#!/usr/bin/env python3
"""
REGIME STUDY for a separate FADE-family strategy (rules fixed and committed 2026-11-20 before any result; the user
asked for "a good strategy for fade when bullish, sideways and bearish" as a separate strategy and to keep testing
without asking; research only; the FADE paper book and its promotion test are NOT changed).

Data     2024-10-01 .. 2026-09-30 (~500 sessions). Year 1 (Oct 2024 - Sep 2025) was never used in any FADE replay.
Pool     each day: EQ company shares (INE ISIN) ranked by average traded value over the previous 20 sessions (NSE CM
         bhavcopy, point in time); ranks 501-600 (FADE's pool).
Move     at 09:45 (close of the 09:45 one-minute candle): stock % from yesterday's close minus NIFTY % from
         yesterday's close. |move| > 20% is dropped as a corporate action / data error.
Regime   NIFTY at 09:45 vs yesterday's close: BULL >= +0.30%, BEAR <= -0.30%, SIDE between. Known at 09:45.
Cells    3 regimes x 2 pools x 2 sides = 12 (the long and short of a pool mirror each other, so 6 real questions):
           UP pool   = the 5 stocks with the largest move >= +2%
           DOWN pool = the 5 stocks with the most negative move <= -2%
           SHORT or LONG each, entry at the 09:46 candle close, exit at the 14:59 candle close (15:00).
Return   per trade: side x (stock % - NIFTY % over the same minutes) - 0.18% (0.13% charges + 0.05% slippage for
         ranks 501-600). One value per DAY = the average of that day's trades (trades on one day are correlated).
         Also reported: unhedged (stock % only), and Rs per day at Rs 40,000 a stock.
PASS     (all, per cell) >= 60 days with trades; day-level mean net > 0 with t >= 3.0 (12 cells: stricter than 2.5);
         net > 0 in EACH year separately; > 0 without its best month; UNHEDGED net > 0 in each year (so it works
         without a NIFTY hedge); worst day >= Rs -5,000 at Rs 40,000 a stock.
         A passing cell becomes a separate paper book; nothing else is built.
Usage (server): python3 regime_fade_study.py          (cache /tmp/cm_bhav2 from split_bonus_events.py)
"""
import datetime as dt
import json
import math
import os
import sys
import urllib.request
from collections import defaultdict

import index_rebalance as ir

CM = "/tmp/cm_bhav2"
FROM, TO = dt.date(2024, 10, 1), dt.date(2026, 9, 30)
Y1_END = dt.date(2025, 9, 30)
RANK_LO, RANK_HI = 501, 600
REG = 0.30
MOVE = 2.0
TOPN = 5
COST = 0.18
SIZE = 40_000
T_MIN = 3.0
I_SIG, I_IN, I_OUT = 30, 31, 344  # 09:45, 09:46, 14:59 candle closes (index 0 = 09:15)


def stats(x):
    n = len(x)
    if n < 2:
        return n, (x[0] if x else 0.0), 0.0
    m = sum(x) / n
    sd = math.sqrt(sum((v - m) ** 2 for v in x) / (n - 1))
    return n, m, (m / (sd / math.sqrt(n)) if sd > 0 else 0.0)


def load_days():
    cal, files = [], {}
    for f in sorted(os.listdir(CM)):
        d = dt.date.fromisoformat(f[:10])
        v = json.load(open(os.path.join(CM, f)))
        if v:
            cal.append(d)
            files[d] = v
    return cal, files


