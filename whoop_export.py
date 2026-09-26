#!/usr/bin/env python3
"""
WHOOP full data export
----------------------
Pulls everything the WHOOP web app can see for your account and saves it
as raw JSON (complete, untouched) + clean CSV files ready for analysis.

Output (default: ~/Desktop/whoop_export):
    raw/                  every API response as-is (the run can resume from here)
    csv/daily.csv         one row per day: recovery, HRV, RHR, strain, calories + every extra field WHOOP returns
    csv/sleeps.csv        one row per sleep (naps included): stages, efficiency, respiratory rate, sleep debt
    csv/sleep_stages.csv  the hypnogram: every stage segment with start/end times
    csv/workouts.csv      every workout: sport, strain, HR, HR-zone minutes, distance
    csv/hr_1min.csv       heart rate every minute for the whole history
    csv/hr_6s.csv         heart rate every 6 seconds for the last N days (default 60)

Usage:
    python3 whoop_export.py                     # auto-detects your first day
    python3 whoop_export.py --start 2023-01-01  # or pick the start yourself
    python3 whoop_export.py --hr6-days 180      # more 6-second data (bigger files)

Re-running is safe: finished days are skipped, only new/missing data is pulled.
"""

import argparse
import getpass
import json
import os
import sys
import time
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

try:
    import pandas as pd
    from whoop_data import WhoopClient, get_sport_name, disable_logging
except ImportError:
    sys.exit("Missing packages. Run:  pip install whoop-data pandas")

CYCLE_WINDOW_DAYS = 20      # the cycles endpoint returns max 26 records per call
PAUSE = 0.35                # seconds between requests (be gentle with the server)
EARLIEST_POSSIBLE = date(2015, 1, 1)


# ───────────────────────────── helpers ─────────────────────────────

def log(msg):
    print(f"[{datetime.now():%H:%M:%S}] {msg}", flush=True)


def save_json(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False))
    tmp.replace(path)


def load_json(path: Path):
    return json.loads(path.read_text())


def iso_start(d: date) -> str:
    return f"{d:%Y-%m-%d}T00:00:00.000Z"


def iso_end(d: date) -> str:
    return f"{d:%Y-%m-%d}T23:59:59.999Z"


def call(fn, *args, retries=4, **kwargs):
    """Call an API function with retry + backoff."""
    for attempt in range(retries):
        try:
            out = fn(*args, **kwargs)
            time.sleep(PAUSE)
            return out
        except Exception as e:
            wait = 5 * (attempt + 1)
            log(f"  ! {e}  → retry in {wait}s")
            time.sleep(wait)
    raise RuntimeError(f"Gave up after {retries} attempts")


def parse_during(s):
    """"['2025-10-01T23:30:00.000Z','2025-10-02T07:30:00.000Z')" → (start, end) UTC datetimes."""
    if not s or not isinstance(s, str):
        return None, None
    parts = s.strip("[]()").replace("'", "").replace('"', "").split(",")
    out = []
    for p in parts[:2]:
        p = p.strip()
        try:
            out.append(datetime.fromisoformat(p.replace("Z", "+00:00")))
        except ValueError:
            out.append(None)
    while len(out) < 2:
        out.append(None)
    return out[0], out[1]


def to_local(dt, tz):
    return dt.astimezone(tz).replace(tzinfo=None) if dt else None


def flat_scalars(d: dict, prefix=""):
    """Keep every scalar field (so we never lose metrics the library ignores)."""
    out = {}
    for k, v in (d or {}).items():
        if isinstance(v, dict):
            out.update(flat_scalars(v, f"{prefix}{k}."))
        elif not isinstance(v, list):
            out[f"{prefix}{k}"] = v
    return out


def hrv_ms(v):
    """Some API versions send HRV in seconds (0.052), others in ms (52)."""
    if v is None:
        return None
    return round(v * 1000, 1) if v < 1 else round(v, 1)


def cycle_records(resp):
    if isinstance(resp, dict):
        return resp.get("records", []) or []
    return resp or []


