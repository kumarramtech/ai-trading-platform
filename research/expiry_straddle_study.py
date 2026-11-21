#!/usr/bin/env python3
"""
NIFTY EXPIRY-DAY HEDGED STRADDLE study (rules fixed and committed 2026-11-21 before any option price was looked at;
the user asked to test options ideas too, without asking; research only; the condor paper book is NOT changed).

Idea      on its expiry day a weekly NIFTY option loses all remaining time value within hours; retail buyers
          overpay for cheap expiry-day options, so a hedged seller may earn it.
Data      Upstox Plus expired instruments: every weekly NIFTY expiry listed by /v2/expired-instruments/expiries
          from 2024-10-03 up to 2026-11-20; option contracts and 1-minute candles of the expiry day. NIFTY spot from
          the index 1-minute candles.
Trade     on each expiry day, at 09:30 (close of the 09:30 candle):
            S = NIFTY spot; ATM K = the listed strike nearest S;
            SELL the K call and the K put; BUY the call at the smallest listed strike >= S x 1.015 and the put at
            the largest listed strike <= S x 0.985 (the loss is capped).
          Exit all four at 15:15 (close of the 15:15 candle). A missing candle uses the last one in the 5 minutes
          before; if any leg has none, the expiry is skipped (counted).
Costs     slippage 0.5 point per leg each way (4 points per trade); brokerage Rs 30 x 8 orders; STT 0.15% of premium
          sold; exchange 0.03503% and SEBI 0.0001% of premium turnover; stamp 0.003% of premium bought; GST 18% on
          brokerage + exchange + SEBI. Lot size from the contract.
Result    Rs per lot per expiry, net. Year 1 = expiries up to 2025-09-30, year 2 after.
PASS      >= 80 expiries; mean > 0 with t >= 2.5; year 1 > 0 and year 2 > 0; > 0 without the best month;
          worst expiry >= Rs -20,000 per lot. Information only: win rate, max drawdown, average credit.
Usage (server, outside market hours): python3 expiry_straddle_study.py
"""
import datetime as dt
import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict

import index_rebalance as ir

OC = "/tmp/opt_cache"
FROM, TO = dt.date(2024, 10, 3), dt.date(2026, 11, 20)
Y1_END = dt.date(2025, 9, 30)
I_IN, I_OUT = 15, 360  # 09:30 and 15:15 candle closes (index 0 = 09:15)
WING = 0.015
SLIP = 0.5
T_MIN = 2.5
WORST = -20_000


def v2(path):
    """GET https://api.upstox.com/v2/<path> -> data, cached on disk."""
    p = os.path.join(OC, path.replace("/", "_").replace("|", "_").replace("?", "_").replace("&", "_")
                     .replace("%", "_").replace("=", "_")[:200] + ".json")
    if os.path.exists(p):
        return json.load(open(p))
    time.sleep(0.9)
    for attempt in range(40):
        try:
            req = urllib.request.Request("https://api.upstox.com/v2/" + path,
                                         headers={"Authorization": "Bearer " + ir.TOK, "Accept": "application/json",
                                                  "User-Agent": ir.UA})
            with urllib.request.urlopen(req, timeout=30) as r:
                data = json.load(r)["data"]
            break
        except urllib.error.HTTPError as ex:
            if ex.code in (400, 404):
                data = None
                break
            time.sleep(60 if ex.code == 429 else 5 + 5 * min(attempt, 6))
        except Exception:
            time.sleep(5 + 5 * min(attempt, 6))
    else:
        raise RuntimeError("Upstox kept failing: " + path)
    json.dump(data, open(p, "w"))
    return data


def closes(key, day):
    """[375 closes or None] of an expired contract on `day`."""
    d = v2("expired-instruments/historical-candle/%s/1minute/%s/%s" % (urllib.parse.quote(key, safe=""), day, day))
    out = [None] * 375
    for c in (d or {}).get("candles", []):
        i = int(c[0][11:13]) * 60 + int(c[0][14:16]) - 555
        if 0 <= i < 375:
            out[i] = float(c[4])
    return out


def at(series, i):
    for j in range(i, i - 6, -1):
        if j >= 0 and series[j] is not None:
            return series[j]
    return None


def charges(sold, bought):
    """Rs for one lot round trip; sold/bought = premium turnover in Rs."""
    brok = 30 * 8
    exch = 0.0003503 * (sold + bought)
    sebi = 0.000001 * (sold + bought)
    return brok + 0.0015 * sold + exch + sebi + 0.00003 * bought + 0.18 * (brok + exch + sebi)


def stats(x):
    n = len(x)
    if n < 2:
        return n, (x[0] if x else 0.0), 0.0
    m = sum(x) / n
    sd = math.sqrt(sum((v - m) ** 2 for v in x) / (n - 1))
    return n, m, (m / (sd / math.sqrt(n)) if sd > 0 else 0.0)


