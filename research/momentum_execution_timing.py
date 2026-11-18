#!/usr/bin/env python3
"""
Path 2: execution timing for the momentum rebalance (2026-11-18; measurement only, research only).

Momentum is locked for its test period, so this changes nothing; it measures what a different order time would
have cost, for a proposal at the January review.

  Stocks    momentum leaders each day: top 30 by the live book's measure (6-1 month return / daily volatility,
            point-in-time at the previous close) among the edge-map universe with prior close >= 100 and 20-day
            average turnover >= Rs 25 crore. Also reported: all eligible stocks.
  Measure   for each order time T (candle close at 09:20, 10:00, 11:00, 12:00, 13:00, 14:00, 15:00, 15:15), the
            price at T versus that day's volume-weighted average price (from 1-minute closes and volumes), in bps.
            For a BUY lower is better; for a SELL higher is better. The day's VWAP is the benchmark a broker's
            VWAP order would roughly achieve.
  Current   rebalance sells at 09:20 and buys at 13:00.
  Proposal  a different time is proposed only if it beats the current one by >= 5 bps in BOTH date halves.
Reads the cached data from intraday_edge_map.py / sector_leader_follower.py only (no API calls).
Usage: python3 momentum_execution_timing.py /tmp/idea3_universe.tsv
"""
import datetime as dt
import json
import math
import os
import sys
from collections import defaultdict

FROM, TO = dt.date(2024, 10, 1), dt.date(2026, 9, 30)
EDGE, IDEA3 = "/tmp/edge_cache", "/tmp/idea3_cache"
TIMES = [("09:20", 5), ("10:00", 45), ("11:00", 105), ("12:00", 165), ("13:00", 225), ("14:00", 285),
         ("15:00", 345), ("15:15", 360)]
MIN_PRICE, MIN_TURN, LOOK, SKIP = 100, 25e7, 126, 21


def months():
    d = FROM.replace(day=1)
    while d <= TO:
        yield max(FROM, d)
        d = (d.replace(day=28) + dt.timedelta(days=4)).replace(day=1)


def main():
    uni = [l.rstrip("\n").split("\t") for l in open(sys.argv[1]) if l.strip()]
    score = defaultdict(dict)
    dev = defaultdict(dict)  # day -> key -> [bps vs VWAP per time]
    for sym, isin, key in uni:
        kf = key.replace("|", "_").replace(" ", "_")
        dpath = os.path.join(IDEA3, "daily_%s.json" % kf)
        if not os.path.exists(dpath):
            continue
        daily = {dt.date.fromisoformat(d): (c, v) for d, c, v in json.load(open(dpath))}
        ds = sorted(daily)
        cl = [daily[d][0] for d in ds]
        for i in range(LOOK + 1, len(ds)):
            j = i - 1
            turn = sum(daily[ds[x]][0] * daily[ds[x]][1] for x in range(j - 19, j + 1)) / 20
            if cl[j] < MIN_PRICE or turn < MIN_TURN or cl[j - LOOK] <= 0:
                continue
            rets = [cl[x] / cl[x - 1] - 1 for x in range(j - LOOK + 1, j + 1) if cl[x - 1] > 0]
            mu = sum(rets) / len(rets)
            sd = math.sqrt(sum((r - mu) ** 2 for r in rets) / (len(rets) - 1))
            if sd > 0:
                score[ds[i]][key] = (cl[j - SKIP] / cl[j - LOOK] - 1) / sd
        for a in months():
            p = os.path.join(EDGE, "m_%s_%s.json" % (kf, a))
            if not os.path.exists(p):
                continue
            for d_, (c, v) in json.load(open(p)).items():
                d = dt.date.fromisoformat(d_)
                if key not in score.get(d, {}):
                    continue
                pv = [(x, y) for x, y in zip(c, v) if x and y]
                vol = sum(y for _, y in pv)
                if vol <= 0 or any(not c[i] for _, i in TIMES):
                    continue
                vwap = sum(x * y for x, y in pv) / vol
                dev[d][key] = [(c[i] / vwap - 1) * 1e4 for _, i in TIMES]

    days = sorted(dev)
    mid = days[len(days) // 2]
    out = ["MOMENTUM EXECUTION TIMING (path 2, measurement only; momentum rules unchanged)",
           "Days %d (%s .. %s). Price at each time vs the day's VWAP, bps (BUY: lower is better; SELL: higher is better)"
           % (len(days), days[0], days[-1]), ""]
    for label, pick in (("momentum leaders (top 30)", True), ("all eligible stocks", False)):
        rows = defaultdict(list)  # time index -> [(day, bps)]
        for d in days:
            keys = sorted(score[d], key=lambda k: -score[d][k])[:30] if pick else list(score[d])
            for k in keys:
                if k in dev[d]:
                    for ti, b in enumerate(dev[d][k]):
                        rows[ti].append((d, b))
        out.append("%s: %d stock-days" % (label, len(rows[0])))
        out.append("  %-6s %8s %8s %8s %6s" % ("time", "mean", "half 1", "half 2", "t"))
        means = {}
        for ti, (name, _) in enumerate(TIMES):
            byday = defaultdict(list)
            for d, b in rows[ti]:
                byday[d].append(b)
            dm = [sum(x) / len(x) for _, x in sorted(byday.items())]
            mu = sum(dm) / len(dm)
            sd = math.sqrt(sum((x - mu) ** 2 for x in dm) / (len(dm) - 1))
            h1 = [b for d, b in rows[ti] if d < mid]
            h2 = [b for d, b in rows[ti] if d >= mid]
            means[name] = (mu, sum(h1) / len(h1), sum(h2) / len(h2))
            out.append("  %-6s %8.1f %8.1f %8.1f %6.2f" % (name, mu, means[name][1], means[name][2],
                                                           mu / (sd / math.sqrt(len(dm)))))
        sell_now, buy_now = means["09:20"], means["13:00"]
        best_sell = max(means, key=lambda k: means[k][0])
        best_buy = min(means, key=lambda k: means[k][0])
        s_ok = all(means[best_sell][h] - sell_now[h] >= 5 for h in (1, 2))
        b_ok = all(buy_now[h] - means[best_buy][h] >= 5 for h in (1, 2))
        out.append("  SELL now 09:20 (%.1f); best %s (%.1f): %s" % (
            sell_now[0], best_sell, means[best_sell][0],
            "PROPOSE (>= 5 bps better in both halves)" if s_ok and best_sell != "09:20" else "keep 09:20"))
        out.append("  BUY now 13:00 (%.1f); best %s (%.1f): %s" % (
            buy_now[0], best_buy, means[best_buy][0],
            "PROPOSE (>= 5 bps better in both halves)" if b_ok and best_buy != "13:00" else "keep 13:00"))
        out.append("")
    print("\n".join(out))


if __name__ == "__main__":
    main()
