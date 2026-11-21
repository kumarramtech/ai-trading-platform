#!/usr/bin/env python3
"""
INFORMATION ONLY breakdown (+ day-before / day-after daily check) of index_other_study.py after O2 PASSED (2026-11-21): same events, data and trades,
split by side, by index family, by review (review-level t), and by stock size; not part of the pass test.

INDEX REBALANCE on the OTHER NSE indices: out-of-sample check of the passed rule B1 (rules fixed and committed
2026-11-21 before any prices of these events were looked at; autonomous mode; research only; the index rebalance
paper book is NOT changed).

Events   research/index_events_other.tsv (index_events_other.py: the same press-release parse, every index of the
         scheduled reviews except NIFTY 50 / Next 50 / Midcap 150, sectoral and thematic included).
         One event per (change_close_date, stock). Left out: a stock that has a NIFTY 50 / Next 50 / Midcap 150
         event on the same change date (research/index_events.tsv: already in the 2026-11-18 study), and a stock
         added to one index and dropped from another on the same change date (no clear direction).
Rule B1  exactly as passed: on the change date ("close of" date), ADD long / DROP short, 09:20 close -> 15:25
         close, minus NIFTY over the same minutes. Change dates Oct 2024 .. Sep 2026 (minute data).
Cost     0.18% (0.13% + 0.05% extra slippage: many of these are small caps).
Eligible price >= Rs 100 and 20-day average traded value >= Rs 10 crore before the announcement (as B1).
Tests    O1 stocks with a NIFTY SMALLCAP 250 event (the largest remaining index with passive money);
         O2 all the other events (sector / thematic / strategy / broad-only indices).
Pass     each test: >= 40 trades; mean net > 0 with t >= 2.5; both halves of the reviews > 0; > 0 without the best
         review; beats the placebo (same stock and window one year earlier) by >= the cost, gross (ir.verdict).
Usage (server, after the close): python3 index_other_study.py index_events_other.tsv index_events.tsv
"""
import datetime as dt
import os
import sys
import urllib.request
from collections import defaultdict

import index_rebalance as ir