def main():
    os.makedirs(OC, exist_ok=True)
    os.makedirs(ir.CACHE, exist_ok=True)
    with urllib.request.urlopen("http://localhost:9090/upstox/auth/token", timeout=15) as r:
        ir.TOK = r.read().decode().strip()
    nk = urllib.parse.quote(ir.NIFTY, safe="")
    exps = [dt.date.fromisoformat(x) for x in v2("expired-instruments/expiries?instrument_key=" + nk)]
    exps = [x for x in exps if FROM <= x <= TO]
    print("expiries %d" % len(exps), file=sys.stderr, flush=True)

    rows, skip = [], defaultdict(list)
    for e in exps:
        spot = ir.minutes(ir.NIFTY, e)
        s = at(spot, I_IN) if spot else None
        if not s:
            skip["no NIFTY minutes"].append(str(e))
            continue
        cons = v2("expired-instruments/option/contract?instrument_key=%s&expiry_date=%s" % (nk, e)) or []
        ce = {c["strike_price"]: c for c in cons if c["instrument_type"] == "CE"}
        pe = {c["strike_price"]: c for c in cons if c["instrument_type"] == "PE"}
        strikes = sorted(set(ce) & set(pe))
        if not strikes:
            skip["no contracts"].append(str(e))
            continue
        k = min(strikes, key=lambda x: (abs(x - s), x))
        cw = [x for x in sorted(ce) if x >= s * (1 + WING)]
        pw = [x for x in sorted(pe) if x <= s * (1 - WING)]
        if not cw or not pw:
            skip["no wing strike"].append(str(e))
            continue
        legs = [(ce[k], -1), (pe[k], -1), (ce[cw[0]], 1), (pe[pw[-1]], 1)]
        px = []
        for c, side in legs:
            ser = closes(c["instrument_key"], e)
            a, b = at(ser, I_IN), at(ser, I_OUT)
            if a is None or b is None:
                break
            px.append((side, a, b))
        if len(px) < 4:
            skip["missing option candle"].append(str(e))
            continue
        lot = float(cons[0].get("lot_size") or legs[0][0].get("lot_size"))
        pts = sum(-side * (a - b) for side, a, b in px) - 4 * 2 * SLIP
        sold = (px[0][1] + px[1][1] + px[2][2] + px[3][2]) * lot
        bought = (px[0][2] + px[1][2] + px[2][1] + px[3][1]) * lot
        credit = px[0][1] + px[1][1] - px[2][1] - px[3][1]
        rs = pts * lot - charges(sold, bought)
        rows.append((e, rs, credit, lot, k, cw[0], pw[-1], s))
        print("  %s spot %.1f K %d wings %d/%d credit %.1f lot %d -> Rs %+.0f" % (e, s, k, cw[0], pw[-1], credit, lot, rs),
              file=sys.stderr, flush=True)

    pl = [r[1] for r in rows]
    n, mu, t = stats(pl)
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
    checks = [("expiries >= 80 (%d)" % n, n >= 80),
              ("mean > 0, t >= %.1f (Rs %+.0f/lot, t %.2f)" % (T_MIN, mu, t), mu > 0 and t >= T_MIN),
              ("year 1 > 0 (Rs %+.0f, %d)" % (stats(y1)[1], len(y1)), bool(y1) and stats(y1)[1] > 0),
              ("year 2 > 0 (Rs %+.0f, %d)" % (stats(y2)[1], len(y2)), bool(y2) and stats(y2)[1] > 0),
              ("without best month %s > 0 (Rs %+.0f)" % (best, stats(rest)[1]), bool(rest) and stats(rest)[1] > 0),
              ("worst expiry >= Rs %d (Rs %.0f)" % (WORST, worst), worst >= WORST)]
    ok = all(c for _, c in checks)
    out = ["NIFTY EXPIRY-DAY HEDGED STRADDLE STUDY (pre-registered 2026-11-21; research only; condor book unchanged)",
           "expiries %d; skipped: %s" % (len(exps), {k: len(v) for k, v in skip.items()})]
    for k_, v in skip.items():
        out.append("  %s: %s" % (k_, ", ".join(v)))
    out += ["",
            "09:30 sell ATM straddle + buy 1.5%% wings, exit 15:15: %d expiries, Rs %+.0f/lot mean, t %.2f, win %.0f%%, "
            "total Rs %+.0f/lot, max drawdown Rs %.0f, average credit %.1f pts" % (
                n, mu, t, 100 * sum(v > 0 for v in pl) / max(1, n), sum(pl), dd,
                sum(r[2] for r in rows) / max(1, n)),
            "  " + "; ".join(("ok " if c else "NO ") + l for l, c in checks), "",
            "by month (Rs/lot): " + ", ".join("%s %+.0f" % (m, by_m[m]) for m in sorted(by_m)), "",
            "VERDICT: " + ("PASS" if ok else "FAIL")]
    print("\n".join(out))


if __name__ == "__main__":
    main()
