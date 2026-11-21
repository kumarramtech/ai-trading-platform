#!/usr/bin/env python3
"""
VERIFICATION of the O2 pass (index_other_study.py, result research/index_other_result.txt): the same events and the
same B1 trade (change date 09:20 -> 15:25, ADD long / DROP short, minus NIFTY, cost 0.18%). No rule changes; this
only looks for errors and for where the profit comes from. Survival rules fixed 2026-11-21 BEFORE running:

  V1 day-clustered: the 8 change dates as 8 equal-weight observations (mean net per day), t >= 2.5
  V2 both sides: ADD-long net > 0 and DROP-short net > 0, each with t >= 2.0 (trade level)
  V3 no single index carries it: leave out every event of the index group with the largest total profit, the rest
     still has net > 0 with day-clustered t >= 2.0
  V4 not outliers: 5% trimmed mean net > 0, and the median gross > 0
  V5 change-day specific: near placebo (same stock, 5 trading days before the change date, same window) gross is
     below half of the change-day gross, and the day-before placebo is reported (pre-positioning)
  V6 liquid names: events with 20-day traded value >= Rs 50 crore have net > 0 with t >= 2.0
  V7 data: for every trade the 15:25 minute close is within 3% of the daily close of that day in >= 95% of trades;
     12 random trades are printed with their release lines for a hand check of side and date

  SURVIVES only if V1-V7 all hold. Otherwise O2 stays a research result and is NOT added to the paper book.
Usage (server): python3 index_other_verify.py index_events_other.tsv index_events.tsv
"""
import datetime as dt
import os
import random
import sys
import urllib.request
from collections import defaultdict

import index_rebalance as ir

COST = 0.18


def day_t(rows):
    """rows: [(date, net)] -> (days, mean of day means, t over days)"""
    by = defaultdict(list)
    for d, x in rows:
        by[d].append(x)
    return ir.stats([sum(v) / len(v) for v in by.values()])


def fmt(name, xs):
    n, mu, t = ir.stats(xs)
    return "%-44s n %5d  mean %+7.3f%%  t %+6.2f" % (name, n, mu, t)


