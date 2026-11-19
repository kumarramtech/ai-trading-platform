#!/usr/bin/env python3
"""
F&O inclusion / exclusion study, step 1: event list (rules approved by the user 2026-11-19; NSE F&O bhavcopies
only, no prices are used).

Universe each trading day: the stock symbols with a stock futures contract (STF) in the NSE F&O UDiFF bhavcopy,
2024-07-08 .. 2026-11-18.
F1 (added)   the first day a symbol has futures, when it had none on any of the previous 40 trading days
             (and the day is on/after 2024-10-01).
F2 (removed) the last day a symbol has futures, when it then has none for the next 20 trading days (or to the
             end of the data, if at least 20 trading days remain), on/after 2024-10-01.
Renames      an F1 and an F2 within 3 trading days of each other with the same ISIN in the CM bhavcopy
             (e.g. ZOMATO -> ETERNAL) are a symbol change, not an event: both are dropped (listed on stderr).
Usage        python3 fo_inclusion_events.py > fo_inclusion_events.tsv
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

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import index_rebalance as ir

UA = "research-client/1.0"
CACHE = "/tmp/fo_univ"
FROM, TO = dt.date(2024, 7, 8), dt.date(2026, 11, 18)
EV_FROM = dt.date(2024, 10, 1)


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


def universe(day):
    """sorted list of symbols with stock futures that day, or None (no file)."""
    p = os.path.join(CACHE, day.isoformat() + ".json")
    if os.path.exists(p):
        return json.load(open(p))
    data = http("https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_%s_F_0000.csv.zip" % day.strftime("%Y%m%d"))
    time.sleep(0.4)
    out = None
    if data:
        try:
            z = zipfile.ZipFile(io.BytesIO(data))
            rd = csv.reader(io.StringIO(z.read(z.namelist()[0]).decode("utf-8", "replace")))
            head = [h.strip() for h in next(rd)]
            ti, si = head.index("FinInstrmTp"), head.index("TckrSymb")
            out = sorted({r[si].strip() for r in rd if len(r) > max(ti, si) and r[ti].strip() == "STF"})
        except Exception:
            out = None
    json.dump(out, open(p, "w"))
    return out


def main():
    os.makedirs(CACHE, exist_ok=True)
    os.makedirs(ir.CACHE, exist_ok=True)
    cal, uni = [], {}
    d = FROM
    while d <= TO:
        if d.weekday() < 5:
            u = universe(d)
            if u:
                cal.append(d)
                uni[d] = set(u)
        d += dt.timedelta(days=1)
    print("trading days %d (%s .. %s), futures stocks first %d last %d" % (
        len(cal), cal[0], cal[-1], len(uni[cal[0]]), len(uni[cal[-1]])), file=sys.stderr)

    adds, drops = [], []
    for i, day in enumerate(cal):
        if day < EV_FROM:
            continue
        for s in uni[day] - uni[cal[i - 1]]:
            if i >= 40 and all(s not in uni[cal[j]] for j in range(i - 40, i)):
                adds.append((day, s, cal[i - 1]))
        if i + 1 < len(cal):
            for s in uni[day] - uni[cal[i + 1]]:
                rest = cal[i + 1:i + 21]
                if len(rest) == 20 and all(s not in uni[x] for x in rest):
                    drops.append((day, s, day))

    def isin(sym, day):
        return ir.bhav_isin(day).get(sym)

    renames = set()
    for ad, asym, _ in adds:
        for dd, dsym, _ in drops:
            if abs(cal.index(ad) - cal.index(dd)) <= 3:
                a, b = isin(asym, ad), isin(dsym, dd)
                if a and a == b:
                    renames.add(("F1", asym, ad))
                    renames.add(("F2", dsym, dd))
                    print("rename dropped: %s %s -> %s %s (%s)" % (dsym, dd, asym, ad, a), file=sys.stderr)

    print("index\tside\tsymbol\tannounce_date\tchange_close_date\tsource")
    n = {"F1": 0, "F2": 0}
    for test, rows in (("F1", adds), ("F2", drops)):
        for day, sym, ann in sorted(rows):
            if (test, sym, day) in renames:
                continue
            print("FO UNIVERSE\t%s\t%s\t%s\t%s\tNSE F&O bhavcopy %s" % (test, sym, ann, day, day.strftime("%Y%m%d")))
            n[test] += 1
    print("events (before eligibility): %s" % n, file=sys.stderr)
    by_day = {}
    for test, rows in (("F1", adds), ("F2", drops)):
        for day, sym, _ in rows:
            by_day.setdefault((test, day), []).append(sym)
    for (test, day), syms in sorted(by_day.items()):
        print("  %s %s: %d  %s" % (test, day, len(syms), " ".join(sorted(syms))), file=sys.stderr)


if __name__ == "__main__":
    main()