def main():
    with urllib.request.urlopen("http://localhost:9090/upstox/auth/token", timeout=15) as r:
        ir.TOK = r.read().decode().strip()
    os.makedirs(ir.CACHE, exist_ok=True)
    cal, files = load_days()
    # The 20-day traded value needs bhavcopies from before FROM: fetch what the event-list cache lacks.
    import split_bonus_events as sb
    sb.CACHE = CM
    d = FROM - dt.timedelta(days=40)
    while d < FROM:
        if d.weekday() < 5 and d not in files:
            v = sb.day_file(d)
            if v:
                cal.append(d)
                files[d] = v
        d += dt.timedelta(days=1)
    cal = sorted(set(cal))

    nifty_d = ir.daily(ir.NIFTY)
    pools = {}
    for i, day in enumerate(cal):
        if day < FROM or day > TO or i < 20:
            continue
        tv = defaultdict(float)
        for x in cal[i - 20:i]:
            for s, (isin, close, opn, val) in files[x].items():
                if isin.startswith("INE"):
                    tv[s] += val
        ranked = sorted(tv, key=lambda s: -tv[s])
        pools[day] = [s for s in ranked[RANK_LO - 1:RANK_HI] if s in files[day] and s in files[cal[i - 1]]]
    syms = sorted({s for p in pools.values() for s in p})
    print("sessions %d, pool symbols over time %d" % (len(pools), len(syms)), file=sys.stderr, flush=True)

    cells = defaultdict(list)  # (regime, pool, side) -> [(day, [hedged...], [unhedged...])]
    regimes = defaultdict(int)
    for n_day, day in enumerate(sorted(pools)):
        i = cal.index(day)
        prev = cal[i - 1]
        nprev = nifty_d.get(prev, (None,))[0]
        nm = ir.minutes(ir.NIFTY, day)
        if not nprev or not nm or not nm[I_SIG] or not nm[I_IN] or not nm[I_OUT]:
            continue
        n_sig = (nm[I_SIG] / nprev - 1) * 100
        n_leg = (nm[I_OUT] / nm[I_IN] - 1) * 100
        reg = "BULL" if n_sig >= REG else "BEAR" if n_sig <= -REG else "SIDE"
        regimes[reg] += 1
        moves = []
        for s in pools[day]:
            isin, _, _, _ = files[day][s]
            pc = files[prev][s][1]
            m = ir.minutes("NSE_EQ|" + isin, day)
            if not m or not m[I_SIG] or not m[I_IN] or not m[I_OUT] or pc <= 0:
                continue
            mv = (m[I_SIG] / pc - 1) * 100 - n_sig
            if abs(mv) > 20:
                continue
            moves.append((mv, (m[I_OUT] / m[I_IN] - 1) * 100))
        up = sorted([x for x in moves if x[0] >= MOVE], key=lambda x: -x[0])[:TOPN]
        dn = sorted([x for x in moves if x[0] <= -MOVE], key=lambda x: x[0])[:TOPN]
        for pool, picks in (("UP", up), ("DOWN", dn)):
            if not picks:
                continue
            for side, sg in (("SHORT", -1), ("LONG", 1)):
                cells[(reg, pool, side)].append((day, [sg * (r - n_leg) - COST for _, r in picks],
                                                 [sg * r - COST for _, r in picks]))
        if n_day % 50 == 0:
            print("  %s done %d/%d" % (day, n_day, len(pools)), file=sys.stderr, flush=True)

    out = ["REGIME STUDY (pre-registered 2026-11-20; research only; FADE book unchanged)",
           "sessions by regime at 09:45: %s" % dict(regimes),
           "cost %.2f%% a trade; t on one value per day; Rs/day at Rs %d a stock; PASS needs t >= %.1f and all checks" % (
               COST, SIZE, T_MIN), ""]
    passed = []
    for reg in ("BULL", "SIDE", "BEAR"):
        for pool in ("UP", "DOWN"):
            for side in ("SHORT", "LONG"):
                rows = cells[(reg, pool, side)]
                daily = [sum(h) / len(h) for _, h, _ in rows]
                rs = [sum(h) / 100 * SIZE for _, h, _ in rows]
                n, mu, t = stats(daily)
                y1 = [v for (d, _, _), v in zip(rows, daily) if d <= Y1_END]
                y2 = [v for (d, _, _), v in zip(rows, daily) if d > Y1_END]
                u1 = [sum(u) / len(u) for d, _, u in rows if d <= Y1_END]
                u2 = [sum(u) / len(u) for d, _, u in rows if d > Y1_END]
                by_m = defaultdict(float)
                for (d, _, _), v in zip(rows, daily):
                    by_m[d.strftime("%Y-%m")] += v
                best = max(by_m, key=by_m.get) if by_m else None
                rest = [v for (d, _, _), v in zip(rows, daily) if d.strftime("%Y-%m") != best]
                worst = min(rs) if rs else 0
                checks = [("days >= 60 (%d)" % n, n >= 60),
                          ("net > 0, t >= %.1f (%+.3f%%, t %.2f)" % (T_MIN, mu, t), mu > 0 and t >= T_MIN),
                          ("year 1 > 0 (%+.3f%%, %d d)" % (stats(y1)[1], len(y1)), bool(y1) and stats(y1)[1] > 0),
                          ("year 2 > 0 (%+.3f%%, %d d)" % (stats(y2)[1], len(y2)), bool(y2) and stats(y2)[1] > 0),
                          ("without best month %s > 0 (%+.3f%%)" % (best, stats(rest)[1]), bool(rest) and stats(rest)[1] > 0),
                          ("unhedged > 0 each year (%+.3f / %+.3f)" % (stats(u1)[1], stats(u2)[1]),
                           bool(u1) and bool(u2) and stats(u1)[1] > 0 and stats(u2)[1] > 0),
                          ("worst day >= Rs -5000 (Rs %.0f)" % worst, worst >= -5000)]
                ok = all(c for _, c in checks)
                out.append("%-4s %-4s %-5s: %3d days, %4d trades, net %+.3f%%/trade-day, t %5.2f, Rs %+.0f/day -> %s" % (
                    reg, pool, side, n, sum(len(h) for _, h, _ in rows), mu, t, sum(rs) / max(1, n),
                    "PASS" if ok else "fail"))
                out.append("      " + "; ".join(("ok " if c else "NO ") + l for l, c in checks))
                if ok:
                    passed.append("%s %s %s" % (reg, pool, side))
    out += ["", "VERDICT: " + (", ".join(passed) + " PASS" if passed else "no cell passes")]
    print("\n".join(out))


if __name__ == "__main__":
    main()