def main():
    os.makedirs(ir.CACHE, exist_ok=True)
    with urllib.request.urlopen("http://localhost:9090/upstox/auth/token", timeout=15) as r:
        ir.TOK = r.read().decode().strip()
    rows = [l.rstrip("\n").split("\t") for l in open(sys.argv[1]) if l.strip()][1:]
    main3 = {(l.split("\t")[4], l.split("\t")[2]) for l in list(open(sys.argv[2]))[1:] if l.strip()}
    grp = defaultdict(list)
    for idx, side, sym, ann, close, src in rows:
        grp[(close, sym)].append((idx, side, ann, src))
    events = []
    for (close, sym), lst in grp.items():
        if (close, sym) in main3 or len({s for _, s, _, _ in lst}) > 1:
            continue
        if any(i == "NIFTY SMALLCAP 250" for i, _, _, _ in lst):
            continue  # O1, not part of the O2 pass
        ann = min(dt.date.fromisoformat(a) for _, _, a, _ in lst)
        events.append((lst[0][1], sym, ann, dt.date.fromisoformat(close), sorted({i for i, _, _, _ in lst}),
                       sorted({s for _, _, _, s in lst})))
    events = [e for e in events if ir.B_FROM <= e[3] <= ir.B_TO]
    events.sort(key=lambda e: (e[3], e[1]))
    master = ir.master_isin()
    nifty = ir.daily(ir.NIFTY)

    trades = []  # dict per trade
    near, before = [], []
    for k, (side, sym, ann, close, idxs, srcs) in enumerate(events):
        isin = ir.bhav_isin(ann).get(sym) or master.get(sym)
        if not isin:
            continue
        key = "NSE_EQ|" + isin
        d = ir.daily(key)
        days = sorted(x for x in d if x in nifty) if d else []
        t1, e = ir.next_day(days, ann), ir.on_or_after(days, close)
        if not t1 or not e or e < t1 or days.index(t1) - 1 < 20:
            continue
        j = days.index(t1) - 1
        turn = sum(d[days[x]][0] * d[days[x]][1] for x in range(j - 19, j + 1)) / 20
        if d[days[j]][0] < ir.MIN_PRICE or turn < ir.MIN_TURN:
            continue
        sign = 1 if side == "ADD" else -1

        def window(day):
            s, n = ir.minutes(key, day), ir.minutes(ir.NIFTY, day)
            if s and n and s[5] and s[370] and n[5] and n[370]:
                return s, sign * ((s[370] / s[5] - 1) - (n[370] / n[5] - 1)) * 100
            return None, None

        s, g = window(e)
        if g is None:
            continue
        trades.append(dict(date=close, sym=sym, side=side, idxs=idxs, srcs=srcs, gross=g, net=g - COST,
                           turn=turn, p920=s[5], p1525=s[370], dclose=d[e][0], day=e))
        ie = days.index(e)
        for bucket, back in ((near, 5), (before, 1)):
            if ie - back >= 0:
                _, gb = window(days[ie - back])
                if gb is not None:
                    bucket.append(gb)
        if k % 200 == 0:
            print("  %d/%d" % (k, len(events)), file=sys.stderr, flush=True)

    out = ["O2 VERIFICATION (rules fixed 2026-11-21 before running; cost %.2f%%)" % COST, ""]
    nets = [t["net"] for t in trades]
    out.append(fmt("all O2 trades (net)", nets))
    checks = {}

    # V1
    n, mu, tt = day_t([(t["date"], t["net"]) for t in trades])
    by = defaultdict(list)
    for t in trades:
        by[t["date"]].append(t["net"])
    out.append("V1 day-clustered: %d days, mean of day means %+.3f%%, t %.2f" % (n, mu, tt))
    for d in sorted(by):
        out.append("     %s  %4d trades  day mean net %+.3f%%" % (d, len(by[d]), sum(by[d]) / len(by[d])))
    checks["V1"] = mu > 0 and tt >= 2.5

    # V2
    a = [t["net"] for t in trades if t["side"] == "ADD"]
    r = [t["net"] for t in trades if t["side"] == "DROP"]
    out.append("V2 " + fmt("ADD long", a))
    out.append("   " + fmt("DROP short", r))
    na, ma, ta = ir.stats(a)
    nr, mr, tr = ir.stats(r)
    checks["V2"] = ma > 0 and ta >= 2 and mr > 0 and tr >= 2

    # V3: per index (an event counts in each index it belongs to)
    per = defaultdict(list)
    for t in trades:
        for i in t["idxs"]:
            per[i].append(t["net"])
    ranked = sorted(per.items(), key=lambda kv: -sum(kv[1]))
    out.append("V3 per index (event counted in each of its indices), top 15 by total net:")
    for i, xs in ranked[:15]:
        out.append("     " + fmt(i, xs) + "  total %+8.1f" % sum(xs))
    top = ranked[0][0]
    rest = [t for t in trades if top not in t["idxs"]]
    n3, mu3, t3 = day_t([(t["date"], t["net"]) for t in rest])
    out.append("   without %s: %d trades, net %+.3f%%, day-clustered t %.2f" % (top, len(rest),
               sum(t["net"] for t in rest) / max(1, len(rest)), t3))
    checks["V3"] = len(rest) > 0 and sum(t["net"] for t in rest) > 0 and t3 >= 2

    # V4
    s = sorted(nets)
    cut = int(len(s) * 0.05)
    trimmed = s[cut:len(s) - cut]
    gs = sorted(t["gross"] for t in trades)
    med = gs[len(gs) // 2]
    top20 = sum(sorted((t["gross"] for t in trades), reverse=True)[:20])
    out.append("V4 trimmed 5%% mean net %+.3f%% | median gross %+.3f%% | top 20 trades = %.0f%% of total gross"
               % (sum(trimmed) / len(trimmed), med, top20 / sum(gs) * 100))
    checks["V4"] = sum(trimmed) / len(trimmed) > 0 and med > 0

    # V5
    g_all = sum(gs) / len(gs)
    nn, nm, nt = ir.stats(near)
    bn, bm, bt = ir.stats(before)
    out.append("V5 change day gross %+.3f%% | 5 days before %+.3f%% (n %d, t %.2f) | day before %+.3f%% (n %d, t %.2f)"
               % (g_all, nm, nn, nt, bm, bn, bt))
    checks["V5"] = nm < g_all / 2

    # V6
    out.append("V6 by 20-day traded value:")
    for lo, hi, lab in ((10e7, 25e7, "Rs 10-25 cr"), (25e7, 50e7, "Rs 25-50 cr"), (50e7, 200e7, "Rs 50-200 cr"),
                        (200e7, 1e15, "Rs 200 cr +")):
        out.append("     " + fmt(lab, [t["net"] for t in trades if lo <= t["turn"] < hi]))
    liq = [t["net"] for t in trades if t["turn"] >= 50e7]
    nl, ml, tl = ir.stats(liq)
    checks["V6"] = ml > 0 and tl >= 2

    # V7
    ok = sum(1 for t in trades if t["dclose"] and abs(t["p1525"] / t["dclose"] - 1) <= 0.03)
    out.append("V7 15:25 minute close within 3%% of the daily close: %d of %d (%.1f%%)" % (ok, len(trades),
               ok / len(trades) * 100))
    checks["V7"] = ok / len(trades) >= 0.95
    random.seed(20261121)
    out.append("   hand-check sample (side / date against the release text):")
    for t in random.sample(trades, min(12, len(trades))):
        out.append("     %s %-12s %-4s 09:20 %9.2f 15:25 %9.2f day close %9.2f gross %+6.2f%%  %s  [%s]"
                   % (t["date"], t["sym"], t["side"], t["p920"], t["p1525"], t["dclose"], t["gross"],
                      "; ".join(t["idxs"][:3]), ",".join(t["srcs"])))

    out.append("")
    for c in sorted(checks):
        out.append("  [%s] %s" % ("PASS" if checks[c] else "fail", c))
    out.append("VERDICT: " + ("SURVIVES" if all(checks.values()) else "DOES NOT SURVIVE") +
               " (V7 also needs the hand check of the sample)")
    print("\n".join(out))


if __name__ == "__main__":
    main()
