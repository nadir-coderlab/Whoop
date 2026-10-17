#!/usr/bin/env python3
"""
Body-scale readings (weight, body fat) sent by the TV-box app «ميزاني TV».

    python3 scale.py ingest     # merge a new payload (SCALE_PAYLOAD) into the store
    python3 scale.py encrypt    # (testing) print an encrypted payload for readings on stdin

How a reading gets here:
  scale  →(Bluetooth)→  TV box app  →(repository_dispatch "scale", encrypted)→  this workflow

The payload is AES-256-GCM encrypted with a key derived from DASHBOARD_PASSWORD
(PBKDF2-SHA256), so the numbers never appear in clear anywhere on GitHub.

Unlike WHOOP, scale readings can't be downloaded again, so they are kept durably:
the whole list lives encrypted (same password) as weights.enc.json on the
`scale-data` branch — ciphertext only, like data.enc.json on GitHub Pages.

Files used during a run (all under data/, which git ignores):
  data/scale-branch/         the scale-data branch content (restored at the start, pushed back if changed)
  data/scale/weights.json    plain list for analyze.py (never committed)
  data/scale.changed         marker: the store changed and must be pushed back
If the password ever changes, the old store can't be opened: it is kept in the branch
as weights.unreadable-<date>.enc.json and a new store starts, so nothing is lost.
"""

import base64
import gzip
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

DATA = Path("data")
BRANCH = DATA / "scale-branch"
STORE = BRANCH / "weights.enc.json"
PLAIN = DATA / "scale" / "weights.json"
CHANGED = DATA / "scale.changed"
STORE_ITER = 310_000
MAX_READINGS = 20_000


def _key(password: str, salt: bytes, iterations: int) -> bytes:
    return PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=iterations).derive(password.encode())


def seal(obj, password: str, iterations: int = STORE_ITER) -> dict:
    salt, iv = os.urandom(16), os.urandom(12)
    raw = gzip.compress(json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode())
    ct = AESGCM(_key(password, salt, iterations)).encrypt(iv, raw, None)
    b = lambda x: base64.b64encode(x).decode()
    return {"v": 1, "iter": iterations, "salt": b(salt), "iv": b(iv), "ct": b(ct)}


def open_sealed(pkg: dict, password: str):
    """Decrypts {v, iter, salt, iv, ct}. The plaintext may be gzip'ed JSON or plain JSON."""
    d = lambda k: base64.b64decode(pkg[k])
    raw = AESGCM(_key(password, d("salt"), int(pkg["iter"]))).decrypt(d("iv"), d("ct"), None)
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    return json.loads(raw.decode())


def clean(r):
    """One reading → {t, w, fat?, imp?, dev?} or None if it isn't plausible."""
    try:
        t = int(r["t"])
        w = round(float(r["w"]), 2)
    except (KeyError, TypeError, ValueError):
        return None
    if not (2 <= w <= 300) or not (946684800000 <= t <= 4102444800000):   # 2000 … 2100
        return None
    out = {"t": t, "w": w}
    for k, lo, hi in (("fat", 1, 75), ("imp", 1, 4000)):
        v = r.get(k)
        if v is not None:
            try:
                v = round(float(v), 1)
                if lo <= v <= hi:
                    out[k] = v
            except (TypeError, ValueError):
                pass
    if r.get("dev"):
        out["dev"] = str(r["dev"])[:24]
    return out


def merge(old: list, new: list) -> tuple[list, int]:
    """Readings are keyed by their timestamp; the app resends recent ones, so duplicates are normal."""
    by_t = {r["t"]: r for r in old}
    added = 0
    for r in new:
        c = clean(r)
        if not c:
            continue
        if c["t"] not in by_t:
            added += 1
        by_t[c["t"]] = {**by_t.get(c["t"], {}), **c}
    merged = sorted(by_t.values(), key=lambda r: r["t"])[-MAX_READINGS:]
    return merged, added


def ingest(password: str):
    BRANCH.mkdir(parents=True, exist_ok=True)
    readings, fresh = [], False
    if STORE.exists():
        try:
            readings = open_sealed(json.loads(STORE.read_text()), password)
            print(f"scale: {len(readings)} readings in the store")
        except Exception:
            # Never overwrite a store we can't read (e.g. the password changed): keep it beside the new one.
            stamp = datetime.now(timezone.utc).strftime("%Y%m%d%H%M")
            STORE.rename(BRANCH / f"weights.unreadable-{stamp}.enc.json")
            print(f"::warning::scale: could not decrypt the stored readings (password changed?) — kept as weights.unreadable-{stamp}.enc.json, starting a new store")
            fresh = True
    else:
        print("scale: no stored readings yet")

    payload = os.getenv("SCALE_PAYLOAD", "").strip()
    added = 0
    if payload and payload not in ("null", "{}"):
        try:
            body = open_sealed(json.loads(payload), password)
            incoming = body.get("readings", []) if isinstance(body, dict) else []
            readings, added = merge(readings, incoming)
            print(f"scale: payload with {len(incoming)} readings → {added} new (app {body.get('app', '?')})")
        except Exception as e:
            print(f"::error::scale: could not open the payload ({type(e).__name__}). Is the TV app using the same DASHBOARD_PASSWORD?")
            sys.exit(1)

    PLAIN.parent.mkdir(parents=True, exist_ok=True)
    PLAIN.write_text(json.dumps(readings))
    if added or fresh:
        STORE.write_text(json.dumps(seal(readings, password)))
        CHANGED.write_text(str(added))
        print(f"scale: store updated ({len(readings)} readings)")


def encrypt_stdin(password: str):
    """For testing the workflow by hand: echo '[{"t":…,"w":…}]' | python3 scale.py encrypt"""
    readings = json.loads(sys.stdin.read())
    print(json.dumps(seal({"readings": readings, "app": "manual"}, password, iterations=200_000)))


if __name__ == "__main__":
    pw = os.getenv("VAULT_PASSWORD") or os.getenv("DASHBOARD_PASSWORD")
    if not pw:
        sys.exit("Set VAULT_PASSWORD")
    {"ingest": ingest, "encrypt": encrypt_stdin}[sys.argv[1]](pw)
