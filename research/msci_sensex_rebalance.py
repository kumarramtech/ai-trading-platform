#!/usr/bin/env python3
"""
MSCI / Sensex rebalance test: out-of-sample check of index_rebalance.py test B1
(rules approved by the user 2026-11-18 before any prices were looked at; research only).

Events   research/msci_sensex_events.tsv, built before any prices:
         MSCI India Index (standard) quarterly review additions/deletions from MSCI's official
         STPublicList PDFs, change date = the "as of the close of" date;
         Sensex June/December reconstitutions (financial-news reports of the Asia Index/BSE release, marked
         in the file), effective at the open, so change date = the trading day before.
         A stock with an NSE index event (research/index_events.tsv) on the same change date is left out.
Trade    identical to B1: change date, ADD long / DROP short, 09:20 close -> 15:25 close, minus NIFTY over the
         same minutes; price >= Rs 100 and 20-day average traded value >= Rs 10 crore; cost 0.13%.
Pass     judged on MSCI only, same checks as B1 (>= 40 trades; net > 0 with t >= 2; both halves > 0; > 0
         without the best review; beats the one-year-earlier placebo by >= the cost, gross).
         Sensex and MSCI+Sensex combined: report only. All numbers are also printed when a group has
         < 40 trades (information only; the verdict then stays NOT ENOUGH DATA).
Usage (server, after the close): python3 msci_sensex_rebalance.py msci_sensex_events.tsv index_events.tsv
"""
import datetime as dt
import os
import sys
import urllib.request
from collections import defaultdict

import index_rebalance as ir


def report(name, trades, placebo, out):
    v = ir.verdict(name, trades, placebo, ir.COST_B, out)
    if v == "NOT ENOUGH DATA" and trades:
        by = defaultdict(list)
        for r, g in trades:
            by[r].append(g - ir.COST_B)
        out.append("  (information only, below the 40-trade minimum)")
        for r in sorted(by):
            out.append("    review %s: %3d trades, net %+.3f%%" % (r, len(by[r]), sum(by[r]) / len(by[r])))
        pn, pmu, _ = ir.stats([g for _, g in placebo])
        out.append("    placebo: %d trades, gross %+.3f%%" % (pn, pmu))
    return v


def main():
    os.makedirs(ir.CACHE, exist_ok=True)
    with urllib.request.urlopen("http://localhost:9090/upstox/auth/token", timeout=15) as r:
        ir.TOK = r.read().decode().strip()
    rows = [l.rstrip("\n").split("\t") for l in open(sys.argv[1]) if l.strip()][1:]
    nse = {(l.split("\t")[4], l.split("\t")[2]) for l in list(open(sys.argv[2]))[1:] if l.strip()}
    master = ir.master_isin()
    nifty = ir.daily(ir.NIFTY)
    ndays = sorted(nifty)

    trades, placebo = defaultdict(list), defaultdict(list)
    lines, overlap, miss, inelig = [], [], [], []
    for idx, side, sym, ann, close, src in rows:
        group = "MSCI" if idx.startswith("MSCI") else "SENSEX"
        ann, close = dt.date.fromisoformat(ann), dt.date.fromisoformat(close)
        if (close.isoformat(), sym) in nse:
            overlap.append("%s %s" % (sym, close))
            continue
        isin = ir.bhav_isin(ann).get(sym) or master.get(sym)
        if not isin:
            miss.append("%s(%s, no ISIN)" % (sym, close))
            continue
        key = "NSE_EQ|" + isin
        d = ir.daily(key)
        days = sorted(x for x in d if x in nifty)
        e = ir.on_or_after(days, close)
        t1 = ir.next_day(days, ann)
        if not e or e != close or not t1:
            miss.append("%s(%s, no daily data)" % (sym, close))
            continue
        j = days.index(t1) - 1
        if j < 20:
            miss.append("%s(%s, < 20 days listed)" % (sym, close))
            continue
        turn = sum(d[days[x]][0] * d[days[x]][1] for x in range(j - 19, j + 1)) / 20
        price = d[days[j]][0]
        if price < ir.MIN_PRICE or turn < ir.MIN_TURN:
            inelig.append("%s(%s, Rs %.0f, Rs %.1f cr)" % (sym, close, price, turn / 1e7))
            continue
        sign = 1 if side == "ADD" else -1
        got = None
        for bucket, dd in ((trades, e), (placebo, ir.on_or_after(days, e - dt.timedelta(days=365)))):
            if not dd:
                continue
            s, n = ir.minutes(key, dd), ir.minutes(ir.NIFTY, dd)
            if s and n and s[5] and s[370] and n[5] and n[370]:
                g = sign * ((s[370] / s[5] - 1) - (n[370] / n[5] - 1)) * 100
                bucket[group].append((close, g))
                if bucket is trades:
                    got = g
        if got is None:
            miss.append("%s(%s, no minute data)" % (sym, close))
        else:
            lines.append("  %-6s %-4s %-11s %s  gross %+6.2f%%  net %+6.2f%%" % (group, side, sym, close, got, got - ir.COST_B))

    out = ["MSCI / SENSEX REBALANCE TEST (out-of-sample check of B1; pre-registered 2026-11-18; research only)",
           "events %d; same-day NSE overlap left out: %s; ineligible: %s; missing: %s" % (
               len(rows), ", ".join(overlap) or "none", ", ".join(inelig) or "none", ", ".join(miss) or "none"),
           "", "trades:"] + lines + [""]
    v = report("MSCI   (judged)   change date 09:20 -> 15:25, ADD long / DROP short", trades["MSCI"], placebo["MSCI"], out)
    out.append("")
    report("SENSEX (report only)", trades["SENSEX"], placebo["SENSEX"], out)
    out.append("")
    report("MSCI + SENSEX (report only)", trades["MSCI"] + trades["SENSEX"], placebo["MSCI"] + placebo["SENSEX"], out)
    out.append("")
    out.append("VERDICT (MSCI, pre-registered): " + v)
    print("\n".join(out))


if __name__ == "__main__":
    main()
