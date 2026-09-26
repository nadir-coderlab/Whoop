#!/usr/bin/env python3
"""
Encrypted storage for the raw WHOOP data between daily runs.

    python3 vault.py pack     # data/raw/  →  data/raw.vault   (AES-256-GCM)
    python3 vault.py unpack   # data/raw.vault  →  data/raw/

The repo is public, so the raw history never goes into git. It lives in the
GitHub Actions cache as one encrypted file; the key comes from the
VAULT_PASSWORD environment variable (the same secret as the dashboard).
If the cache is ever evicted, the next run simply re-downloads from WHOOP.
"""

import io
import os
import sys
import tarfile
from pathlib import Path

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

DATA = Path("data")
RAW = DATA / "raw"
VAULT = DATA / "raw.vault"
MAGIC = b"WHV1"
ITER = 200_000


def key(password: str, salt: bytes) -> bytes:
    return PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=ITER).derive(password.encode())


def pack(password: str):
    if not RAW.exists():
        print("vault: nothing to pack")
        return
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz", compresslevel=6) as tar:
        tar.add(RAW, arcname="raw")
    salt, iv = os.urandom(16), os.urandom(12)
    ct = AESGCM(key(password, salt)).encrypt(iv, buf.getvalue(), None)
    VAULT.write_bytes(MAGIC + salt + iv + ct)
    print(f"vault: packed {sum(1 for _ in RAW.rglob('*.json'))} files → {VAULT.stat().st_size / 1e6:.1f} MB")


def unpack(password: str):
    if not VAULT.exists():
        print("vault: no previous data (first run) — full download")
        return
    blob = VAULT.read_bytes()
    if blob[:4] != MAGIC:
        print("vault: unknown format — ignoring")
        return
    salt, iv, ct = blob[4:20], blob[20:32], blob[32:]
    try:
        raw = AESGCM(key(password, salt)).decrypt(iv, ct, None)
    except Exception:
        print("::warning::vault: could not decrypt (password changed?) — full download")
        return
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz") as tar:
        tar.extractall(DATA, filter="data")
    print(f"vault: restored {sum(1 for _ in RAW.rglob('*.json'))} files")


if __name__ == "__main__":
    pw = os.getenv("VAULT_PASSWORD")
    if not pw:
        sys.exit("Set VAULT_PASSWORD")
    DATA.mkdir(exist_ok=True)
    {"pack": pack, "unpack": unpack}[sys.argv[1]](pw)
