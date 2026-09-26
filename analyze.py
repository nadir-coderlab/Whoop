#!/usr/bin/env python3
"""
Turns the exported CSVs into the dashboard's data file, then encrypts it.

    python3 analyze.py --csv whoop_export/csv --out site --password "..."

Writes site/data.enc.json (AES-256-GCM, key from PBKDF2-SHA256).
The page asks for the same password and decrypts it in the browser,
so the file is useless to anyone who finds it on GitHub Pages.
"""

import argparse
import base64
import gzip
import json
import math
import os
from datetime import date, datetime, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

AR_DAYS = ["الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد"]
WEEK_ORDER = [6, 0, 1, 2, 3, 4, 5]          # Saudi week: Sunday first

# Umm al-Qura Ramadan dates (start, last day)
RAMADAN = {
    "رمضان 1445": ("2024-03-11", "2024-04-09"),
    "رمضان 1446": ("2025-03-01", "2025-03-29"),
    "رمضان 1447": ("2026-02-18", "2026-03-19"),
    "رمضان 1448": ("2027-02-08", "2027-03-09"),
}

PBKDF2_ITER = 310_000


# ───────────────────────── helpers ─────────────────────────

def r(x, n=1):
    if x is None:
        return None
    try:
        if isinstance(x, (float, np.floating)) and (math.isnan(x) or math.isinf(x)):
            return None
    except TypeError:
        return None
    return round(float(x), n)


def hour_of(ts, pivot=12):
    """Clock hour as a continuous number; times after midnight become 24+ (for bedtimes)."""
    h = ts.hour + ts.minute / 60
    return h + 24 if h < pivot else h


def fmt_clock(h):
    if h is None:
        return None
    h = h % 24
    hh, mm = int(h), int(round((h - int(h)) * 60))
    if mm == 60:
        hh, mm = (hh + 1) % 24, 0
    return f"{hh}:{mm:02d}"


def load(csv_dir: Path):
    rd = lambda n, **kw: pd.read_csv(csv_dir / n, **kw) if (csv_dir / n).exists() else pd.DataFrame()
    daily = rd("daily.csv", parse_dates=["date"])
    sleeps = rd("sleeps.csv", parse_dates=["start_local", "end_local"])
    stages = rd("sleep_stages.csv", parse_dates=["start_local", "end_local"])
    works = rd("workouts.csv", parse_dates=["start_local", "end_local"])
    hr = rd("hr_1min.csv", usecols=["time_local", "hr"], parse_dates=["time_local"])
    if not hr.empty:
        hr = hr.dropna().drop_duplicates("time_local").set_index("time_local").sort_index()["hr"]
    return daily, sleeps, stages, works, hr


# ───────────────────────── nights ─────────────────────────

def main_sleeps(sleeps):
    if sleeps.empty:
        return sleeps
    s = sleeps.copy()
    if "is_nap" in s:
        s = s[s["is_nap"].astype(str).str.lower() != "true"]
    s = s.dropna(subset=["start_local", "end_local"])
    s = s.sort_values("asleep_min", ascending=False).drop_duplicates("date").sort_values("date")
    s["date"] = pd.to_datetime(s["date"])
    s["sleep_h"] = s["asleep_min"] / 60
    s["bed_h"] = s["start_local"].apply(hour_of)
    s["wake_h"] = s["end_local"].apply(lambda t: t.hour + t.minute / 60)
    mid = s["start_local"] + (s["end_local"] - s["start_local"]) / 2
    s["mid_h"] = mid.apply(lambda t: hour_of(t, pivot=18))
    return s