# ───────────────────────────── pull phases ─────────────────────────────

def pull_cycles(client, start: date, end: date, raw: Path):
    """Cycles = recovery + strain + sleep summaries + workouts, in 20-day windows."""
    d = start
    windows = []
    while d <= end:
        w_end = min(d + timedelta(days=CYCLE_WINDOW_DAYS - 1), end)
        windows.append((d, w_end))
        d = w_end + timedelta(days=1)

    for i, (a, b) in enumerate(windows, 1):
        f = raw / "cycles" / f"{a}.json"
        recent = (end - b).days < 3          # always refresh the latest window
        if f.exists() and not recent:
            continue
        resp = call(client.get_cycles, start_time=iso_start(a), end_time=iso_end(b), limit=CYCLE_WINDOW_DAYS + 6)
        save_json(f, resp)
        log(f"cycles {i}/{len(windows)}  {a} → {b}  ({len(cycle_records(resp))} days)")


def detect_start(client, today: date) -> date:
    """Walk backwards until ~6 months of empty windows → that's before your first day."""
    log("Detecting your first WHOOP day...")
    d = today
    first_seen = today
    empty_streak = 0
    while d > EARLIEST_POSSIBLE and empty_streak < 9:
        a = d - timedelta(days=CYCLE_WINDOW_DAYS - 1)
        resp = call(client.get_cycles, start_time=iso_start(a), end_time=iso_end(d), limit=CYCLE_WINDOW_DAYS + 6)
        if cycle_records(resp):
            empty_streak = 0
            first_seen = a
        else:
            empty_streak += 1
        d = a - timedelta(days=1)
    log(f"First data around {first_seen}")
    return first_seen


def all_cycles(raw: Path):
    seen, out = set(), []
    for f in sorted((raw / "cycles").glob("*.json")):
        for rec in cycle_records(load_json(f)):
            cid = (rec.get("cycle") or {}).get("id")
            if cid in seen:
                continue
            seen.add(cid)
            out.append(rec)
    return out


def pull_sleep_events(client, cycles, raw: Path):
    ids = []
    for rec in cycles:
        for s in rec.get("sleeps", []) or []:
            if s.get("activity_id"):
                ids.append(str(s["activity_id"]))
    todo = [i for i in ids if not (raw / "sleep_events" / f"{i}.json").exists()]
    log(f"Sleep hypnograms: {len(ids)} total, {len(todo)} to download")
    for n, aid in enumerate(todo, 1):
        try:
            save_json(raw / "sleep_events" / f"{aid}.json", call(client.get_sleep_event, activity_id=aid))
        except RuntimeError:
            log(f"  skipped sleep {aid}")
        if n % 25 == 0:
            log(f"  sleep {n}/{len(todo)}")


def pull_hr(client, start: date, end: date, step: int, raw: Path):
    folder = raw / f"hr_{step}s"
    days = [start + timedelta(days=i) for i in range((end - start).days + 1)]
    todo = [d for d in days if not (folder / f"{d}.json").exists() or (end - d).days < 2]
    log(f"Heart rate every {step}s: {len(days)} days, {len(todo)} to download")
    for n, d in enumerate(todo, 1):
        try:
            resp = call(client.get_heart_rate, start=iso_start(d), end=iso_end(d), step=step)
            save_json(folder / f"{d}.json", resp)
        except RuntimeError:
            log(f"  skipped {d}")
        if n % 30 == 0 or n == len(todo):
            log(f"  HR {step}s  {n}/{len(todo)}  (at {d})")


# ───────────────────────────── build CSVs ─────────────────────────────

