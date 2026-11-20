#!/usr/bin/env python3
"""
Low-rollover expiry study, step 2: the test (rules approved by the user 2026-11-20 before any prices were looked
at; research only). Events: research/fo_rollover_events.tsv (built from NSE F&O bhavcopies, commit 09f175b).

R1    bottom 10% of F&O stocks by rollover on the day before the monthly expiry: SHORT on the expiry day,
      09:20 close -> 15:25 close, minus NIFTY over the same minutes; cost 0.13%.
CTRL  top 10% by rollover (information only, never a verdict): the same short, reported alongside.
Eligible price >= Rs 100 and 20-day average traded value >= Rs 10 crore before the day; same-day NSE index
         events (index_events.tsv) left out.
Pass  >= 40 trades; mean net > 0 with t >= 2.5; both halves > 0; > 0 without the best month; beats the placebo
      (the same stock and trade 10 trading days earlier) by >= the cost, gross.
Usage (server, after the close): python3 fo_rollover_study.py fo_rollover_events.tsv index_events.tsv
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

    tests = {t: ([], []) for t in ("R1", "CTRL")}
    skip = defaultdict(list)
    for idx, test, sym, sig, day, src, roll in rows:
        sig, day = dt.date.fromisoformat(sig), dt.date.fromisoformat(day)
        tag = "%s %s %s" % (test, sym, day)
        if (day.isoformat(), sym) in nse:
            skip["NSE index event same day"].append(tag)
            continue
        isin = ir.bhav_isin(day).get(sym) or ir.bhav_isin(sig).get(sym) or master.get(sym)
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
        g = intraday(key, day)
        if g is None:
            skip["no minute data"].append(tag)
            continue
        trades, placebo = tests[test]
        month = day.strftime("%Y-%m")
        trades.append((month, -g))
        pg = intraday(key, days[i - 10])
        if pg is not None:
            placebo.append((month, -pg))

    out = ["LOW-ROLLOVER EXPIRY STUDY (pre-registered 2026-11-20; t >= %.1f; research only)" % T_MIN,
           "events %d; left out: %s" % (len(rows), "; ".join("%s %d" % (k, len(v)) for k, v in skip.items()) or "none")]
    for k, v in skip.items():
        if k != "ineligible":
            out.append("  %s: %s" % (k, ", ".join(v[:40]) + (" ..." if len(v) > 40 else "")))
    out.append("")
    res = {}
    for t, label in (("R1", "bottom 10% rollover"), ("CTRL", "top 10% rollover (information only)")):
        trades, placebo = tests[t]
        block = []
        v = ir.verdict("%s %s: short 09:20 -> 15:25 on expiry day vs NIFTY" % (t, label), trades, placebo, ir.COST_B, block)
        n, mu, tt = ir.stats([g - ir.COST_B for _, g in trades])
        if v == "PASS" and tt < T_MIN:
            v = "FAIL"
            block.append("  [fail] t %.2f below the stricter %.1f -> FAIL" % (tt, T_MIN))
        elif v != "NOT ENOUGH DATA":
            block.append("  (t check here is %.1f: t %.2f %s)" % (T_MIN, tt, "ok" if tt >= T_MIN else "below"))
        res[t] = v if t == "R1" else "(info) " + v
        out += block + [""]
    out.append("VERDICT: " + ", ".join("%s %s" % kv for kv in res.items()))
    print("\n".join(out))


if __name__ == "__main__":
    main()
