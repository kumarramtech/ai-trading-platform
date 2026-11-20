#!/usr/bin/env python3
"""
DELIVERY-SPIKE study (rules fixed and committed 2026-11-20 before any result; autonomous mode, the user asked to keep
testing intraday ideas without asking; research only; no existing strategy is touched).

Idea      a big price move on unusually high DELIVERY volume (shares taken home, not day-traded) is real buying /
          selling by holders, which may continue the next day.
Data      NSE sec_bhavdata_full_DDMMYYYY.csv (EQ series: CLOSE_PRICE, PREV_CLOSE, TURNOVER_LACS, DELIV_QTY, DELIV_PER),
          signal days 2024-10-01 .. 2026-09-29. ISIN for the minute data from the CM bhavcopy cache (/tmp/cm_bhav2).
Signal    end of day S: px = CLOSE/PREV_CLOSE - 1 (|px| > 20% dropped as a corporate action / error);
          dr = DELIV_QTY(S) / average DELIV_QTY over the 20 sessions before S (needs >= 15 of them);
          DU (delivery up):   px >= +3%, dr >= 3, DELIV_PER(S) >= 50  -> BUY  on the next session T
          DD (delivery down): px <= -3%, dr >= 3, DELIV_PER(S) >= 50  -> SHORT on the next session T
          At most 10 a day per test, the largest dr first.
Eligible  CLOSE(S) >= Rs 100 and 20-day average turnover >= Rs 10 crore; same-day NSE index / F&O-list events out.
Trade     T: 09:20 close -> 15:25 close minus NIFTY over the same minutes; cost 0.13%.
Pass      each test: >= 100 trades; mean net > 0 with t >= 2.5; both halves > 0; > 0 without the best month;
          beats the placebo (the same stock and trade 10 trading days earlier) by >= the cost, gross.
Usage (server, after the close): python3 delivery_study.py index_events.tsv fo_inclusion_events.tsv
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

DL = "/tmp/deliv"
CM = "/tmp/cm_bhav2"
FROM, TO = dt.date(2024, 8, 20), dt.date(2026, 9, 30)
SIG_FROM, SIG_TO = dt.date(2024, 10, 1), dt.date(2026, 9, 29)
T_MIN = 2.5
PX, DR, DP, CAP = 3.0, 3.0, 50.0, 10


def deliv_day(day):
    """{symbol: [close, prev_close, turnover_rs, deliv_qty, deliv_per]} for EQ, or None (holiday)."""
    p = os.path.join(DL, day.isoformat() + ".json")
    if os.path.exists(p):
        return json.load(open(p))
    data = sb.http("https://nsearchives.nseindia.com/products/content/sec_bhavdata_full_%s.csv" % day.strftime("%d%m%Y"))
    time.sleep(0.4)
    out = None
    if data:
        out = {}
        lines = data.decode("utf-8", "replace").splitlines()
        head = [h.strip() for h in lines[0].split(",")]
        ix = {h: i for i, h in enumerate(head)}
        for l in lines[1:]:
            r = [x.strip() for x in l.split(",")]
            if len(r) < len(head) or r[ix["SERIES"]] != "EQ":
                continue
            try:
                dq = float(r[ix["DELIV_QTY"]]) if r[ix["DELIV_QTY"]] not in ("-", "") else None
                dpc = float(r[ix["DELIV_PER"]]) if r[ix["DELIV_PER"]] not in ("-", "") else None
                out[r[ix["SYMBOL"]]] = [float(r[ix["CLOSE_PRICE"]]), float(r[ix["PREV_CLOSE"]]),
                                        float(r[ix["TURNOVER_LACS"]]) * 1e5, dq, dpc]
            except ValueError:
                continue
    json.dump(out, open(p, "w"))
    return out


def intraday(key, day):
    s, n = ir.minutes(key, day), ir.minutes(ir.NIFTY, day)
    if s and n and s[5] and s[370] and n[5] and n[370]:
        return ((s[370] / s[5] - 1) - (n[370] / n[5] - 1)) * 100
    return None


def isin_of(sym, day, cal_cm):
    for d in cal_cm:
        if d >= day:
            v = sb.day_file(d)
            if v and sym in v:
                return v[sym][0]
            break
    return None


def main():
    os.makedirs(DL, exist_ok=True)
    os.makedirs(ir.CACHE, exist_ok=True)
    sb.CACHE = CM
    with urllib.request.urlopen("http://localhost:9090/upstox/auth/token", timeout=15) as r:
        ir.TOK = r.read().decode().strip()
    same_day = {(l.split("\t")[4], l.split("\t")[2]) for l in list(open(sys.argv[1]))[1:] if l.strip()}
    same_day |= {(l.split("\t")[4], l.split("\t")[2]) for l in list(open(sys.argv[2]))[1:] if l.strip()}

    cal, dd = [], {}
    d = FROM
    while d <= TO:
        if d.weekday() < 5:
            v = deliv_day(d)
            if v:
                cal.append(d)
                dd[d] = v
        d += dt.timedelta(days=1)
    print("sessions %d" % len(cal), file=sys.stderr, flush=True)

    signals = []  # (test, sym, S, T, dr)
    for i in range(20, len(cal) - 1):
        s_day, t_day = cal[i], cal[i + 1]
        if s_day < SIG_FROM or s_day > SIG_TO:
            continue
        cand = {"DU": [], "DD": []}
        for sym, (c, pc, tv, dq, dpc) in dd[s_day].items():
            if not dq or dpc is None or pc <= 0:
                continue
            hist = [dd[cal[j]][sym][3] for j in range(i - 20, i) if sym in dd[cal[j]] and dd[cal[j]][sym][3]]
            if len(hist) < 15:
                continue
            px = (c / pc - 1) * 100
            dr = dq / (sum(hist) / len(hist))
            if abs(px) > 20 or dr < DR or dpc < DP:
                continue
            if px >= PX:
                cand["DU"].append((dr, sym))
            elif px <= -PX:
                cand["DD"].append((dr, sym))
        for test, lst in cand.items():
            for dr, sym in sorted(lst, reverse=True)[:CAP]:
                signals.append((test, sym, s_day, t_day, dr))
    print("signals: %s" % {t: sum(1 for x in signals if x[0] == t) for t in ("DU", "DD")}, file=sys.stderr, flush=True)

    cm_cal = sorted(dt.date.fromisoformat(f[:10]) for f in os.listdir(CM))
    tests = {t: ([], []) for t in ("DU", "DD")}
    skip = defaultdict(int)
    for k, (test, sym, s_day, t_day, dr) in enumerate(signals):
        if (t_day.isoformat(), sym) in same_day:
            skip["index / F&O event same day"] += 1
            continue
        i = cal.index(s_day)
        turn = sum(dd[cal[j]].get(sym, [0, 0, 0])[2] for j in range(i - 19, i + 1)) / 20
        if dd[s_day][sym][0] < ir.MIN_PRICE or turn < ir.MIN_TURN:
            skip["ineligible"] += 1
            continue
        isin = isin_of(sym, t_day, cm_cal) or isin_of(sym, s_day, cm_cal)
        if not isin:
            skip["no isin"] += 1
            continue
        key = "NSE_EQ|" + isin
        g = intraday(key, t_day)
        if g is None:
            skip["no minute data"] += 1
            continue
        sg = 1 if test == "DU" else -1
        month = t_day.strftime("%Y-%m")
        tests[test][0].append((month, sg * g))
        ti = cal.index(t_day)
        if ti >= 10:
            pg = intraday(key, cal[ti - 10])
            if pg is not None:
                tests[test][1].append((month, sg * pg))
        if k % 200 == 0:
            print("  %d/%d" % (k, len(signals)), file=sys.stderr, flush=True)

    out = ["DELIVERY-SPIKE STUDY (pre-registered 2026-11-20; t >= %.1f; research only)" % T_MIN,
           "signals %d; left out: %s" % (len(signals), dict(skip)), ""]
    res = {}
    for t, label in (("DU", "up move on delivery spike: BUY next day"), ("DD", "down move on delivery spike: SHORT next day")):
        trades, placebo = tests[t]
        block = []
        v = ir.verdict("%s %s, 09:20 -> 15:25 vs NIFTY" % (t, label), trades, placebo, ir.COST_B, block)
        n, mu, tt = ir.stats([g - ir.COST_B for _, g in trades])
        if v == "PASS" and (tt < T_MIN or n < 100):
            v = "FAIL"
            block.append("  [fail] needs t >= %.1f and >= 100 trades (t %.2f, n %d) -> FAIL" % (T_MIN, tt, n))
        elif v != "NOT ENOUGH DATA":
            block.append("  (t check here is %.1f: t %.2f %s)" % (T_MIN, tt, "ok" if tt >= T_MIN else "below"))
        res[t] = v
        out += block + [""]
    out.append("VERDICT: " + ", ".join("%s %s" % kv for kv in res.items()))
    print("\n".join(out))


if __name__ == "__main__":
    main()
