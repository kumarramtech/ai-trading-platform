#!/usr/bin/env python3
"""
F&O OPEN-INTEREST BUILDUP study (rules fixed and committed 2026-11-20 before any result; the user asked to keep
testing intraday ideas without asking; research only).

Universe  stocks with stock futures (STF) in the NSE F&O UDiFF bhavcopy, signal days 2024-10-01 .. 2026-09-29.
Signal    end of day S (all public after the close): OI = total stock-futures open interest over all expiries;
          dOI = OI(S) / OI(S-1) - 1; price = CM close(S) / CM close(S-1) - 1 (EQ series).
          Days S that are a stock-futures expiry, or the day after one, are skipped (an expiring contract makes OI
          jump), and so are stocks in the F&O ban on day T.
          LB (long buildup):  price >= +2% and dOI >= +10%  -> BUY  on the next session T
          SB (short buildup): price <= -2% and dOI >= +10%  -> SHORT on the next session T
          At most 10 a day per test, the largest dOI first.
Trade     T: 09:20 close -> 15:25 close, minus NIFTY over the same minutes; cost 0.13%. Price >= Rs 100 and 20-day
          traded value >= Rs 10 crore (CM bhavcopy), else left out. Same-day index / F&O-list events left out.
Pass      each test: >= 100 trades; mean net > 0 with t >= 2.5; both halves > 0; > 0 without the best month;
          beats the placebo (the same stock and trade 10 trading days earlier) by >= the cost, gross.
Usage (server, after the close): python3 fo_oi_buildup_study.py index_events.tsv fo_inclusion_events.tsv
"""
import csv
import datetime as dt
import io
import json
import os
import sys
import time
import urllib.request
import zipfile
from collections import defaultdict

import index_rebalance as ir
import split_bonus_events as sb

FO = "/tmp/fo_oi"
CM = "/tmp/cm_bhav2"
FROM, TO = dt.date(2024, 9, 20), dt.date(2026, 9, 30)
SIG_FROM, SIG_TO = dt.date(2024, 10, 1), dt.date(2026, 9, 29)
T_MIN = 2.5
PX, OI, CAP = 2.0, 10.0, 10


def fo_day(day):
    """[{symbol: total STF OI}, sorted expiries] or None."""
    p = os.path.join(FO, day.isoformat() + ".json")
    if os.path.exists(p):
        return json.load(open(p))
    data = sb.http("https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_%s_F_0000.csv.zip" % day.strftime("%Y%m%d"))
    time.sleep(0.4)
    out = None
    if data:
        z = zipfile.ZipFile(io.BytesIO(data))
        rd = csv.reader(io.StringIO(z.read(z.namelist()[0]).decode("utf-8", "replace")))
        head = [h.strip() for h in next(rd)]
        ti, si, xi, oi = (head.index(c) for c in ("FinInstrmTp", "TckrSymb", "XpryDt", "OpnIntrst"))
        tot, exps = defaultdict(float), set()
        for r in rd:
            if len(r) > max(ti, si, xi, oi) and r[ti].strip() == "STF":
                tot[r[si].strip()] += float(r[oi] or 0)
                exps.add(r[xi].strip())
        out = [dict(tot), sorted(exps)]
    json.dump(out, open(p, "w"))
    return out


def ban(day):
    data = sb.http("https://nsearchives.nseindia.com/archives/fo/sec_ban/fo_secban_%s.csv" % day.strftime("%d%m%Y"))
    time.sleep(0.3)
    if not data:
        return set()
    lines = data.decode("utf-8", "replace").replace("NIL", "").splitlines()[1:]
    return {l.split(",")[-1].strip() for l in lines if l.strip() and "," in l}


def intraday(key, day):
    s, n = ir.minutes(key, day), ir.minutes(ir.NIFTY, day)
    if s and n and s[5] and s[370] and n[5] and n[370]:
        return ((s[370] / s[5] - 1) - (n[370] / n[5] - 1)) * 100
    return None


