#!/usr/bin/env python3
"""
DAY AFTER AN F&O REMOVAL study (rules fixed and committed 2026-11-20 before any result; autonomous mode, the user
asked to keep testing intraday ideas without asking; research only; the F&O exclusion paper book is NOT changed).
Events: the F2 rows of research/fo_inclusion_events.tsv (a stock's last futures day; F2 itself passed 2026-11-19:
removed stocks lag NIFTY by ~-1.98% gross that day, which looked like an expiry-day effect).

R1  the day AFTER the last futures day, BUY the removed stock (a rebound once the forced futures unwinding is over),
    09:20 close -> 15:25 close, minus NIFTY over the same minutes; cost 0.13%.
Eligible price >= Rs 100 and 20-day average traded value >= Rs 10 crore before the F2 day (as in F2); stocks with an
    NSE index event on the trade day are left out. Only this one test (the short is its mirror and is not tested).
Pass  >= 40 trades; mean net > 0 with t >= 2.5; both halves > 0; > 0 without the best month; beats the placebo (the
    same stock and trade 10 trading days earlier) by >= the cost, gross.
Usage (server, after the close): python3 fo_removal_nextday_study.py fo_inclusion_events.tsv index_events.tsv
"""
import datetime as dt
import os
import sys
import urllib.request
from collections import defaultdict

import index_rebalance as ir

T_MIN = 2.5


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

    tests = {"R1": ([], [])}
    skip = defaultdict(list)
    for idx, test, sym, ann, day, src in rows:
        if test != "F2":
            continue
        ann, day = dt.date.fromisoformat(ann), dt.date.fromisoformat(day)
        tag = "%s %s" % (sym, day)
        isin = ir.bhav_isin(day).get(sym) or ir.bhav_isin(ann).get(sym) or master.get(sym)
        if not isin:
            skip["no ISIN"].append(tag)
            continue
        key = "NSE_EQ|" + isin
        d = ir.daily(key)
        days = sorted(x for x in d if x in nifty)
        if day not in days or days.index(day) < 21:
            skip["no daily data"].append(tag)
            continue
        i = days.index(day)
        turn = sum(d[days[x]][0] * d[days[x]][1] for x in range(i - 20, i)) / 20
        if d[days[i - 1]][0] < ir.MIN_PRICE or turn < ir.MIN_TURN:
            skip["ineligible"].append(tag)
            continue
        if i + 1 >= len(days):
            skip["no next day yet"].append(tag)
            continue
        nxt = days[i + 1]
        if (nxt.isoformat(), sym) in nse:
            skip["NSE index event on the trade day"].append(tag)
            continue
        g = intraday(key, nxt)
        if g is None:
            skip["no minute data"].append(tag)
            continue
        sign = 1
        trades, placebo = tests["R1"]
        month = nxt.strftime("%Y-%m")
        trades.append((month, sign * g))
        pg = intraday(key, days[i + 1 - 10])
        if pg is not None:
            placebo.append((month, sign * pg))

    out = ["DAY AFTER F&O REMOVAL STUDY (pre-registered 2026-11-20; t >= %.1f; research only)" % T_MIN,
           "events %d; left out: %s" % (len(rows), "; ".join("%s %d" % (k, len(v)) for k, v in skip.items()) or "none")]
    for k, v in skip.items():
        out.append("  %s: %s" % (k, ", ".join(v[:40]) + (" ..." if len(v) > 40 else "")))
    out.append("")
    res = {}
    for t, label in (("R1", "removed: BUY the day after the last futures day"),):
        trades, placebo = tests[t]
        block = []
        v = ir.verdict("TEST %s %s, 09:20 -> 15:25 vs NIFTY" % (t, label), trades, placebo, ir.COST_B, block)
        n, mu, tt = ir.stats([g - ir.COST_B for _, g in trades])
        if v == "PASS" and tt < T_MIN:
            v = "FAIL"
            block.append("  [fail] t %.2f below the stricter %.1f -> FAIL" % (tt, T_MIN))
        elif v != "NOT ENOUGH DATA":
            block.append("  (t check here is %.1f: t %.2f %s)" % (T_MIN, tt, "ok" if tt >= T_MIN else "below"))
        res[t] = v
        out += block + [""]
    out.append("VERDICT: " + ", ".join("%s %s" % kv for kv in res.items()))
    print("\n".join(out))


if __name__ == "__main__":
    main()
