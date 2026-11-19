#!/usr/bin/env python3
"""
F&O ban list study (rules approved by the user 2026-11-19 before any prices were looked at; research only).

Events   research/fo_ban_events.tsv, built from NSE's daily official lists (archives/fo/sec_ban/fo_secban_DDMMYYYY.csv)
         for 2024-10-01 .. 2026-11-18. ENTRY = first day on the list after a day off it; EXIT = first day off it
         after a day on it. announce_date = the previous trading day (the list is published that evening).
         A stock with an NSE index event (research/index_events.tsv) on the same day is left out. An event whose
         previous trading day (NIFTY calendar) is not its announce_date (a missing list file) is left out and counted.
Trades   intraday 09:20 close -> 15:25 close, minus NIFTY over the same minutes, cost 0.13%; price >= Rs 100 and
         20-day average traded value >= Rs 10 crore on the day before.
         C1 entry day: AGAINST the 5-day move (close 6 trading days before -> close of the day before entry).
         C2 exit day:  AGAINST the ban-period move (close of the day before entry -> close of the day before exit);
                       skipped when the spell's entry is before the data (already banned on 2024-10-01).
Pass     each test separately: >= 40 trades; mean net > 0 with t >= 2.0; both halves > 0; > 0 without the best
         month; beats the placebo (same stock, same direction, first trading day on/after date - 364, the same
         weekday) by >= the cost, gross.
Info     unsigned stock-minus-NIFTY mean for each test, and each test's halves (SEBI changed the OI limit
         measurement in 2025).
Usage (server, after the close): python3 fo_ban_study.py fo_ban_events.tsv index_events.tsv
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
    ndays = sorted(nifty)

    last_entry = {}            # symbol -> entry date of the current spell
    tests = {"C1": ([], [], []), "C2": ([], [], [])}   # trades, placebo, unsigned
    skip = defaultdict(list)
    for idx, side, sym, ann, day, src in sorted(rows, key=lambda r: (r[4], r[1] != "EXIT")):
        ann, day = dt.date.fromisoformat(ann), dt.date.fromisoformat(day)
        entry_of_spell = last_entry.get(sym)
        if side == "ENTRY":
            last_entry[sym] = day
        else:
            last_entry.pop(sym, None)
        if day not in nifty or ndays[ndays.index(day) - 1] != ann:
            skip["calendar gap"].append("%s %s" % (sym, day))
            continue
        if (day.isoformat(), sym) in nse:
            skip["NSE index event same day"].append("%s %s" % (sym, day))
            continue
        if side == "EXIT" and not entry_of_spell:
            skip["exit without entry in data"].append("%s %s" % (sym, day))
            continue
        isin = ir.bhav_isin(ann).get(sym) or master.get(sym)
        if not isin:
            skip["no ISIN"].append("%s %s" % (sym, day))
            continue
        key = "NSE_EQ|" + isin
        d = ir.daily(key)
        days = sorted(x for x in d if x in nifty)
        if day not in days or days.index(day) < 21:
            skip["no daily data"].append("%s %s" % (sym, day))
            continue
        i = days.index(day)
        turn = sum(d[days[x]][0] * d[days[x]][1] for x in range(i - 20, i)) / 20
        if d[days[i - 1]][0] < ir.MIN_PRICE or turn < ir.MIN_TURN:
            skip["ineligible"].append("%s %s" % (sym, day))
            continue
        if side == "ENTRY":
            move = d[days[i - 1]][0] / d[days[i - 6]][0] - 1
        else:
            if entry_of_spell not in days or days.index(entry_of_spell) < 1:
                skip["no daily data"].append("%s %s" % (sym, day))
                continue
            move = d[days[i - 1]][0] / d[days[days.index(entry_of_spell) - 1]][0] - 1
        if move == 0:
            skip["zero prior move"].append("%s %s" % (sym, day))
            continue
        sign = -1 if move > 0 else 1
        g = intraday(key, day)
        if g is None:
            skip["no minute data"].append("%s %s" % (sym, day))
            continue
        trades, placebo, unsigned = tests["C1" if side == "ENTRY" else "C2"]
        month = day.strftime("%Y-%m")
        trades.append((month, sign * g))
        unsigned.append((day, g))
        pd_ = ir.on_or_after(days, day - dt.timedelta(days=364))
        if pd_ and pd_ < day - dt.timedelta(days=300):
            pg = intraday(key, pd_)
            if pg is not None:
                placebo.append((month, sign * pg))

    out = ["F&O BAN LIST STUDY (pre-registered 2026-11-19; research only)",
           "events %d; left out: %s" % (len(rows), "; ".join("%s %d" % (k, len(v)) for k, v in skip.items()) or "none")]
    for k, v in skip.items():
        if k not in ("exit without entry in data",):
            out.append("  %s: %s" % (k, ", ".join(v[:40]) + (" ..." if len(v) > 40 else "")))
    out.append("")
    res = {}
    for name, label in (("C1", "entry day, against the 5-day move"), ("C2", "exit day, against the ban-period move")):
        trades, placebo, unsigned = tests[name]
        res[name] = ir.verdict("TEST %s %s, 09:20 -> 15:25" % (name, label), trades, placebo, ir.COST_B, out)
        un = [g for _, g in unsigned]
        out.append("  (info) unsigned stock minus NIFTY: n %d, mean %+.3f%%, t %.2f" % ir.stats(un))
        if unsigned:
            mid = sorted(x for x, _ in unsigned)[len(unsigned) // 2]
            for lab, part in (("first half", [g for (m, g), (x, _) in zip(trades, unsigned) if x < mid]),
                              ("second half", [g for (m, g), (x, _) in zip(trades, unsigned) if x >= mid])):
                n, mu, t = ir.stats([g - ir.COST_B for g in part])
                out.append("  (info) %s (split %s): n %d, net %+.3f%%, t %.2f" % (lab, mid, n, mu, t))
        out.append("")
    out.append("VERDICT: " + ", ".join("%s %s" % kv for kv in res.items()))
    print("\n".join(out))


if __name__ == "__main__":
    main()
