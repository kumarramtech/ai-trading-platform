#!/usr/bin/env python3
"""
IPO lock-in study, step 2: the test (rules approved by the user 2026-11-19 before any prices were looked at;
research only). Events: research/ipo_lockin_events.tsv (L1 anchor 30 days, L2 anchor 90 days, L3 pre-IPO
shareholders 6 months; event day = first trading day the shares are free).

Trade    on the event day SHORT 09:20 close -> cover 15:25 close, minus NIFTY over the same minutes; cost 0.13%.
Eligible price >= Rs 100 and average traded value >= Rs 10 crore over the 20 trading days before the event day
         (fewer when the stock has listed more recently; at least 10 days, else left out and counted).
Pass     each test separately: >= 40 trades; mean net > 0 with t >= 2.0; both halves > 0; > 0 without the best
         month; beats the placebo (the same stock and trade 10 trading days before the event day) by >= the cost,
         gross. A stock with an NSE index event (index_events.tsv) on the same day is left out.
Usage (server, after the close): python3 ipo_lockin_study.py ipo_lockin_events.tsv index_events.tsv
"""
import datetime as dt
import os
import sys
import urllib.request
from collections import defaultdict

import index_rebalance as ir


def intraday(key, day):
    s, n = ir.minutes(key, day), ir.minutes(ir.NIFTY, day)
    if s and n and s[5] and s[370] and n[5] and n[370]:
        return ((s[370] / s[5] - 1) - (n[370] / n[5] - 1)) * 100
    return None


def main():
    os.makedirs(ir.CACHE, exist_ok=True)
    with urllib.request.urlopen("http://localhost:9090/upstox/auth/token", timeout=15) as r:
        ir.TOK = r.read().decode().strip()
    rows = [l.rstrip("\n").split("\t") for l in open(sys.argv[1]) if l.strip()][1:]
    nse = {(l.split("\t")[4], l.split("\t")[2]) for l in list(open(sys.argv[2]))[1:] if l.strip()}
    master = ir.master_isin()
    nifty = ir.daily(ir.NIFTY)

    tests = {t: ([], []) for t in ("L1", "L2", "L3")}
    skip = defaultdict(list)
    for idx, test, sym, listed, day, src in rows:
        listed, day = dt.date.fromisoformat(listed), dt.date.fromisoformat(day)
        tag = "%s %s %s" % (test, sym, day)
        if (day.isoformat(), sym) in nse:
            skip["NSE index event same day"].append(tag)
            continue
        isin = ir.bhav_isin(listed).get(sym) or master.get(sym)
        if not isin:
            skip["no ISIN"].append(tag)
            continue
        key = "NSE_EQ|" + isin
        d = ir.daily(key)
        days = sorted(x for x in d if x in nifty)
        if day not in days:
            skip["no daily data on the day"].append(tag)
            continue
        i = days.index(day)
        back = days[max(0, i - 20):i]
        if len(back) < 10:
            skip["< 10 days of history"].append(tag)
            continue
        turn = sum(d[x][0] * d[x][1] for x in back) / len(back)
        if d[days[i - 1]][0] < ir.MIN_PRICE or turn < ir.MIN_TURN:
            skip["ineligible"].append(tag)
            continue
        g = intraday(key, day)
        if g is None:
            skip["no minute data"].append(tag)
            continue
        trades, placebo = tests[test]
        month = day.strftime("%Y-%m")
        trades.append((month, -g))
        if i >= 10:
            pg = intraday(key, days[i - 10])
            if pg is not None:
                placebo.append((month, -pg))

    out = ["IPO LOCK-IN STUDY (pre-registered 2026-11-19; research only)",
           "events %d; left out: %s" % (len(rows), "; ".join("%s %d" % (k, len(v)) for k, v in skip.items()) or "none")]
    for k, v in skip.items():
        if k != "ineligible":
            out.append("  %s: %s" % (k, ", ".join(v[:30]) + (" ..." if len(v) > 30 else "")))
    out.append("")
    res = {}
    for t, label in (("L1", "anchor 30 days"), ("L2", "anchor 90 days"), ("L3", "pre-IPO shareholders 6 months")):
        trades, placebo = tests[t]
        res[t] = ir.verdict("TEST %s %s: short 09:20 -> 15:25 vs NIFTY" % (t, label), trades, placebo, ir.COST_B, out)
        out.append("")
    out.append("VERDICT: " + ", ".join("%s %s" % kv for kv in res.items()))
    print("\n".join(out))


if __name__ == "__main__":
    main()