def night_hr(ms, hr, curves_last=30):
    """Nightly HR shape: lowest point and when it happens."""
    rows, curves = [], {}
    if hr is None or len(hr) == 0 or ms.empty:
        return pd.DataFrame(), curves
    recent = set(ms["date"].sort_values().tail(curves_last))
    for _, s in ms.iterrows():
        seg = hr[s["start_local"]:s["end_local"]]
        if len(seg) < 60:
            continue
        sm = seg.rolling(5, center=True, min_periods=3).median()
        t_min = sm.idxmin()
        span = (s["end_local"] - s["start_local"]).total_seconds()
        pct = (t_min - s["start_local"]).total_seconds() / span
        first = seg[: s["start_local"] + timedelta(hours=1)].mean()
        rows.append({"date": s["date"], "nadir_hr": sm.min(), "nadir_pct": pct,
                     "nadir_clock": t_min.hour + t_min.minute / 60, "first_hour_hr": first,
                     "night_avg_hr": seg.mean()})
        if s["date"] in recent:
            c = seg.resample("5min").mean().dropna()
            curves[s["date"].strftime("%Y-%m-%d")] = {
                "start": s["start_local"].strftime("%Y-%m-%dT%H:%M"),
                "t": [int((i - s["start_local"]).total_seconds() // 60) for i in c.index],
                "hr": [r(v, 0) for v in c.values],
                "nadir_hr": r(sm.min(), 0), "nadir_min": int((t_min - s["start_local"]).total_seconds() // 60),
                "nadir_clock": t_min.strftime("%H:%M"), "nadir_pct": r(pct * 100, 0),
            }
    return pd.DataFrame(rows), curves


def hypnograms(ms, stages, last=30):
    out = {}
    if stages.empty or ms.empty:
        return out
    for _, s in ms.sort_values("date").tail(last).iterrows():
        seg = stages[stages["activity_id"].astype(str) == str(s["activity_id"])].sort_values("start_local")
        if seg.empty:
            continue
        out[s["date"].strftime("%Y-%m-%d")] = [
            [int((a - s["start_local"]).total_seconds() // 60), int((b - s["start_local"]).total_seconds() // 60), t]
            for a, b, t in zip(seg["start_local"], seg["end_local"], seg["stage"]) if pd.notna(a) and pd.notna(b)]
    return out


# ───────────────────────── naps ─────────────────────────

def naps_block(sleeps, ms, daily, days=90):
    """Naps = every sleep that isn't the day's main sleep (plus anything WHOOP flags as a nap)."""
    if sleeps.empty or ms.empty:
        return {"count": 0, "recent": []}
    s = sleeps.dropna(subset=["start_local", "end_local"]).copy()
    main_ids = set(ms["activity_id"].astype(str))
    n = s[~s["activity_id"].astype(str).isin(main_ids)].copy()
    if n.empty:
        return {"count": 0, "recent": []}
    n["date"] = pd.to_datetime(n["date"])
    n["dur"] = n["asleep_min"].where(n["asleep_min"].notna(),
                                    (n["end_local"] - n["start_local"]).dt.total_seconds() / 60)
    n = n.sort_values("start_local")
    last = n[n["date"] >= n["date"].max() - timedelta(days=days)]
    out = {
        "count": int(len(last)), "days": days,
        "days_with": int(last["date"].nunique()),
        "avg_min": r(last["dur"].mean(), 0),
        "typical_start": fmt_clock(last["start_local"].apply(lambda t: t.hour + t.minute / 60).median()) if len(last) else None,
        "recent": [{"date": d.strftime("%Y-%m-%d"), "start": a.strftime("%H:%M"), "end": b.strftime("%H:%M"), "min": r(m, 0)}
                   for d, a, b, m in zip(n["date"].tail(12)[::-1], n["start_local"].tail(12)[::-1],
                                         n["end_local"].tail(12)[::-1], n["dur"].tail(12)[::-1])],
    }
    # recovery the next morning: nap days vs no-nap days (last 180 days)
    d = daily[["date", "recovery_score"]].dropna().copy()
    d = d[d["date"] >= d["date"].max() - timedelta(days=180)]
    nap_days = set(n["date"] + timedelta(days=1))
    with_nap = d[d["date"].isin(nap_days)]["recovery_score"]
    without = d[~d["date"].isin(nap_days)]["recovery_score"]
    if len(with_nap) >= 5 and len(without) >= 5:
        out["recovery_after_nap"] = r(with_nap.mean(), 0)
        out["recovery_no_nap"] = r(without.mean(), 0)
        out["n_after_nap"] = int(len(with_nap))
    return out


# ───────────────────────── workouts ─────────────────────────

def workout_hrr(works, hr):
    """Heart-rate recovery: how many beats HR drops 1 and 2 minutes after the workout ends."""
    out = []
    if works.empty:
        return out
    for _, w in works.sort_values("start_local").iterrows():
        row = {"date": str(w["date"])[:10], "sport": w.get("sport"), "start": w["start_local"].strftime("%H:%M") if pd.notna(w["start_local"]) else None,
               "min": r(w.get("duration_min"), 0), "strain": r(w.get("strain")), "avg_hr": r(w.get("avg_hr"), 0),
               "max_hr": r(w.get("max_hr"), 0), "kcal": r(w.get("calories"), 0), "hrr1": None, "hrr2": None,
               "z45": r((w.get("zone4_min") or 0) + (w.get("zone5_min") or 0), 0)}
        if len(hr) and pd.notna(w["end_local"]) and (w.get("avg_hr") or 0) >= 100:
            e = w["end_local"]
            before = hr[e - timedelta(minutes=2):e]
            a1 = hr[e + timedelta(seconds=30):e + timedelta(seconds=90)]
            a2 = hr[e + timedelta(seconds=90):e + timedelta(seconds=150)]
            if len(before) and len(a1):
                row["hrr1"] = r(before.iloc[-1] - a1.iloc[0], 0)
            if len(before) and len(a2):
                row["hrr2"] = r(before.iloc[-1] - a2.iloc[0], 0)
        out.append(row)
    return out


# ───────────────────────── drivers ─────────────────────────

def drivers(daily, ms, nights, works, window=180):
    """Which behaviours move YOUR recovery — median split, difference in recovery points."""
    if daily.empty or ms.empty:
        return []
    d = daily[["date", "recovery_score"]].copy()
    d = d.merge(ms[["date", "bed_h", "sleep_h", "efficiency_pct", "start_local"]], on="date", how="left")
    if not nights.empty:
        d = d.merge(nights[["date", "nadir_pct"]], on="date", how="left")
    else:
        d["nadir_pct"] = np.nan
    d = d.sort_values("date")
    d["prev_strain"] = daily.set_index("date")["day_strain"].reindex(d["date"] - timedelta(days=1)).values
    d["bed_dev"] = (d["bed_h"] - d["bed_h"].rolling(7, min_periods=4).median().shift(1)).abs()
    d["weekend_night"] = d["start_local"].dt.weekday.isin([3, 4]).astype(float)
    d.loc[d["start_local"].isna(), "weekend_night"] = np.nan
    late = set()
    if not works.empty:
        w = works.dropna(subset=["end_local"])
        for _, x in w.iterrows():
            if hour_of(x["end_local"], pivot=6) >= 20:
                late.add(pd.Timestamp(x["date"]) + timedelta(days=1))
    d["late_workout"] = d["date"].isin(late).astype(float)
    d = d[d["date"] >= d["date"].max() - timedelta(days=window)].dropna(subset=["recovery_score"])

    feats = [
        # key, type, threshold, text if HIGH, text if LOW
        ("bed_h", "median", None, "لما تنام بعد {t}", "لما تنام قبل {t}"),
        ("sleep_h", "median", None, "لما تنام أكثر من {t} ساعة", "لما تنام أقل من {t} ساعة"),
        ("nadir_pct", "fixed", 0.5, "لما يوصل نبضك لأدناه في النص الثاني من الليل", "لما يوصل نبضك لأدناه في النص الأول من الليل"),
        ("bed_dev", "fixed", 0.75, "لما يختلف وقت نومك عن عادتك بأكثر من 45 دقيقة", "لما تنام في وقتك المعتاد"),
        ("prev_strain", "median", None, "لما يكون إجهاد أمس فوق {t}", "لما يكون إجهاد أمس تحت {t}"),
        ("efficiency_pct", "median", None, "لما تكون كفاءة نومك فوق {t}%", "لما تكون كفاءة نومك تحت {t}%"),
        ("late_workout", "fixed", 0.5, "لما تتمرن بعد 8 الليل", "لما يكون تمرينك قبل 8 الليل أو ما فيه تمرين"),
        ("weekend_night", "fixed", 0.5, "ليالي الخميس والجمعة", "ليالي أيام الدوام"),
    ]
    out = []
    for key, kind, thr, hi_txt, lo_txt in feats:
        x = d[[key, "recovery_score"]].dropna()
        if len(x) < 20:
            continue
        t = x[key].median() if kind == "median" else thr
        hi = x[x[key] > t]["recovery_score"]
        lo = x[x[key] <= t]["recovery_score"]
        if len(hi) < 6 or len(lo) < 6:
            continue
        diff = hi.mean() - lo.mean()
        rho = x[key].rank().corr(x["recovery_score"].rank())
        if key == "bed_h":
            tt = fmt_clock(t)
        elif key in ("sleep_h",):
            tt = f"{t:.1f}"
        else:
            tt = f"{t:.0f}"
        better_hi = diff > 0
        text = (hi_txt if better_hi else lo_txt).format(t=tt)
        out.append({"key": key, "text": text, "diff": r(abs(diff), 0), "rho": r(rho, 2),
                    "n": int(len(x)), "n_better": int(len(hi) if better_hi else len(lo)),
                    "better_avg": r(hi.mean() if better_hi else lo.mean(), 0),
                    "worse_avg": r(lo.mean() if better_hi else hi.mean(), 0),
                    "strong": bool(abs(rho) >= 0.15 and abs(diff) >= 4)})
    out.sort(key=lambda z: -z["diff"])
    return out


# ───────────────────────── other blocks ─────────────────────────

def find_col(df, *needles):
    for c in df.columns:
        lc = c.lower()
        if all(n in lc for n in needles):
            return c
    return None


def build(csv_dir: Path):
    daily, sleeps, stages, works, hr = load(csv_dir)
    daily = daily.sort_values("date").drop_duplicates("date")
    ms = main_sleeps(sleeps)
    nights, curves = night_hr(ms, hr)

    base = daily[["date", "recovery_score", "hrv_ms", "resting_hr", "day_strain", "calories"]].copy()
    base = base.merge(ms[["date", "sleep_h", "bed_h", "wake_h", "mid_h", "efficiency_pct", "respiratory_rate",
                          "deep_sws_min", "rem_min", "light_min", "awake_min"]], on="date", how="left")
    if not nights.empty:
        base = base.merge(nights, on="date", how="left")
    temp_c = find_col(daily, "skin_temp")
    spo2_c = find_col(daily, "spo2")
    if temp_c:
        base["skin_temp"] = daily[temp_c].values
    if spo2_c:
        base["spo2"] = daily[spo2_c].values
    base = base.sort_values("date").reset_index(drop=True)

    # ── series (full history, dashboard filters the range)
    def col(c, n=1):
        return [r(v, n) for v in base[c]] if c in base else []
    series = {
        "date": base["date"].dt.strftime("%Y-%m-%d").tolist(),
        "recovery": col("recovery_score", 0), "hrv": col("hrv_ms"), "rhr": col("resting_hr", 0),
        "strain": col("day_strain"), "sleep_h": col("sleep_h", 2), "bed_h": col("bed_h", 2),
        "wake_h": col("wake_h", 2), "eff": col("efficiency_pct"), "resp": col("respiratory_rate", 2),
        "deep": col("deep_sws_min", 0), "rem": col("rem_min", 0), "light": col("light_min", 0),
        "awake": col("awake_min", 0), "nadir_hr": col("nadir_hr", 0), "nadir_pct": [r(v * 100, 0) if v is not None and not pd.isna(v) else None for v in base.get("nadir_pct", pd.Series(dtype=float))],
        "skin_temp": col("skin_temp", 2), "spo2": col("spo2", 1), "kcal": col("calories", 0),
    }

    # ── KPIs: last value vs personal 30-day baseline
    last = base.iloc[-1]
    prev30 = base.iloc[-31:-1]
    def kpi(c, n=1):
        if c not in base:
            return None
        v = last.get(c)
        return {"value": r(v, n), "avg7": r(base[c].tail(7).mean(), n), "avg30": r(prev30[c].mean(), n),
                "spark": [r(x, n) for x in base[c].tail(14)]}
    kpis = {k: kpi(c, n) for k, c, n in (("recovery", "recovery_score", 0), ("hrv", "hrv_ms", 0),
                                          ("rhr", "resting_hr", 0), ("sleep_h", "sleep_h", 1),
                                          ("strain", "day_strain", 1), ("resp", "respiratory_rate", 1))}

    # ── early-warning check (today vs previous 30 days)
    alerts = []
    for c, label, sign in (("resting_hr", "نبض الراحة", 1), ("respiratory_rate", "معدل التنفس", 1),
                           ("hrv_ms", "HRV", -1), ("skin_temp", "حرارة الجلد", 1)):
        if c not in base or pd.isna(last.get(c)):
            continue
        ref = prev30[c].dropna()
        if len(ref) < 14 or ref.std() == 0:
            continue
        zz = (last[c] - ref.mean()) / ref.std()
        if sign * zz >= 2:
            alerts.append({"metric": label, "value": r(last[c], 1), "avg": r(ref.mean(), 1), "z": r(zz, 1)})

    # ── weekday pattern (by the night before)
    wk = base.copy()
    wk["wd"] = (wk["date"] - timedelta(days=1)).dt.weekday
    wk = wk[wk["date"] >= wk["date"].max() - timedelta(days=180)]
    weekday = []
    for i in WEEK_ORDER:
        g = wk[wk["wd"] == i]
        weekday.append({"night": "ليلة " + AR_DAYS[i], "recovery": r(g["recovery_score"].mean(), 0),
                        "sleep_h": r(g["sleep_h"].mean(), 1), "bed": fmt_clock(g["bed_h"].mean()) if g["bed_h"].notna().any() else None,
                        "n": int(len(g))})

    # ── social jetlag (midpoint of sleep: weekend nights vs work nights)
    sj = None
    w90 = wk[wk["date"] >= wk["date"].max() - timedelta(days=90)]
    if w90["mid_h"].notna().sum() > 20:
        we = w90[w90["wd"].isin([3, 4])]["mid_h"].mean()
        wd_ = w90[~w90["wd"].isin([3, 4])]["mid_h"].mean()
        sj = {"hours": r(we - wd_, 1), "weekend_mid": fmt_clock(we), "work_mid": fmt_clock(wd_)}

    # ── Ramadan vs the 30 days before it
    ramadan = []
    for name, (a, b) in RAMADAN.items():
        a, b = pd.Timestamp(a), pd.Timestamp(b)
        during = base[(base["date"] >= a) & (base["date"] <= b)]
        before = base[(base["date"] >= a - timedelta(days=30)) & (base["date"] < a)]
        if len(during) < 15 or len(before) < 15:
            continue
        row = {"name": name, "days": int(len(during))}
        for k, c, n in (("recovery", "recovery_score", 0), ("hrv", "hrv_ms", 0), ("rhr", "resting_hr", 0),
                        ("sleep_h", "sleep_h", 1)):
            row[k] = [r(before[c].mean(), n), r(during[c].mean(), n)]
        row["bed"] = [fmt_clock(before["bed_h"].mean()), fmt_clock(during["bed_h"].mean())]
        ramadan.append(row)

    out = {
        "meta": {"generated": datetime.now().strftime("%Y-%m-%d %H:%M"),
                 "first": series["date"][0], "last": series["date"][-1], "days": len(series["date"])},
        "kpis": kpis, "alerts": alerts, "series": series,
        "drivers": drivers(daily, ms, nights, works),
        "curves": curves, "hypno": hypnograms(ms, stages),
        "workouts": workout_hrr(works, hr),
        "weekday": weekday, "social_jetlag": sj, "ramadan": ramadan,
        "naps": naps_block(sleeps, ms, daily),
    }
    return out


# ───────────────────────── encryption ─────────────────────────

def encrypt(payload: dict, password: str) -> dict:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
    from cryptography.hazmat.primitives import hashes
    salt, iv = os.urandom(16), os.urandom(12)
    key = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=PBKDF2_ITER).derive(password.encode())
    raw = gzip.compress(json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode())
    ct = AESGCM(key).encrypt(iv, raw, None)
    b = lambda x: base64.b64encode(x).decode()
    return {"v": 1, "iter": PBKDF2_ITER, "salt": b(salt), "iv": b(iv), "ct": b(ct)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default="whoop_export/csv")
    ap.add_argument("--out", default="site")
    ap.add_argument("--password", default=os.getenv("DASHBOARD_PASSWORD"))
    ap.add_argument("--plain", action="store_true", help="also write unencrypted data.json (local testing only)")
    a = ap.parse_args()
    if not a.password:
        raise SystemExit("Set DASHBOARD_PASSWORD (or --password)")
    data = build(Path(a.csv))
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "data.enc.json").write_text(json.dumps(encrypt(data, a.password)))
    if a.plain:
        (out / "data.json").write_text(json.dumps(data, ensure_ascii=False, indent=1))
    print(f"✓ {data['meta']['days']} days  {data['meta']['first']} → {data['meta']['last']}  "
          f"| drivers {len(data['drivers'])} | alerts {len(data['alerts'])} | {out/'data.enc.json'}")


if __name__ == "__main__":
    main()