COST = 0.18
T_MIN = 2.5


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
        if (close, sym) in main3:
            skip["main-3 index event same date"] += 1
            continue
        sides = {s for _, s, _ in lst}
        if len(sides) > 1:
            skip["added and dropped same date"] += 1
            continue
        test = "O1" if any(i == "NIFTY SMALLCAP 250" for i, _, _ in lst) else "O2"
        ann = min(dt.date.fromisoformat(a) for _, _, a in lst)
        events.append((test, sides.pop(), sym, ann, dt.date.fromisoformat(close)))
    events = [e for e in events if ir.B_FROM <= e[4] <= ir.B_TO]
    events.sort(key=lambda e: (e[4], e[2]))
    print("events in the minute-data window: %s" % {t: sum(1 for e in events if e[0] == t) for t in ("O1", "O2")},
          file=sys.stderr, flush=True)
    master = ir.master_isin()
    nifty = ir.daily(ir.NIFTY)
    tests = {t: ([], []) for t in ("O1", "O2")}
    detail = []
    fam = {}
    for (close, sym), lst in grp.items():
        fam[(close, sym)] = sorted({i for i, _, _ in lst})
    for k, (test, side, sym, ann, close) in enumerate(events):
        isin = ir.bhav_isin(ann).get(sym) or master.get(sym)
        if not isin:
            skip["no ISIN"] += 1
            continue
        key = "NSE_EQ|" + isin
        d = ir.daily(key)
        days = sorted(x for x in d if x in nifty) if d else []
        t1, e = ir.next_day(days, ann), ir.on_or_after(days, close)
        if not t1 or not e or e < t1 or days.index(t1) - 1 < 20:
            skip["no daily data"] += 1
            continue
        j = days.index(t1) - 1
        turn = sum(d[days[x]][0] * d[days[x]][1] for x in range(j - 19, j + 1)) / 20
        if d[days[j]][0] < ir.MIN_PRICE or turn < ir.MIN_TURN:
            skip["ineligible"] += 1
            continue
        sign = 1 if side == "ADD" else -1
        for bucket, dd in ((tests[test][0], e), (tests[test][1], ir.on_or_after(days, e - dt.timedelta(days=365)))):
            if not dd:
                continue
            s, n = ir.minutes(key, dd), ir.minutes(ir.NIFTY, dd)
            if s and n and s[5] and s[370] and n[5] and n[370]:
                g = sign * ((s[370] / s[5] - 1) - (n[370] / n[5] - 1)) * 100
                bucket.append((close, g))
                if bucket is tests[test][0]:
                    ei = days.index(e)
                    def cc(a, b):
                        return sign * ((d[days[b]][0] / d[days[a]][0]) - (nifty[days[b]][0] / nifty[days[a]][0])) * 100
                    pre = cc(ei - 2, ei - 1) if ei >= 2 else None
                    post = cc(ei, ei + 1) if ei + 1 < len(days) else None
                    detail.append((test, side, sym, close, g, turn, d[days[j]][0], fam[(close.isoformat(), sym)], pre, post))
            elif bucket is tests[test][0]:
                skip["no minute data"] += 1
        if k % 100 == 0:
            print("  %d/%d" % (k, len(events)), file=sys.stderr, flush=True)

    import math
    def st(x):
        n = len(x)
        if n < 2:
            return n, 0, 0
        m = sum(x) / n
        sd = math.sqrt(sum((v - m) ** 2 for v in x) / (n - 1))
        return n, m, m / (sd / math.sqrt(n)) if sd else 0
    o2 = [x for x in detail if x[0] == "O2"]
    out = ["INFORMATION ONLY: O2 breakdown (net = gross - %.2f%%)" % COST]
    for side in ("ADD", "DROP"):
        n, m, t = st([x[4] - COST for x in o2 if x[1] == side])
        out.append("side %-4s: %4d trades, net %+.3f%%, t %.2f" % (side, n, m, t))
    rv = defaultdict(list)
    for x in o2:
        rv[x[3]].append(x[4] - COST)
    n, m, t = st([sum(v) / len(v) for v in rv.values()])
    out.append("review-level: %d reviews, mean %+.3f%%, t %.2f" % (n, m, t))
    for lo, hi, lab in ((10e7, 30e7, "turnover 10-30 cr"), (30e7, 100e7, "30-100 cr"), (100e7, 1e15, ">100 cr")):
        n, m, t = st([x[4] - COST for x in o2 if lo <= x[5] < hi])
        out.append("%-18s: %4d trades, net %+.3f%%, t %.2f" % (lab, n, m, t))
    by = defaultdict(list)
    for x in o2:
        for f in x[7]:
            by[f].append(x[4] - COST)
    out.append("by index (a stock in several indices counts in each):")
    for f in sorted(by, key=lambda f: -len(by[f]))[:30]:
        n, m, t = st(by[f])
        out.append("  %-45s %4d trades, net %+.3f%%, t %.2f" % (f, n, m, t))
    only_broad = [x[4] - COST for x in o2 if all(f in ("NIFTY 500", "NIFTY TOTAL MARKET", "NIFTY 200", "NIFTY 100", "NIFTY LARGEMIDCAP 250", "NIFTY MIDSMALLCAP 400", "NIFTY SMALLCAP 500", "NIFTY MIDCAP 100", "NIFTY SMALLCAP 100", "NIFTY MICROCAP 250", "NIFTY MIDCAP 50", "NIFTY SMALLCAP 50") for f in x[7])]
    n, m, t = st(only_broad)
    out.append("only broad size indices (no strategy/sector index): %d trades, net %+.3f%%, t %.2f" % (n, m, t))
    n, m, t = st([x[4] - COST for x in o2 if len(x[7]) == 1])
    out.append("stock in exactly one index: %d trades, net %+.3f%%, t %.2f" % (n, m, t))
    for lab, ix in (("day BEFORE (close T-2 -> T-1)", 8), ("day AFTER (close T -> T+1)", 9)):
        for side in ("ADD", "DROP"):
            n, m, t = st([x[ix] for x in o2 if x[1] == side and x[ix] is not None])
            out.append("%s %s, signed, gross: %d, %+.3f%%, t %.2f" % (lab, side, n, m, t))
    worst = sorted(o2, key=lambda x: x[4])[:5]
    out.append("worst trades: " + "; ".join("%s %s %s %+.2f%%" % (x[2], x[1], x[3], x[4]) for x in worst))
    best = sorted(o2, key=lambda x: -x[4])[:5]
    out.append("best trades: " + "; ".join("%s %s %s %+.2f%%" % (x[2], x[1], x[3], x[4]) for x in best))
    print(chr(10).join(out))


if __name__ == "__main__":
    main()
