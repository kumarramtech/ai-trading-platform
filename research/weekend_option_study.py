#!/usr/bin/env python3
"""
NIFTY WEEKEND OPTION-SELLING study (rules fixed and committed 2026-11-21 before any option price of these days was
looked at; autonomous mode, the user asked to test options ideas too; research only; the condor paper book is NOT
changed).

Idea      published finding (Jones and Shemesh, "Option mispricing around nontrading periods"): option returns are
          strongly negative over weekends, because prices do not fully allow for the two lost days of time value.
          A hedged seller who holds only over the weekend may earn it.
Data      Upstox Plus expired instruments (expiries, option contracts, 1-minute candles); NIFTY spot from the index
          1-minute candles.
Events    W: every trading day D (2024-10-01 .. 2026-11-13) whose next trading day N is >= 3 calendar days later
             (weekends and long weekends).
          C (control, information only): every trading day D whose next trading day N is the next calendar day.
          Expiry used = the nearest listed NIFTY expiry strictly AFTER N (never held into an expiry day); the event is
          skipped (counted) if that expiry is after 2026-11-20 (not yet in the expired-instruments data).
Trade     on D at 15:15 (close of the 15:15 candle): S = NIFTY spot; ATM K = listed strike nearest S; SELL the K call
          and the K put; BUY the call at the smallest listed strike >= S x 1.02 and the put at the largest listed
          strike <= S x 0.98. Exit all four on N at 09:30 (close of the 09:30 candle). A missing candle uses the last
          one in the 5 minutes before; if any leg has none, the event is skipped (counted).
Costs     as the expiry-day study: slippage 0.5 point per leg each way; brokerage Rs 30 x 8; STT 0.15% of premium
          sold; exchange 0.03503% and SEBI 0.0001% of premium turnover; stamp 0.003% of premium bought; GST 18% on
          brokerage + exchange + SEBI. Lot size from the contract.
Result    Rs per lot per event, net. Year 1 = D up to 2025-09-30, year 2 after.
PASS (W)  >= 80 events; mean > 0 with t >= 2.5; year 1 > 0 and year 2 > 0; > 0 without the best month;
          worst event >= Rs -20,000 per lot. Information only: win rate, max drawdown, average credit, control C.
Usage (server, outside market hours): python3 weekend_option_study.py
"""
import datetime as dt
import os
import sys
import urllib.parse
import urllib.request
from collections import defaultdict

import index_rebalance as ir
import expiry_straddle_study as es

FROM, TO = dt.date(2024, 10, 1), dt.date(2026, 11, 13)
LAST_EXP = dt.date(2026, 11, 20)
Y1_END = dt.date(2025, 9, 30)
I_IN, I_OUT = 360, 15  # 15:15 on D, 09:30 on N
WING = 0.02
SLIP = 0.5
T_MIN = 2.5
WORST = -20_000


def trade(d, n, exps, nk, skip):
    spot = ir.minutes(ir.NIFTY, d)
    s = es.at(spot, I_IN) if spot else None
    if not s:
        skip["no NIFTY minutes"] += 1
        return None
    later = [x for x in exps if x > n]
    if not later or later[0] > LAST_EXP:
        skip["expiry not in data"] += 1
        return None
    e = later[0]
    cons = es.v2("expired-instruments/option/contract?instrument_key=%s&expiry_date=%s" % (nk, e)) or []
    ce = {c["strike_price"]: c for c in cons if c["instrument_type"] == "CE"}
    pe = {c["strike_price"]: c for c in cons if c["instrument_type"] == "PE"}
    strikes = sorted(set(ce) & set(pe))
    if not strikes:
        skip["no contracts"] += 1
        return None
    k = min(strikes, key=lambda x: (abs(x - s), x))
    cw = [x for x in sorted(ce) if x >= s * (1 + WING)]
    pw = [x for x in sorted(pe) if x <= s * (1 - WING)]
    if not cw or not pw:
        skip["no wing strike"] += 1
        return None
    legs = [(ce[k], -1), (pe[k], -1), (ce[cw[0]], 1), (pe[pw[-1]], 1)]
    px = []
    for c, side in legs:
        a = es.at(es.closes(c["instrument_key"], d), I_IN)
        b = es.at(es.closes(c["instrument_key"], n), I_OUT)
        if a is None or b is None:
            skip["missing option candle"] += 1
            return None
        px.append((side, a, b))
    lot = float(legs[0][0].get("lot_size") or cons[0].get("lot_size"))
    pts = sum(-side * (a - b) for side, a, b in px) - 4 * 2 * SLIP
    sold = (px[0][1] + px[1][1] + px[2][2] + px[3][2]) * lot
    bought = (px[0][2] + px[1][2] + px[2][1] + px[3][1]) * lot
    credit = px[0][1] + px[1][1] - px[2][1] - px[3][1]
    return d, pts * lot - es.charges(sold, bought), credit, e