def build_csvs(raw: Path, out: Path, tz):
    out.mkdir(parents=True, exist_ok=True)
    cycles = all_cycles(raw)

    daily, sleeps, workouts = [], [], []
    for rec in cycles:
        cyc = rec.get("cycle") or {}
        days = cyc.get("days", "") or ""
        day = days.replace("['", "").replace("')", "").split("','")[0]

        row = {
            "date": day,
            "recovery_score": rec.get("score"),
            "hrv_ms": hrv_ms(rec.get("hrv_rmssd_milli")),
            "resting_hr": rec.get("resting_heart_rate"),
            "day_strain": cyc.get("scaled_strain"),
            "day_avg_hr": cyc.get("day_avg_heart_rate"),
            "day_max_hr": cyc.get("day_max_heart_rate"),
            "calories": round(cyc["day_kilojoules"] / 4.184) if cyc.get("day_kilojoules") else None,
            "n_sleeps": len(rec.get("sleeps") or []),
            "n_workouts": len(rec.get("workouts") or []),
        }
        # keep every other scalar field WHOOP sends (skin temp, SpO2, etc. when present)
        extras = flat_scalars({k: v for k, v in rec.items() if k != "cycle"}, "rec.")
        extras.update(flat_scalars(cyc, "cycle."))
        row.update(extras)
        daily.append(row)

        for s in rec.get("sleeps") or []:
            st, en = parse_during(s.get("during"))
            ms = lambda k: round(s[k] / 60000, 1) if s.get(k) is not None else None
            srow = {
                "date": day,
                "activity_id": s.get("activity_id"),
                "start_local": to_local(st, tz),
                "end_local": to_local(en, tz),
                "is_nap": s.get("is_nap", s.get("nap")),
                "sleep_score": s.get("score"),
                "asleep_min": ms("quality_duration"),
                "in_bed_min": ms("time_in_bed"),
                "latency_min": ms("latency"),
                "light_min": ms("light_sleep_duration"),
                "deep_sws_min": ms("slow_wave_sleep_duration"),
                "rem_min": ms("rem_sleep_duration"),
                "awake_min": ms("wake_duration"),
                "efficiency_pct": s.get("sleep_efficiency"),
                "respiratory_rate": s.get("respiratory_rate"),
                "disturbances": s.get("disturbances"),
                "sleep_need_min": ms("sleep_need"),
                "debt_before_min": ms("debt_pre"),
                "debt_after_min": ms("debt_post"),
            }
            srow.update(flat_scalars(s, "raw."))
            sleeps.append(srow)

        for w in rec.get("workouts") or []:
            st, en = parse_during(w.get("during"))
            z = w.get("zone_duration") or {}
            zmin = lambda k: round(z[k] / 60000, 1) if z.get(k) is not None else None
            wrow = {
                "date": day,
                "sport_id": w.get("sport_id"),
                "sport": get_sport_name(w.get("sport_id")) if w.get("sport_id") is not None else None,
                "start_local": to_local(st, tz),
                "end_local": to_local(en, tz),
                "duration_min": round((en - st).total_seconds() / 60, 1) if st and en else None,
                "strain": w.get("score"),
                "avg_hr": w.get("average_heart_rate"),
                "max_hr": w.get("max_heart_rate"),
                "calories": round(w["kilojoules"] / 4.184) if w.get("kilojoules") else None,
                "distance_km": round(w["distance_meter"] / 1000, 2) if w.get("distance_meter") else None,
                "zone0_min": zmin("zone_zero_milli"), "zone1_min": zmin("zone_one_milli"),
                "zone2_min": zmin("zone_two_milli"), "zone3_min": zmin("zone_three_milli"),
                "zone4_min": zmin("zone_four_milli"), "zone5_min": zmin("zone_five_milli"),
            }
            wrow.update(flat_scalars(w, "raw."))
            workouts.append(wrow)

    pd.DataFrame(daily).sort_values("date").to_csv(out / "daily.csv", index=False)
    pd.DataFrame(sleeps).sort_values("start_local").to_csv(out / "sleeps.csv", index=False) if sleeps else None
    pd.DataFrame(workouts).sort_values("start_local").to_csv(out / "workouts.csv", index=False) if workouts else None
    log(f"daily.csv {len(daily)} days | sleeps.csv {len(sleeps)} | workouts.csv {len(workouts)}")

    # hypnogram
    stages = []
    for f in (raw / "sleep_events").glob("*.json"):
        data = load_json(f)
        segs = data if isinstance(data, list) else next(
            (v for v in (data or {}).values() if isinstance(v, list) and v and isinstance(v[0], dict) and "during" in v[0]),
            [])
        for seg in segs:
            st, en = parse_during(seg.get("during"))
            stages.append({
                "activity_id": f.stem,
                "stage": seg.get("type"),
                "start_local": to_local(st, tz),
                "end_local": to_local(en, tz),
                "minutes": round((en - st).total_seconds() / 60, 2) if st and en else None,
            })
    if stages:
        pd.DataFrame(stages).sort_values(["start_local"]).to_csv(out / "sleep_stages.csv", index=False)
        log(f"sleep_stages.csv {len(stages)} segments")

    # heart rate
    for step, name in ((60, "hr_1min.csv"), (6, "hr_6s.csv")):
        folder = raw / f"hr_{step}s"
        files = sorted(folder.glob("*.json")) if folder.exists() else []
        if not files:
            continue
        frames = []
        for f in files:
            vals = (load_json(f) or {}).get("values", [])
            if vals:
                frames.append(pd.DataFrame({"ts_ms": [v["time"] for v in vals], "hr": [v["data"] for v in vals]}))
        if not frames:
            continue
        df = pd.concat(frames).drop_duplicates("ts_ms").sort_values("ts_ms")
        df["time_local"] = pd.to_datetime(df["ts_ms"], unit="ms", utc=True).dt.tz_convert(tz).dt.tz_localize(None)
        df[["time_local", "hr", "ts_ms"]].to_csv(out / name, index=False)
        log(f"{name} {len(df):,} readings")


