#!/usr/bin/env python3
"""
Path 3: intraday trades only in the direction of momentum (pre-registered 2026-11-18 evening; user said
"go ahead with all three" before any data was looked at; research only).

  Stocks    the edge-map universe; each day prior close >= 100 and 20-day average turnover >= Rs 25 crore
  Momentum  the live book's measure, point-in-time at the previous close: return from 126 to 21 trading days
            ago divided by the standard deviation of daily returns over the last 126 days. Leaders = top 30,
            laggards = bottom 30, middle = ranks 100-200 of the eligible stocks that day.
  Signal    at the 10:30 candle close: r_rel = (stock / prior close - 1) - (NIFTY / NIFTY prior 15:29 - 1), in %
  Trade A   long the 5 leaders with the lowest r_rel among those with r_rel <= -1.0% (a dip), Rs 2 lakh each,
            exit at the 15:15 candle close.
  Trade B   A plus: short the 5 laggards with the highest r_rel among those with r_rel >= +1.0% (a bounce).
  Control   the A rule on the middle ranks (100-200) instead of the leaders.
  Costs     intraday, Rs 2 lakh: brokerage Rs 30 x 2, STT 0.025% on the sell, exchange 0.00297% both sides,
            SEBI Rs 10/crore, stamp 0.003% on the buy, GST 18% on brokerage + exchange + SEBI, slippage 0.03% a side
  Pass      (each of A, B) >= 200 trades, mean net >= +0.10% a trade, t >= 2.0 on daily means, both date halves
            > 0, AND mean net above the control's (otherwise momentum adds nothing to a plain dip-buy)
Reads the cached data from intraday_edge_map.py / sector_leader_follower.py only (no API calls).
Usage: python3 momentum_intraday.py /tmp/idea3_universe.tsv
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
I_1030, I_1515, I_1529 = 75, 360, 374
N_PICK, DIP = 5, 1.0
NOTIONAL, MIN_PRICE, MIN_TURN = 200000, 100, 25e7
LOOK, SKIP = 126, 21


def months():
    d = FROM.replace(day=1)
    while d <= TO:
        yield max(FROM, d)
        d = (d.replace(day=28) + dt.timedelta(days=4)).replace(day=1)


def cost_pct(entry, exit_, long_):
    qty = max(1, int(NOTIONAL // entry))
    buy_px, sell_px = (entry, exit_) if long_ else (exit_, entry)
    buy, sell = buy_px * qty, sell_px * qty
    t = buy + sell
    exch, sebi = t * 0.00297 / 100, t * 10 / 1e7
    rs = 60 + sell * 0.00025 + exch + sebi + buy * 0.00003 + 0.18 * (60 + exch + sebi) + t * 0.0003
    return rs / (entry * qty) * 100


def stats(name, trades):
    n = len(trades)
    if n < 2:
        return "%-36s %6d  too few" % (name, n), None
    byday = defaultdict(list)
    for d, net, g in trades:
        byday[d].append(net)
    dm = [sum(v) / len(v) for _, v in sorted(byday.items())]
    k = len(dm)
    mu = sum(dm) / k
    sd = math.sqrt(sum((x - mu) ** 2 for x in dm) / (k - 1)) if k > 1 else 0
    t = mu / (sd / math.sqrt(k)) if sd > 0 else 0
    mid = sorted(byday)[k // 2]
    h1 = [x[1] for x in trades if x[0] < mid]
    h2 = [x[1] for x in trades if x[0] >= mid]
    mean = sum(x[1] for x in trades) / n
    gross = sum(x[2] for x in trades) / n
    win = 100 * sum(1 for x in trades if x[1] > 0) / n
    h1m, h2m = sum(h1) / len(h1), sum(h2) / len(h2)
    s = dict(n=n, mean=mean, t=t, h1=h1m, h2=h2m)
    return ("%-36s %6d %5d %8.3f %8.3f %6.2f %8.3f %8.3f %5.1f" % (name, n, k, mean, gross, t, h1m, h2m, win)), s


def main():
    uni = [l.rstrip("\n").split("\t") for l in open(sys.argv[1]) if l.strip()]
    nifty = {}
    for a in months():
        with open(os.path.join(IDEA3, "min_%s_%s.json" % (NIFTY, a))) as f:
            for d, arr in json.load(f).items():
                nifty[dt.date.fromisoformat(d)] = arr
    days = sorted(d for d in nifty if nifty[d][I_1030] and nifty[d][I_1529])
    prv = {days[i + 1]: days[i] for i in range(len(days) - 1)}

    score = defaultdict(dict)   # day -> key -> momentum score (point-in-time)
    sig = defaultdict(dict)     # day -> key -> (r_rel, entry, exit)
    for sym, isin, key in uni:
        kf = key.replace("|", "_").replace(" ", "_")
        dpath = os.path.join(IDEA3, "daily_%s.json" % kf)
        if not os.path.exists(dpath):
            continue
        daily = {dt.date.fromisoformat(d): (c, v) for d, c, v in json.load(open(dpath))}
        ds = sorted(daily)
        cl = [daily[d][0] for d in ds]
        for i in range(LOOK + 1, len(ds)):
            d = ds[i]  # values known at the previous close (index i - 1) are used for day d
            j = i - 1
            prior = cl[j]
            turn = sum(daily[ds[x]][0] * daily[ds[x]][1] for x in range(j - 19, j + 1)) / 20
            if prior < MIN_PRICE or turn < MIN_TURN or cl[j - LOOK] <= 0:
                continue
            rets = [cl[x] / cl[x - 1] - 1 for x in range(j - LOOK + 1, j + 1) if cl[x - 1] > 0]
            if len(rets) < LOOK - 5:
                continue
            mu = sum(rets) / len(rets)
            sd = math.sqrt(sum((r - mu) ** 2 for r in rets) / (len(rets) - 1))
            if sd <= 0:
                continue
            score[d][key] = (cl[j - SKIP] / cl[j - LOOK] - 1) / sd
            score[d].setdefault("_prior_" + key, prior)
        for a in months():
            p = os.path.join(EDGE, "m_%s_%s.json" % (kf, a))
            if not os.path.exists(p):
                continue
            for ds_, v in json.load(open(p)).items():
                d = dt.date.fromisoformat(ds_)
                c = v[0]
                if d not in prv or key not in score.get(d, {}) or not c[I_1030] or not c[I_1515]:
                    continue
                prior = score[d]["_prior_" + key]
                n_prev = nifty[prv[d]][I_1529]
                r_rel = (c[I_1030] / prior - 1) * 100 - (nifty[d][I_1030] / n_prev - 1) * 100
                sig[d][key] = (r_rel, c[I_1030], c[I_1515])

    tr = {"A": [], "B": [], "C": []}
    for d in sorted(sig):
        ranked = sorted((k for k in score[d] if not k.startswith("_")), key=lambda k: -score[d][k])
        leaders, laggards, middle = ranked[:30], ranked[-30:] if len(ranked) >= 60 else [], ranked[99:200]

        def longs(group):
            c = sorted((sig[d][k][0], k) for k in group if k in sig[d] and sig[d][k][0] <= -DIP)
            return [k for _, k in c[:N_PICK]]

        for k in longs(leaders):
            _, e, x = sig[d][k]
            g = (x / e - 1) * 100
            r = (d, g - cost_pct(e, x, True), g)
            tr["A"].append(r)
            tr["B"].append(r)
        shorts = sorted(((-sig[d][k][0], k) for k in laggards if k in sig[d] and sig[d][k][0] >= DIP))[:N_PICK]
        for _, k in shorts:
            _, e, x = sig[d][k]
            g = (e / x - 1) * 100
            tr["B"].append((d, g - cost_pct(e, x, False), g))
        for k in longs(middle):
            _, e, x = sig[d][k]
            g = (x / e - 1) * 100
            tr["C"].append((d, g - cost_pct(e, x, True), g))

    out = ["MOMENTUM-DIRECTION INTRADAY (path 3, pre-registered 2026-11-18; research only)",
           "Signal days %d (%s .. %s), cost about %.3f%% a round trip" % (
               len(sig), min(sig) if sig else "-", max(sig) if sig else "-", cost_pct(1000, 1000, True)),
           "Pass: >= 200 trades, mean net >= +0.10%, t >= 2.0 (daily means), both halves > 0, above control", "",
           "%-36s %6s %5s %8s %8s %6s %8s %8s %5s  %s" % ("variant", "trades", "days", "net %", "gross %", "t",
                                                         "half 1", "half 2", "win%", "verdict")]
    lc, c = stats("control: dip-buy on ranks 100-200", tr["C"])
    res = {}
    for v, lbl in (("A", "A leaders bought on dips (DECIDES)"), ("B", "B A + laggards shorted (DECIDES)")):
        line, s = stats(lbl, tr[v])
        ok = bool(s) and s["n"] >= 200 and s["mean"] >= 0.10 and s["t"] >= 2.0 and s["h1"] > 0 and s["h2"] > 0 \
            and (c is None or s["mean"] > c["mean"])
        res[v] = ok
        out.append(line + ("  PASS" if ok else "  FAIL"))
    out.append(lc + "  info")
    out.append("")
    out.append("DECISION  " + ("A passes" if res["A"] else "B passes" if res["B"] else
                               "NEITHER passes: path 3 is dropped as pre-registered (no re-tuning).")
               + (" -> propose a separate paper book (user's OK)." if res["A"] or res["B"] else ""))
    print("\n".join(out))


if __name__ == "__main__":
    main()