def summary(name, rows):
    pl = [r[1] for r in rows]
    n, mu, t = es.stats(pl)
    y1 = [r[1] for r in rows if r[0] <= Y1_END]
    y2 = [r[1] for r in rows if r[0] > Y1_END]
    by_m = defaultdict(float)
    for r in rows:
        by_m[r[0].strftime("%Y-%m")] += r[1]
    best = max(by_m, key=by_m.get) if by_m else None
    rest = [r[1] for r in rows if r[0].strftime("%Y-%m") != best]
    worst = min(pl) if pl else 0
    eq = peak = dd = 0.0
    for v in pl:
        eq += v
        peak = max(peak, eq)
        dd = min(dd, eq - peak)
    checks = [("events >= 80 (%d)" % n, n >= 80),
              ("mean > 0, t >= %.1f (Rs %+.0f/lot, t %.2f)" % (T_MIN, mu, t), mu > 0 and t >= T_MIN),
              ("year 1 > 0 (Rs %+.0f, %d)" % (es.stats(y1)[1], len(y1)), bool(y1) and es.stats(y1)[1] > 0),
              ("year 2 > 0 (Rs %+.0f, %d)" % (es.stats(y2)[1], len(y2)), bool(y2) and es.stats(y2)[1] > 0),
              ("without best month %s > 0 (Rs %+.0f)" % (best, es.stats(rest)[1]),
               bool(rest) and es.stats(rest)[1] > 0),
              ("worst event >= Rs %d (Rs %.0f)" % (WORST, worst), worst >= WORST)]
    lines = ["%s: %d events, Rs %+.0f/lot mean, t %.2f, win %.0f%%, total Rs %+.0f/lot, max drawdown Rs %.0f, "
             "average credit %.1f pts" % (name, n, mu, t, 100 * sum(v > 0 for v in pl) / max(1, n), sum(pl), dd,
                                          sum(r[2] for r in rows) / max(1, n)),
             "  " + "; ".join(("ok " if c else "NO ") + l for l, c in checks),
             "  by month (Rs/lot): " + ", ".join("%s %+.0f" % (m, by_m[m]) for m in sorted(by_m))]
    return all(c for _, c in checks), lines


def main():
    os.makedirs(es.OC, exist_ok=True)
    os.makedirs(ir.CACHE, exist_ok=True)
    with urllib.request.urlopen("http://localhost:9090/upstox/auth/token", timeout=15) as r:
        ir.TOK = r.read().decode().strip()
    es.ir.TOK = ir.TOK
    nk = urllib.parse.quote(ir.NIFTY, safe="")
    exps = sorted(dt.date.fromisoformat(x) for x in es.v2("expired-instruments/expiries?instrument_key=" + nk))
    days = sorted(ir.daily(ir.NIFTY))
    rows = {"W": [], "C": []}
    skip = {"W": defaultdict(int), "C": defaultdict(int)}
    for i, d in enumerate(days[:-1]):
        if not FROM <= d <= TO:
            continue
        n = days[i + 1]
        kind = "W" if (n - d).days >= 3 else "C"
        r = trade(d, n, exps, nk, skip[kind])
        if r:
            rows[kind].append(r)
            print("  %s %s -> %s exp %s credit %.1f Rs %+.0f" % (kind, d, n, r[3], r[2], r[1]), file=sys.stderr,
                  flush=True)
    ok, lw = summary("W weekend: sell ATM straddle + buy 2% wings D 15:15 -> next session 09:30", rows["W"])
    _, lc = summary("C control (one night, information only)", rows["C"])
    out = ["NIFTY WEEKEND OPTION-SELLING STUDY (pre-registered 2026-11-21; research only; condor book unchanged)",
           "skipped: W %s, C %s" % (dict(skip["W"]), dict(skip["C"])), ""] + lw + [""] + lc + ["",
                                                                                        "VERDICT: W " + (
                                                                                            "PASS" if ok else "FAIL")]
    print("\n".join(out))


if __name__ == "__main__":
    main()
