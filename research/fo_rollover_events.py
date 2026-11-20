#!/usr/bin/env python3
"""
Low-rollover expiry study, step 1: event list (rules approved by the user 2026-11-20; NSE files only, no prices).

Expiries   every monthly stock-futures expiry Oct 2024 .. Sep 2026 (the nearest STF expiry seen in a mid-month
           NSE F&O UDiFF bhavcopy of that month).
Signal     the bhavcopy of the trading day before the expiry: rollover = open interest in the later expiries /
           total stock-futures open interest, per stock (stocks with total OI > 0 and a contract in the
           expiring month and in a later month).
Left out   (before ranking) F2 removals (stocks with no later contract), stocks in the NSE F&O ban on the
           expiry day (fo_secban_DDMMYYYY.csv). Index-rebalance same-day events and eligibility (price/turnover)
           are applied in the study script.
R1         bottom 10% by rollover (ceil(n / 10) stocks) -> short 09:20 -> 15:25 on the expiry day vs NIFTY.
CTRL       top 10% by rollover (information only).
Usage      python3 fo_rollover_events.py > fo_rollover_events.tsv   (summary on stderr)
"""
import csv
import datetime as dt
import io
import math
import sys
import time
import urllib.error
import urllib.request
import zipfile

UA = "research-client/1.0"
FIRST, LAST = (2024, 10), (2026, 9)


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


def fo(day):
    """{symbol: {expiry: OI}} of stock futures, or None (no file that day)."""
    data = http("https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_%s_F_0000.csv.zip" % day.strftime("%Y%m%d"))
    time.sleep(0.4)
    if not data:
        return None
    z = zipfile.ZipFile(io.BytesIO(data))
    rd = csv.reader(io.StringIO(z.read(z.namelist()[0]).decode("utf-8", "replace")))
    head = [h.strip() for h in next(rd)]
    ti, si, xi, oi = (head.index(c) for c in ("FinInstrmTp", "TckrSymb", "XpryDt", "OpnIntrst"))
    out = {}
    for r in rd:
        if len(r) > max(ti, si, xi, oi) and r[ti].strip() == "STF":
            x = dt.date.fromisoformat(r[xi].strip())
            out.setdefault(r[si].strip(), {})[x] = out.get(r[si].strip(), {}).get(x, 0) + float(r[oi] or 0)
    return out


def ban(day):
    data = http("https://nsearchives.nseindia.com/archives/fo/sec_ban/fo_secban_%s.csv" % day.strftime("%d%m%Y"))
    time.sleep(0.4)
    if data is None:
        return None
    lines = data.decode("utf-8", "replace").replace("NIL", "").splitlines()[1:]
    return {l.split(",")[-1].strip() for l in lines if l.strip() and "," in l}


def main():
    print("index\tside\tsymbol\tannounce_date\tchange_close_date\tsource\trollover")
    y, m = FIRST
    summary = []
    while (y, m) <= LAST:
        mid, f = None, None
        for dd in range(8, 20):
            d = dt.date(y, m, dd)
            if d.weekday() < 5:
                f = fo(d)
                if f:
                    mid = d
                    break
        exp = min(x for v in f.values() for x in v if x >= mid and (x.year, x.month) == (y, m))
        sig, fs = exp - dt.timedelta(days=1), None
        while fs is None:
            if sig.weekday() < 5:
                fs = fo(sig)
            if fs is None:
                sig -= dt.timedelta(days=1)
        banned = ban(exp)
        if banned is None:
            print("WARNING no ban file for %s" % exp, file=sys.stderr)
            banned = set()
        roll, f2, nb = {}, 0, 0
        for s, v in fs.items():
            near = v.get(exp, 0)
            later = sum(o for x, o in v.items() if x > exp)
            if exp in v and not any(x > exp for x in v):
                f2 += 1
                continue
            if s in banned:
                nb += 1
                continue
            if exp in v and near + later > 0:
                roll[s] = later / (near + later)
        ranked = sorted(roll, key=lambda s: (roll[s], s))
        k = math.ceil(len(ranked) / 10)
        for side, syms in (("R1", ranked[:k]), ("CTRL", ranked[-k:])):
            for s in syms:
                print("FO ROLLOVER\t%s\t%s\t%s\t%s\tNSE F&O bhavcopy %s\t%.4f" % (
                    side, s, sig, exp, sig.strftime("%Y%m%d"), roll[s]))
        summary.append("  expiry %s (signal %s): ranked %d, F2 out %d, ban out %d, R1 %d (rollover %.2f..%.2f), "
                       "CTRL %d (%.2f..%.2f), median %.2f" % (
                           exp, sig, len(ranked), f2, nb, k, roll[ranked[0]], roll[ranked[k - 1]], k,
                           roll[ranked[-k]], roll[ranked[-1]], roll[ranked[len(ranked) // 2]]))
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    print("\n".join(summary), file=sys.stderr)


if __name__ == "__main__":
    main()
