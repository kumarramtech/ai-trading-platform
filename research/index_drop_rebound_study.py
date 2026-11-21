#!/usr/bin/env python3
"""
INDEX-DROP REBOUND study (rules fixed and committed 2026-11-21 before any of these prices were looked at; autonomous
mode; research only; the index rebalance paper book is NOT changed).

Where it comes from: in the O2 breakdown (index_other_breakdown.py, Oct 2024 - Sep 2026, information only) stocks
DROPPED from the other NSE indices rose +0.70% vs NIFTY from the change-date close to the next close (t 9.8). That
was seen, not tested, so it is tested here on data never used: change dates 2018-01-01 .. 2024-09-30.

Events   research/index_events_other.tsv with the O2 rules: one event per (change date, stock); out if the stock
         has a NIFTY 50 / Next 50 / Midcap 150 event that date (index_events.tsv) or is added and dropped the same
         date; Smallcap 250 events out (O1). DROP events only for the test.
R1       BUY each dropped stock at the CLOSE of the change date, SELL at the close of the next trading day; return
         minus NIFTY over the same closes (daily data). Cost 0.33% a round trip (delivery: STT both sides).
         A one-day close move above 25% inside the window is a data error (split/bonus) and is left out.
Eligible price >= Rs 100 and 20-day average traded value >= Rs 10 crore before the announcement.
Pass     >= 40 trades; ir.verdict (mean net > 0 with t >= 2, both halves of the reviews > 0, > 0 without the best
         review, beats the placebo one year earlier by >= the cost, gross); PLUS t >= 2.5 per trade AND t >= 2.5 on
         one value per review date (trades on one date are correlated).
Information only: the same window for ADDED stocks (signed as a long), not part of the pass.
Usage (server, any time outside market hours): python3 index_drop_rebound_study.py index_events_other.tsv index_events.tsv
"""
import datetime as dt
import os
import sys
import urllib.request
from collections import defaultdict

import index_rebalance as ir

FROM, TO = dt.date(2018, 1, 1), dt.date(2024, 9, 30)
COST = 0.33
T_MIN = 2.5
JUMP = 25.0


def main():
    os.makedirs(ir.CACHE, exist_ok=True)
    with urllib.request.urlopen("http://localhost:9090/upstox/auth/token", timeout=15) as r:
        ir.TOK = r.read().decode().strip()
    rows = [l.rstrip("\n").split("\t") for l in open(sys.argv[1]) if l.strip()][1:]
    main3 = {(l.split("\t")[4], l.split("\t")[2]) for l in list(open(sys.argv[2]))[1:] if l.strip()}
    grp = defaultdict(list)
    for idx, side, sym, ann, close, src in rows:
        grp[(close, sym)].append((idx, side, ann))
    events, skip = [], defaultdict(int)
    for (close, sym), lst in grp.items():
        c = dt.date.fromisoformat(close)
        if not FROM <= c <= TO:
            continue
        if (close, sym) in main3:
            skip["main-3 index event same date"] += 1
            continue
        sides = {s for _, s, _ in lst}
        if len(sides) > 1:
            skip["added and dropped same date"] += 1
            continue
        if any(i == "NIFTY SMALLCAP 250" for i, _, _ in lst):
            skip["Smallcap 250 (O1)"] += 1
            continue
        events.append((sides.pop(), sym, min(dt.date.fromisoformat(a) for _, _, a in lst), c))
    events.sort(key=lambda e: (e[3], e[1]))
    print("events %s" % {s: sum(1 for e in events if e[0] == s) for s in ("DROP", "ADD")}, file=sys.stderr, flush=True)
    master = ir.master_isin()
    nifty = ir.daily(ir.NIFTY)
    res = {"DROP": ([], []), "ADD": ([], [])}
    for k, (side, sym, ann, close) in enumerate(events):
        isin = ir.bhav_isin(ann).get(sym) or master.get(sym)
        if not isin:
            skip["no ISIN"] += 1
            continue
        d = ir.daily("NSE_EQ|" + isin)
        days = sorted(x for x in d if x in nifty) if d else []
        t1, e = ir.next_day(days, ann), ir.on_or_after(days, close)
        if not t1 or not e or e < t1 or days.index(t1) - 1 < 20 or days.index(e) + 1 >= len(days):
            skip["no daily data"] += 1
            continue
        j = days.index(t1) - 1
        turn = sum(d[days[x]][0] * d[days[x]][1] for x in range(j - 19, j + 1)) / 20
        if d[days[j]][0] < ir.MIN_PRICE or turn < ir.MIN_TURN:
            skip["ineligible"] += 1
            continue

        def win(a):
            i = days.index(a)
            if i + 1 >= len(days):
                return None
            p, q = days[i], days[i + 1]
            if abs(d[q][0] / d[p][0] - 1) * 100 > JUMP:
                return None
            return ((d[q][0] / d[p][0]) - (nifty[q][0] / nifty[p][0])) * 100

        g = win(e)
        if g is None:
            skip["25% jump"] += 1
            continue
        res[side][0].append((close, g))
        pa = ir.on_or_after(days, e - dt.timedelta(days=365))
        if pa:
            pg = win(pa)
            if pg is not None:
                res[side][1].append((close, pg))
        if k % 200 == 0:
            print("  %d/%d" % (k, len(events)), file=sys.stderr, flush=True)

    out = ["INDEX-DROP REBOUND STUDY (pre-registered 2026-11-21; change dates %s .. %s; cost %.2f%%; research only)"
           % (FROM, TO, COST), "events %d; left out: %s" % (len(events), dict(skip)), ""]
    trades, placebo = res["DROP"]
    v = ir.verdict("R1 buy DROPPED stocks at the change-date close, sell next close, vs NIFTY", trades, placebo, COST, out)
    n, mu, tt = ir.stats([g - COST for _, g in trades])
    by = defaultdict(list)
    for r, g in trades:
        by[r].append(g - COST)
    rn, rmu, rt = ir.stats([sum(x) / len(x) for x in by.values()])
    out.append("  review-level: %d reviews, mean %+.3f%%, t %.2f" % (rn, rmu, rt))
    if v == "PASS" and (tt < T_MIN or rt < T_MIN):
        v = "FAIL"
        out.append("  [fail] needs t >= %.1f per trade and per review (t %.2f / %.2f) -> FAIL" % (T_MIN, tt, rt))
    out.append("")
    an, amu, at = ir.stats([g for _, g in res["ADD"][0]])
    out.append("information only: ADDED stocks, same window, gross: %d trades, %+.3f%%, t %.2f" % (an, amu, at))
    out += ["", "VERDICT: R1 " + v]
    print("\n".join(out))


if __name__ == "__main__":
    main()