# ───────────────────────────── main ─────────────────────────────

def main():
    ap = argparse.ArgumentParser(description="Full WHOOP data export")
    ap.add_argument("--start", help="YYYY-MM-DD (default: auto-detect first day)")
    ap.add_argument("--end", help="YYYY-MM-DD (default: today)")
    ap.add_argument("--out", default=str(Path.home() / "Desktop" / "whoop_export"))
    ap.add_argument("--hr6-days", type=int, default=60, help="days of 6-second HR (0 = skip)")
    ap.add_argument("--no-hr", action="store_true", help="skip heart-rate download")
    ap.add_argument("--tz", default="Asia/Riyadh")
    ap.add_argument("--csv-only", action="store_true", help="rebuild CSVs from raw/ without downloading")
    args = ap.parse_args()

    out = Path(args.out).expanduser()
    raw = out / "raw"
    tz = ZoneInfo(args.tz)

    if not args.csv_only:
        disable_logging()
        email = os.getenv("WHOOP_USERNAME") or input("WHOOP email: ").strip()
        pwd = os.getenv("WHOOP_PASSWORD") or getpass.getpass("WHOOP password (hidden): ")
        log("Signing in...")
        client = WhoopClient(username=email, password=pwd)
        log("Signed in ✓")

        end = date.fromisoformat(args.end) if args.end else date.today()
        start_file = raw / "start_date.txt"
        if args.start:
            start = date.fromisoformat(args.start)
        elif start_file.exists():
            start = date.fromisoformat(start_file.read_text().strip())
        else:
            start = detect_start(client, end)
        start_file.parent.mkdir(parents=True, exist_ok=True)
        start_file.write_text(str(start))
        log(f"Range: {start} → {end}  ({(end - start).days + 1} days)")

        pull_cycles(client, start, end, raw)
        cycles = all_cycles(raw)
        pull_sleep_events(client, cycles, raw)
        try:
            save_json(raw / "sports_history.json", call(client.get_sports_history))
        except RuntimeError:
            pass
        if not args.no_hr:
            pull_hr(client, start, end, 60, raw)
            if args.hr6_days > 0:
                pull_hr(client, max(start, end - timedelta(days=args.hr6_days - 1)), end, 6, raw)

    log("Building CSV files...")
    build_csvs(raw, out / "csv", tz)
    log(f"Done ✓  →  {out / 'csv'}")


if __name__ == "__main__":
    main()
