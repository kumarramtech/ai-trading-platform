#!/usr/bin/env python3
"""
Split / bonus ex-date study, step 2: the test (rules approved by the user 2026-11-20 before any intraday prices
were looked at; detection fixes approved the same evening; research only). Events: research/split_bonus_events.tsv.

S1    BUY on the ex-date, 09:20 close -> 15:25 close, minus NIFTY over the same minutes; cost 0.13%.
Eligible previous close ON THE NEW BASIS (divided by the split/bonus ratio when the daily history is unadjusted)
      >= Rs 50 and 20-day average traded value >= Rs 10 crore before the day. Stocks with an NSE index event
      (index_events.tsv) or an F&O add/removal (fo_inclusion_events.tsv) the same day are left out.
Pass  >= 40 trades; mean net > 0 with t >= 2.5; both halves > 0; > 0 without the best month; beats the placebo (the
      same stock and trade 10 trading days earlier) by >= the cost, gross.
A split changes the ISIN: history before the ex-date comes from the old ISIN when the new one has none.
Usage (server, after the close): python3 split_bonus_study.py split_bonus_events.tsv index_events.tsv fo_inclusion_events.tsv
"""
import datetime as dt
import os
import sys
import urllib.request
from collections import defaultdict

import index_rebalance as ir

T_MIN = 2.5
MIN_PRICE = 50


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
    same_day = {(l.split("\t")[4], l.split("\t")[2]) for l in list(open(sys.argv[2]))[1:] if l.strip()}
    same_day |= {(l.split("\t")[4], l.split("\t")[2]) for l in list(open(sys.argv[3]))[1:] if l.strip()}
    nifty = ir.daily(ir.NIFTY)

    trades, placebo = [], []
    skip = defaultdict(list)
    for idx, test, sym, prev, day, src, ratio, isin_old, isin_new in rows:
        day, ratio = dt.date.fromisoformat(day), float(ratio)
        tag = "%s %s" % (sym, day)
        if (day.isoformat(), sym) in same_day:
            skip["index / F&O event same day"].append(tag)
            continue
        new_key, old_key = "NSE_EQ|" + isin_new, "NSE_EQ|" + isin_old
        d = dict(ir.daily(new_key))
        if isin_old != isin_new:
            for x, v in ir.daily(old_key).items():
                if x < day and x not in d:
                    d[x] = v
        days = sorted(x for x in d if x in nifty)
        if day not in days or days.index(day) < 21:
            skip["no daily data"].append(tag)
            continue
        i = days.index(day)
        pc, c0 = d[days[i - 1]][0], d[day][0]
        adj = pc / ratio if pc / c0 > 1.3 else pc  # unadjusted history: put the previous close on the new basis
        turn = sum(d[days[x]][0] * d[days[x]][1] for x in range(i - 20, i)) / 20
        if adj < MIN_PRICE or turn < ir.MIN_TURN:
            skip["ineligible"].append(tag)
            continue
        g = intraday(new_key, day)
        if g is None:
            skip["no minute data"].append(tag)
            continue
        month = day.strftime("%Y-%m")
        trades.append((month, g))
        pday = days[i - 10]
        pg = intraday(new_key, pday)
        if pg is None and isin_old != isin_new:
            pg = intraday(old_key, pday)
        if pg is not None:
            placebo.append((month, pg))

    out = ["SPLIT / BONUS EX-DATE STUDY (pre-registered 2026-11-20; t >= %.1f; research only)" % T_MIN,
           "events %d; left out: %s" % (len(rows), "; ".join("%s %d" % (k, len(v)) for k, v in skip.items()) or "none")]
    for k, v in skip.items():
        if k != "ineligible":
            out.append("  %s: %s" % (k, ", ".join(v[:40]) + (" ..." if len(v) > 40 else "")))
    out.append("")
    block = []
    v = ir.verdict("S1 buy on the ex-date, 09:20 -> 15:25 vs NIFTY", trades, placebo, ir.COST_B, block)
    n, mu, tt = ir.stats([g - ir.COST_B for _, g in trades])
    if v == "PASS" and tt < T_MIN:
        v = "FAIL"
        block.append("  [fail] t %.2f below the stricter %.1f -> FAIL" % (tt, T_MIN))
    elif v != "NOT ENOUGH DATA":
        block.append("  (t check here is %.1f: t %.2f %s)" % (T_MIN, tt, "ok" if tt >= T_MIN else "below"))
    out += block + ["", "VERDICT: S1 " + v]
    print("\n".join(out))


if __name__ == "__main__":
    main()