def main():
    os.makedirs(FO, exist_ok=True)
    os.makedirs(ir.CACHE, exist_ok=True)
    sb.CACHE = CM
    with urllib.request.urlopen("http://localhost:9090/upstox/auth/token", timeout=15) as r:
        ir.TOK = r.read().decode().strip()
    same_day = {(l.split("\t")[4], l.split("\t")[2]) for l in list(open(sys.argv[1]))[1:] if l.strip()}
    same_day |= {(l.split("\t")[4], l.split("\t")[2]) for l in list(open(sys.argv[2]))[1:] if l.strip()}

    cal, fo, cm = [], {}, {}
    d = FROM
    while d <= TO:
        if d.weekday() < 5:
            f = fo_day(d)
            c = sb.day_file(d)
            if f and c:
                cal.append(d)
                fo[d], cm[d] = f, c
        d += dt.timedelta(days=1)
    print("sessions %d" % len(cal), file=sys.stderr, flush=True)
    expiry_days = {dt.date.fromisoformat(min(x for x in fo[d][1] if x >= d.isoformat())) for d in cal
                   if any(x >= d.isoformat() for x in fo[d][1])}

    signals = []  # (test, sym, S, T, dOI)
    for i in range(1, len(cal) - 1):
        s_day, prev, t_day = cal[i], cal[i - 1], cal[i + 1]
        if s_day < SIG_FROM or s_day > SIG_TO or s_day in expiry_days or prev in expiry_days:
            continue
        cand = {"LB": [], "SB": []}
        for sym, oi_now in fo[s_day][0].items():
            oi_prev = fo[prev][0].get(sym, 0)
            a, b = cm[s_day].get(sym), cm[prev].get(sym)
            if oi_prev <= 0 or not a or not b or b[1] <= 0:
                continue
            doi = (oi_now / oi_prev - 1) * 100
            px = (a[1] / b[1] - 1) * 100
            if doi >= OI and abs(px) <= 20:
                if px >= PX:
                    cand["LB"].append((doi, sym))
                elif px <= -PX:
                    cand["SB"].append((doi, sym))
        banned = None
        for test, lst in cand.items():
            for doi, sym in sorted(lst, reverse=True)[:CAP]:
                if banned is None:
                    banned = ban(t_day)
                if sym in banned:
                    continue
                signals.append((test, sym, s_day, t_day, doi))
    print("signals: %s" % {t: sum(1 for x in signals if x[0] == t) for t in ("LB", "SB")}, file=sys.stderr, flush=True)

    nifty = ir.daily(ir.NIFTY)
    tests = {t: ([], []) for t in ("LB", "SB")}
    skip = defaultdict(int)
    for k, (test, sym, s_day, t_day, doi) in enumerate(signals):
        if (t_day.isoformat(), sym) in same_day:
            skip["index / F&O event same day"] += 1
            continue
        i = cal.index(s_day)
        turn = sum(cm[cal[j]].get(sym, [0, 0, 0, 0])[3] for j in range(i - 19, i + 1)) / 20
        if cm[s_day][sym][1] < ir.MIN_PRICE or turn < ir.MIN_TURN:
            skip["ineligible"] += 1
            continue
        key = "NSE_EQ|" + cm[t_day].get(sym, cm[s_day][sym])[0]
        g = intraday(key, t_day)
        if g is None:
            skip["no minute data"] += 1
            continue
        sg = 1 if test == "LB" else -1
        month = t_day.strftime("%Y-%m")
        tests[test][0].append((month, sg * g))
        ti = cal.index(t_day)
        if ti >= 10:
            pg = intraday(key, cal[ti - 10])
            if pg is not None:
                tests[test][1].append((month, sg * pg))
        if k % 200 == 0:
            print("  %d/%d" % (k, len(signals)), file=sys.stderr, flush=True)

    out = ["F&O OI BUILDUP STUDY (pre-registered 2026-11-20; t >= %.1f; research only)" % T_MIN,
           "signals %d; left out: %s" % (len(signals), dict(skip)), ""]
    res = {}
    for t, label in (("LB", "long buildup: BUY next day"), ("SB", "short buildup: SHORT next day")):
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
