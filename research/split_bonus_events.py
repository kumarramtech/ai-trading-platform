#!/usr/bin/env python3
"""
Split / bonus ex-date study, step 1: event list (rules approved by the user 2026-11-20; NSE CM bhavcopies only,
no intraday prices are used).

Event   an ex-date of a stock split or bonus issue, 2024-10-01 .. 2026-09-30, EQ series (same symbol both days).
        ratio = ClsPric(T-1) / OpnPric(T): the ex-date opens at a fraction of the previous close. (Fix of
        2026-11-20, before any test: the approved rule used PrvsClsgPric(T), but NSE does NOT adjust it in the
        UDiFF bhavcopy, e.g. BAJFINANCE 2025-06-16 prev close 9331 raw, open ~938; it found 0 events.)
        Price bands cap a normal move at 20% (ratio <= 1.25), so a ratio >= 1.3 is a corporate action.
Keep    company shares only (ISIN INE..., so no ETF unit splits), ratio within 10% of a clean split/bonus ratio:
        1.5, 2, 2.5, 3, 4, 5, 10. (Second fix, same day, still before any intraday price: a 4% band dropped real splits
        that opened with a gap, e.g. ADANIPOWER 2025-09-22 4.79, TATAINVEST 2025-10-14 9.52, NBCC 2024-10-07 1.43,
        which would select events by their opening move.)
Exclude demergers by hand (EXCLUDE below): a demerger also cuts the price, but it is not a split or bonus.
Report  other ratios >= 1.3 (demergers, capital changes, data errors) go to stderr for a hand check; not events.
Usage   python3 split_bonus_events.py > split_bonus_events.tsv 2> split_bonus_listing.txt
"""
import csv
import datetime as dt
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request
import zipfile

UA = "research-client/1.0"
CACHE = "/tmp/cm_bhav2"
FROM, TO = dt.date(2024, 9, 20), dt.date(2026, 9, 30)
EV_FROM = dt.date(2024, 10, 1)
CLEAN = (1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 10.0)
TOL = 0.10
EXCLUDE = {
    ("SIEMENS", "2025-04-07"): "demerger of Siemens Energy India",
    ("ABFRL", "2025-05-22"): "demerger of Aditya Birla Lifestyle Brands",
    ("TATAMOTORS", "2025-10-14"): "demerger of the commercial vehicle business",
    ("VEDL", "2026-04-30"): "Vedanta demerger (no split/bonus; ratio not clean, ISIN unchanged)",
}


def http(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as ex:
            if ex.code in (400, 403, 404):
                return None
            time.sleep(10 + 10 * attempt)
        except Exception:
            time.sleep(5 + 5 * attempt)
    return None


def day_file(day):
    """{symbol: [isin, close, open, traded_value]} for EQ series, or None (no file)."""
    p = os.path.join(CACHE, day.isoformat() + ".json")
    if os.path.exists(p):
        return json.load(open(p))
    data = http("https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_%s_F_0000.csv.zip" % day.strftime("%Y%m%d"))
    time.sleep(0.4)
    out = None
    if data:
        z = zipfile.ZipFile(io.BytesIO(data))
        rd = csv.DictReader(io.StringIO(z.read(z.namelist()[0]).decode("utf-8", "replace")))
        out = {}
        for r in rd:
            r = {k.strip(): (v or "").strip() for k, v in r.items() if k}
            if r.get("SctySrs") == "EQ":
                try:
                    out[r["TckrSymb"]] = [r.get("ISIN", ""), float(r["ClsPric"]), float(r["OpnPric"]),
                                          float(r.get("TtlTrfVal") or 0)]
                except (KeyError, ValueError):
                    pass
    json.dump(out, open(p, "w"))
    return out


def main():
    os.makedirs(CACHE, exist_ok=True)
    cal, files = [], {}
    d = FROM
    while d <= TO:
        if d.weekday() < 5:
            f = day_file(d)
            if f:
                cal.append(d)
                files[d] = f
        d += dt.timedelta(days=1)
    print("trading days %d (%s .. %s)" % (len(cal), cal[0], cal[-1]), file=sys.stderr)

    print("index\tside\tsymbol\tannounce_date\tchange_close_date\tsource\tratio\tisin_before\tisin_on")
    kept, odd = 0, []
    for i in range(1, len(cal)):
        day, prev = cal[i], cal[i - 1]
        if day < EV_FROM:
            continue
        for sym, (isin, close, opn, tv) in files[day].items():
            y = files[prev].get(sym)
            if not y or opn <= 0:
                continue
            ratio = y[1] / opn
            if ratio < 1.3:
                continue
            clean = next((c for c in CLEAN if abs(ratio / c - 1) <= TOL), None)
            why = EXCLUDE.get((sym, day.isoformat())) or (None if isin.startswith("INE") else "not a company share (ETF/fund)")
            if clean and why:
                odd.append("  EXCLUDED %s %-12s ratio %.4f: %s" % (day, sym, ratio, why))
            elif clean:
                print("SPLIT BONUS\tS1\t%s\t%s\t%s\tNSE CM bhavcopy %s\t%.4f\t%s\t%s" % (
                    sym, prev, day, day.strftime("%Y%m%d"), ratio, y[0], isin))
                kept += 1
            else:
                odd.append("  %s %-12s ratio %.4f  close(T-1) %.2f  open(T) %.2f  ISIN %s -> %s" % (
                    day, sym, ratio, y[1], opn, y[0], isin))
    print("events kept: %d; other ratios >= 1.3 (not events, hand check): %d" % (kept, len(odd)), file=sys.stderr)
    print("\n".join(odd), file=sys.stderr)


if __name__ == "__main__":
    main()
