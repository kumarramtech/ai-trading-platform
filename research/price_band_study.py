#!/usr/bin/env python3
"""
PRICE-BAND CHANGE study (rules fixed and committed 2026-11-21 before any result; autonomous mode, the user asked to
keep testing intraday ideas without asking; research only; no existing strategy is touched).

Idea      NSE tightens a stock's circuit band (e.g. 20% -> 10% / 5%) as a surveillance step; traders and leveraged
          holders are pushed out on the day it takes effect (a forced flow, like the F&O exclusion that passed).
Events    NSE eq_band_changes_DDMMYYYY.csv for every weekday 2024-10-01 .. 2026-09-29, EQ series only. The file of
          day D is published after the close and applies from the NEXT session T (the trade day).
          B1 band CUT    (To < From):  SHORT on T
          B2 band RAISED (To > From):  BUY on T
          A stock with both a cut and a raise within 5 sessions is left out (data noise).
Eligible  CM close on D >= Rs 100 and 20-day average traded value >= Rs 10 crore up to D (NSE CM bhavcopy cache);
          same-day NSE index / F&O-list events on T left out.
Trade     T: 09:20 close -> 15:25 close minus NIFTY over the same minutes; cost 0.13%. A stock that is upper/lower
          circuit-locked has no fill in reality, so a trade whose 09:15..09:25 one-minute closes are all identical
          is treated as locked and left out.
Pass      each test: >= 40 trades; mean net > 0 with t >= 2.5; both halves > 0; > 0 without the best month; beats
          the placebo (the same stock and trade 10 trading days earlier) by >= the cost, gross.
Usage (server, after the close): python3 price_band_study.py index_events.tsv fo_inclusion_events.tsv
"""
import datetime as dt
import json
import os
import sys
import time
import urllib.request
from collections import defaultdict

import index_rebalance as ir
import split_bonus_events as sb

BD = "/tmp/band"
CM = "/tmp/cm_bhav2"
FROM, TO = dt.date(2024, 10, 1), dt.date(2026, 9, 29)
T_MIN = 2.5


def band_day(day):
    p = os.path.join(BD, day.isoformat() + ".json")
    if os.path.exists(p):
        return json.load(open(p))
    data = sb.http("https://nsearchives.nseindia.com/content/equities/eq_band_changes_%s.csv" % day.strftime("%d%m%Y"))
    time.sleep(0.4)
    out = []
    if data:
        lines = data.decode("utf-8", "replace").splitlines()[1:]
        for l in lines:
            r = [x.strip() for x in l.split(",")]
            if len(r) >= 6 and r[2] == "EQ":
                try:
                    out.append([r[1], float(r[-2]), float(r[-1])])
                except ValueError:
                    pass
    json.dump(out, open(p, "w"))
    return out


def intraday(key, day):
    s, n = ir.minutes(key, day), ir.minutes(ir.NIFTY, day)
    if s and n and s[5] and s[370] and n[5] and n[370]:
        return ((s[370] / s[5] - 1) - (n[370] / n[5] - 1)) * 100
    return None


def main():
    os.makedirs(BD, exist_ok=True)
    os.makedirs(ir.CACHE, exist_ok=True)
    sb.CACHE = CM
    with urllib.request.urlopen("http://localhost:9090/upstox/auth/token", timeout=15) as r:
        ir.TOK = r.read().decode().strip()
    same_day = {(l.split("\t")[4], l.split("\t")[2]) for l in list(open(sys.argv[1]))[1:] if l.strip()}
    same_day |= {(l.split("\t")[4], l.split("\t")[2]) for l in list(open(sys.argv[2]))[1:] if l.strip()}

    cal, cm = [], {}
    for f in sorted(os.listdir(CM)):
        d = dt.date.fromisoformat(f[:10])
        v = json.load(open(os.path.join(CM, f)))
        if v:
            cal.append(d)
            cm[d] = v
    events = []  # (test, sym, D, T)
    for i, d in enumerate(cal[:-1]):
        if d < FROM or d > TO or i < 20:
            continue
        for sym, a, b in band_day(d):
            if b < a:
                events.append(("B1", sym, d, cal[i + 1]))
            elif b > a:
                events.append(("B2", sym, d, cal[i + 1]))
    noisy = set()
    by_sym = defaultdict(list)
    for e in events:
        by_sym[e[1]].append(e)
    for sym, lst in by_sym.items():
        for x in lst:
            for y in lst:
                if x[0] != y[0] and abs(cal.index(x[2]) - cal.index(y[2])) <= 5:
                    noisy.add((x[0], sym, x[2]))
    print("events %s" % {t: sum(1 for e in events if e[0] == t) for t in ("B1", "B2")}, file=sys.stderr, flush=True)

    tests = {t: ([], []) for t in ("B1", "B2")}
    skip = defaultdict(int)
    for k, (test, sym, d, t_day) in enumerate(events):
        if (test, sym, d) in noisy:
            skip["cut and raise within 5 sessions"] += 1
            continue
        if (t_day.isoformat(), sym) in same_day:
            skip["index / F&O event same day"] += 1
            continue
        i = cal.index(d)
        if sym not in cm[d]:
            skip["not in CM EQ"] += 1
            continue
        turn = sum(cm[cal[j]].get(sym, [0, 0, 0, 0])[3] for j in range(i - 19, i + 1)) / 20
        if cm[d][sym][1] < ir.MIN_PRICE or turn < ir.MIN_TURN:
            skip["ineligible"] += 1
            continue
        key = "NSE_EQ|" + cm[d][sym][0]
        m = ir.minutes(key, t_day)
        if m and all(m[x] is not None for x in range(11)) and len({m[x] for x in range(11)}) == 1:
            skip["circuit-locked at 09:20"] += 1
            continue
        g = intraday(key, t_day)
        if g is None:
            skip["no minute data"] += 1
            continue
        sg = -1 if test == "B1" else 1
        month = t_day.strftime("%Y-%m")
        tests[test][0].append((month, sg * g))
        ti = cal.index(t_day)
        pg = intraday(key, cal[ti - 10])
        if pg is not None:
            tests[test][1].append((month, sg * pg))
        if k % 100 == 0:
            print("  %d/%d" % (k, len(events)), file=sys.stderr, flush=True)

    out = ["PRICE-BAND CHANGE STUDY (pre-registered 2026-11-21; t >= %.1f; research only)" % T_MIN,
           "events %d; left out: %s" % (len(events), dict(skip)), ""]
    res = {}
    for t, label in (("B1", "band CUT: SHORT on the effective day"), ("B2", "band RAISED: BUY on the effective day")):
        trades, placebo = tests[t]
        block = []
        v = ir.verdict("%s %s, 09:20 -> 15:25 vs NIFTY" % (t, label), trades, placebo, ir.COST_B, block)
        n, mu, tt = ir.stats([g - ir.COST_B for _, g in trades])
        if v == "PASS" and tt < T_MIN:
            v = "FAIL"
            block.append("  [fail] t %.2f below the stricter %.1f -> FAIL" % (tt, T_MIN))
        elif v != "NOT ENOUGH DATA":
            block.append("  (t check here is %.1f: t %.2f %s)" % (T_MIN, tt, "ok" if tt >= T_MIN else "below"))
        res[t] = v
        out += block + [""]
    out.append("VERDICT: " + ", ".join("%s %s" % kv for kv in res.items()))
    print("\n".join(out))


if __name__ == "__main__":
    main()
