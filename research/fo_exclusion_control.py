#!/usr/bin/env python3
"""
Information only (after the F2 pass, 2026-11-19): on each F2 day, the average 09:20 -> 15:25 move vs NIFTY of
the OTHER stocks that kept their futures, from cached minute data only (no new downloads). If they fell as much,
F2 is an expiry-day effect, not an exclusion effect. Not part of the pre-registered test.
"""
import datetime as dt, glob, json, os, sys
from collections import defaultdict
import index_rebalance as ir

rows = [l.rstrip("\n").split("\t") for l in open(sys.argv[1]) if l.strip()][1:]
f2 = defaultdict(set)
for idx, t, sym, ann, day, src in rows:
    if t == "F2":
        f2[day].add(sym)
univ = "/tmp/fo_univ"
isin = {}
for p in sorted(glob.glob(os.path.join(ir.CACHE, "bhav_*.json")))[-40:]:
    isin.update(json.load(open(p)))
n_all = []
for day in sorted(f2):
    d = dt.date.fromisoformat(day)
    others = set(json.load(open(os.path.join(univ, day + ".json")))) - f2[day]
    nf = ir.minutes(ir.NIFTY, d)
    xs = []
    for s in others:
        k = isin.get(s)
        if not k:
            continue
        key = "NSE_EQ|" + k
        kf = key.replace("|", "_").replace(" ", "_")
        a = d.replace(day=1)
        if not (glob.glob(os.path.join(ir.EDGE, "m_%s_*.json" % kf)) or glob.glob(os.path.join(ir.CACHE, "m_%s_*.json" % kf))):
            continue
        try:
            m = ir.minutes(key, d) if (os.path.exists(os.path.join(ir.CACHE, "m_%s_%s.json" % (key.replace("|","_").replace(" ","_"), a))) or glob.glob(os.path.join(ir.EDGE, "m_%s_%s*.json" % (kf, a.strftime("%Y-%m"))))) else None
        except Exception:
            m = None
        if m and nf and m[5] and m[370] and nf[5] and nf[370]:
            xs.append(((m[370] / m[5] - 1) - (nf[370] / nf[5] - 1)) * 100)
    n, mu, t = ir.stats(xs)
    n_all += xs
    print("%s  removed %2d  other F&O stocks with cached data %3d: mean vs NIFTY %+.3f%% (t %.2f)" % (day, len(f2[day]), n, mu, t))
n, mu, t = ir.stats(n_all)
print("ALL F2 days, other F&O stocks: n %d, mean %+.3f%% vs NIFTY, t %.2f  (F2 removed stocks: -1.98%% gross)" % (n, mu, t))
