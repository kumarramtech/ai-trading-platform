#!/usr/bin/env python3
"""
INDEX-CHANGE ANNOUNCEMENT-DAY study (rules fixed and committed 2026-11-21 before any of these prices were looked at;
autonomous mode; research only; the index rebalance paper book is NOT changed).

Idea      NSE Indices publishes its review (adds / drops) weeks before the change date, usually after the close.
          Traders who front-run the index funds may buy the adds and sell the drops on the FIRST session after the
          release. The passed rule B1 trades the change date; this tests the announcement side, which no study has.
Events    N1: research/index_events.tsv (NIFTY 50 / Next 50 / Midcap 150), one event per (review, stock), the
              largest index decides (as index_rebalance.py).
          N2: research/index_events_other.tsv with the O2 rules (one per (change date, stock); out if a main-3 event
              the same change date, if added and dropped the same date, or a Smallcap 250 event).
          Trade day T = the first trading day AFTER the release date (announce_date), T in Oct 2024 .. Sep 2026.
Trade     T: ADD long / DROP short, 09:20 close -> 15:25 close, minus NIFTY over the same minutes.
Cost      N1 0.13%; N2 0.18% (small caps).
Eligible  price >= Rs 100 and 20-day average traded value >= Rs 10 crore before T.
Pass      each test: ir.verdict (>= 40 trades; mean net > 0 with t >= 2; both halves of the reviews > 0; > 0 without
          the best review; beats the placebo one year earlier by >= the cost, gross) PLUS t >= 2.5 per trade AND
          t >= 2.5 on one value per release date.
Usage (server, outside market hours): python3 index_announce_study.py index_events.tsv index_events_other.tsv
"""
import datetime as dt
import os
import sys
import urllib.request
from collections import defaultdict

import index_rebalance as ir

FROM, TO = dt.date(2024, 10, 1), dt.date(2026, 9, 30)
T_MIN = 2.5
COST = {"N1": 0.13, "N2": 0.18}


def main():
    os.makedirs(ir.CACHE, exist_ok=True)
    with urllib.request.urlopen("http://localhost:9090/upstox/auth/token", timeout=15) as r:
        ir.TOK = r.read().decode().strip()
    main_rows = [l.rstrip("\n").split("\t") for l in open(sys.argv[1]) if l.strip()][1:]
    other_rows = [l.rstrip("\n").split("\t") for l in open(sys.argv[2]) if l.strip()][1:]
    events, skip = [], defaultdict(int)
    ev = {}
    for idx, side, sym, ann, close, src in main_rows:
        k = (close, sym)
        if k not in ev or ir.RANK[idx] < ir.RANK[ev[k][0]]:
            ev[k] = (idx, side, sym, ann)
    for (close, sym), (idx, side, _, ann) in ev.items():
        events.append(("N1", side, sym, dt.date.fromisoformat(ann)))
    main3 = {(l[4], l[2]) for l in main_rows}
    grp = defaultdict(list)
    for idx, side, sym, ann, close, src in other_rows:
        grp[(close, sym)].append((idx, side, ann))
    for (close, sym), lst in grp.items():
        if (close, sym) in main3:
            skip["N2: main-3 event same date"] += 1
            continue
        sides = {s for _, s, _ in lst}
        if len(sides) > 1:
            skip["N2: added and dropped same date"] += 1
            continue
        if any(i == "NIFTY SMALLCAP 250" for i, _, _ in lst):
            skip["N2: Smallcap 250"] += 1
            continue
        events.append(("N2", sides.pop(), sym, min(dt.date.fromisoformat(a) for _, _, a in lst)))
    master = ir.master_isin()
    nifty = ir.daily(ir.NIFTY)
    ndays = sorted(nifty)
    events = [e for e in events if (lambda t: t and FROM <= t <= TO)(ir.next_day(ndays, e[3]))]
    events.sort(key=lambda e: (e[3], e[2]))
    print("events %s" % {t: sum(1 for e in events if e[0] == t) for t in ("N1", "N2")}, file=sys.stderr, flush=True)

    tests = {t: ([], []) for t in ("N1", "N2")}
    for k, (test, side, sym, ann) in enumerate(events):
        isin = ir.bhav_isin(ann).get(sym) or master.get(sym)
        if not isin:
            skip["no ISIN"] += 1
            continue
        key = "NSE_EQ|" + isin
        d = ir.daily(key)
        days = sorted(x for x in d if x in nifty) if d else []
        t1 = ir.next_day(days, ann)
        if not t1 or days.index(t1) < 21:
            skip["no daily data"] += 1
            continue
        j = days.index(t1) - 1
        turn = sum(d[days[x]][0] * d[days[x]][1] for x in range(j - 19, j + 1)) / 20
        if d[days[j]][0] < ir.MIN_PRICE or turn < ir.MIN_TURN:
            skip["ineligible"] += 1
            continue
        sign = 1 if side == "ADD" else -1
        rel = ann.isoformat()
        for bucket, dd in ((tests[test][0], t1), (tests[test][1], ir.on_or_after(days, t1 - dt.timedelta(days=365)))):
            if not dd:
                continue
            s, n = ir.minutes(key, dd), ir.minutes(ir.NIFTY, dd)
            if s and n and s[5] and s[370] and n[5] and n[370]:
                bucket.append((rel, sign * ((s[370] / s[5] - 1) - (n[370] / n[5] - 1)) * 100))
            elif bucket is tests[test][0]:
                skip["no minute data"] += 1
        if k % 100 == 0:
            print("  %d/%d" % (k, len(events)), file=sys.stderr, flush=True)

    out = ["INDEX-CHANGE ANNOUNCEMENT-DAY STUDY (pre-registered 2026-11-21; t >= %.1f per trade and per release; "
           "research only)" % T_MIN, "events %d; left out: %s" % (len(events), dict(skip)), ""]
    res = {}
    for t, label in (("N1", "NIFTY 50 / Next 50 / Midcap 150"), ("N2", "other NSE indices")):
        trades, placebo = tests[t]
        block = []
        v = ir.verdict("%s %s: first session after the release, 09:20 -> 15:25, ADD long / DROP short" % (t, label),
                       trades, placebo, COST[t], block)
        n, mu, tt = ir.stats([g - COST[t] for _, g in trades])
        by = defaultdict(list)
        for r, g in trades:
            by[r].append(g - COST[t])
        rn, rmu, rt = ir.stats([sum(x) / len(x) for x in by.values()])
        block.append("  release-level: %d releases, mean %+.3f%%, t %.2f" % (rn, rmu, rt))
        if v == "PASS" and (tt < T_MIN or rt < T_MIN):
            v = "FAIL"
            block.append("  [fail] needs t >= %.1f per trade and per release (t %.2f / %.2f) -> FAIL" % (T_MIN, tt, rt))
        res[t] = v
        out += block + [""]
    out.append("VERDICT: " + ", ".join("%s %s" % kv for kv in res.items()))
    print("\n".join(out))


if __name__ == "__main__":
    main()
