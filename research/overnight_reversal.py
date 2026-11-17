#!/usr/bin/env python3
"""
Idea 4: overnight short-term reversal (pre-registered 2026-11-17 ~22:45 IST, research only).

  Stocks   the edge-map universe; each day prior close >= 200 and 20-day avg turnover >= Rs 25 crore
  Signal   at the 15:15 candle close: r_rel = (stock 15:15 / prior close - 1) - (NIFTY 15:15 / NIFTY prior 15:29 - 1)
  Trade    buy the 5 lowest r_rel with r_rel <= -3%, Rs 2 lakh each, delivery, long only
  Variants A = sell at the next session's 09:30 candle close; B = sell at the next session's 15:15 close (A preferred)
  Costs    Rs 30 x 2 brokerage, STT 0.1% buy + sell, exchange 0.00297% both, SEBI Rs 10/crore, stamp 0.015% buy,
           DP Rs 20 on the sell, GST 18% on brokerage + exchange + SEBI, slippage 0.03% a side
  Pass     >= 200 trades, mean net >= +0.10% a trade, t >= 2.0 on daily means, both date halves > 0
Reads the cached data from intraday_edge_map.py / sector_leader_follower.py only (no API calls).
"""
import datetime as dt
import json
import math
import os
import sys
from collections import defaultdict

FROM, TO = dt.date(2024, 10, 1), dt.date(2026, 9, 30)
EDGE, IDEA3 = "/tmp/edge_cache", "/tmp/idea3_cache"
NIFTY = "NSE_INDEX_Nifty_50"
I_1515, I_0930, I_1529 = 360, 15, 374
N_PICK, MAX_REL = 5, -3.0
NOTIONAL, MIN_PRICE, MIN_TURN = 200000, 200, 25e7


def months():
    d = FROM.replace(day=1)
    while d <= TO:
        nxt = (d.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
        yield max(FROM, d)
        d = nxt


def cost_pct(buy_px, sell_px):
    qty = max(1, int(NOTIONAL // buy_px))
    buy, sell = buy_px * qty, sell_px * qty
    t = buy + sell
    exch, sebi = t * 0.00297 / 100, t * 10 / 1e7
    rs = 60 + 0.001 * t + exch + sebi + buy * 0.00015 + 20 + 0.18 * (60 + exch + sebi) + t * 0.0003
    return rs / buy * 100


def stats(name, trades):
    n = len(trades)
    if n < 2:
        return "%-34s %6d  too few" % (name, n), False
    byday = defaultdict(list)
    for d, net, g in trades:
        byday[d].append(net)
    dm = [sum(v) / len(v) for _, v in sorted(byday.items())]
    k = len(dm)
    mu = sum(dm) / k
    sd = math.sqrt(sum((x - mu) ** 2 for x in dm) / (k - 1)) if k > 1 else 0
    t = mu / (sd / math.sqrt(k)) if sd > 0 else 0
    days = sorted(byday)
    mid = days[k // 2]
    h1 = [x[1] for x in trades if x[0] < mid]
    h2 = [x[1] for x in trades if x[0] >= mid]
    mean = sum(x[1] for x in trades) / n
    gross = sum(x[2] for x in trades) / n
    win = 100 * sum(1 for x in trades if x[1] > 0) / n
    h1m, h2m = sum(h1) / len(h1), sum(h2) / len(h2)
    ok = n >= 200 and mean >= 0.10 and t >= 2.0 and h1m > 0 and h2m > 0
    return ("%-34s %6d %5d %8.3f %8.3f %6.2f %8.3f %8.3f %5.1f" % (name, n, k, mean, gross, t, h1m, h2m, win)), ok


def main():
    uni = [l.rstrip("\n").split("\t") for l in open(sys.argv[1]) if l.strip()]
    nifty = {}
    for a in months():
        with open(os.path.join(IDEA3, "min_%s_%s.json" % (NIFTY, a))) as f:
            for d, arr in json.load(f).items():
                nifty[dt.date.fromisoformat(d)] = arr
    days = sorted(d for d in nifty if nifty[d][I_1515] and nifty[d][I_1529])
    nxt = {days[i]: days[i + 1] for i in range(len(days) - 1)}
    prv = {days[i + 1]: days[i] for i in range(len(days) - 1)}

    cands = defaultdict(list)  # day -> [(r_rel, key, entry, exit_A, exit_B)]
    used = 0
    for sym, isin, key in uni:
        kf = key.replace("|", "_").replace(" ", "_")
        dpath = os.path.join(IDEA3, "daily_%s.json" % kf)
        if not os.path.exists(dpath):
            continue
        daily = {dt.date.fromisoformat(d): (c, v) for d, c, v in json.load(open(dpath))}
        ds = sorted(daily)
        pit = {}
        for i, d in enumerate(ds):
            if i >= 20:
                prev = ds[i - 20:i]
                pit[d] = (daily[ds[i - 1]][0], sum(daily[x][0] * daily[x][1] for x in prev) / 20)
        mins = {}
        for a in months():
            p = os.path.join(EDGE, "m_%s_%s.json" % (kf, a))
            if os.path.exists(p):
                for d, v in json.load(open(p)).items():
                    mins[dt.date.fromisoformat(d)] = v[0]
        if not mins:
            continue
        used += 1
        for d, cl in mins.items():
            if d not in pit or d not in prv or d not in nxt or nxt[d] not in mins:
                continue
            prior, turn = pit[d]
            c, nc = cl[I_1515], mins[nxt[d]]
            if prior < MIN_PRICE or turn < MIN_TURN or not c or not nc[I_0930] or not nc[I_1515]:
                continue
            n_prev = nifty[prv[d]][I_1529]
            r_rel = (c / prior - 1) * 100 - (nifty[d][I_1515] / n_prev - 1) * 100
            if r_rel <= MAX_REL:
                cands[d].append((r_rel, key, c, nc[I_0930], nc[I_1515]))

    tr = {"A": [], "B": []}
    for d, cs in cands.items():
        for r_rel, key, c, xa, xb in sorted(cs)[:N_PICK]:
            for v, x in (("A", xa), ("B", xb)):
                g = (x / c - 1) * 100
                tr[v].append((d, g - cost_pct(c, x), g))
    out = ["OVERNIGHT REVERSAL (idea 4, pre-registered 2026-11-17; research only)",
           "Data %s..%s, %d stocks with minute data, %d signal days, cost about %.3f%% a round trip"
           % (FROM, TO, used, len(cands), cost_pct(1000, 1000)),
           "Pass: >= 200 trades, mean net >= +0.10%, t >= 2.0 (daily means), both halves > 0", "",
           "%-34s %6s %5s %8s %8s %6s %8s %8s %5s  %s" % ("variant", "trades", "days", "net %", "gross %", "t",
                                                         "half 1", "half 2", "win%", "verdict")]
    res = {}
    for v, lbl in (("A", "A sell next 09:30 (DECIDES)"), ("B", "B sell next 15:15 (DECIDES)")):
        line, ok = stats(lbl, tr[v])
        res[v] = ok
        out.append(line + ("  PASS" if ok else "  FAIL"))
    out.append("")
    out.append("DECISION  " + ("A passes" if res["A"] else "B passes" if res["B"] else
                               "NEITHER passes: idea 4 is dropped as pre-registered (no re-tuning).")
               + (" -> separate paper book (user pre-approved overnight)." if res["A"] or res["B"] else ""))
    print("\n".join(out))


if __name__ == "__main__":
    main()
