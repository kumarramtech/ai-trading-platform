#!/usr/bin/env python3
"""
Intraday EDGE MAP (pre-registered 2026-11-17, research only).

Measures what NSE stocks do after a fixed, pre-declared list of intraday situations, with a
discovery year and a held-out year that is judged ONCE.

  Stocks     today's top 500 by turnover that have an Upstox sector in a sector with >= 4 stocks
             (the idea 3 universe); each day only stocks with prior close >= 200 and 20-day average
             turnover >= Rs 25 crore (point in time)
  Times      decision points 09:45, 10:15, ... 14:15 (every 30 minutes)
  Outcome    the stock's return from the decision-minute close to +30 min, +60 min and the 15:15 close
  Costs      Rs 2 lakh a trade: Rs 30 x 2 brokerage, STT 0.025% sell, exchange 0.00297%, SEBI, stamp
             0.003% buy, GST, slippage 0.03% a side (about 0.13% a round trip)
  Situations (buckets fixed now)
    tod      09:45 | 10:15-10:45 | 11:15-12:45 | 13:15-14:15
    gap      09:15 close vs prior close %: <=-2 | -2..-0.5 | -0.5..0.5 | 0.5..2 | >=2
    move     since the 09:15 close %: <=-3 | -3..-1 | -1..1 | 1..3 | >=3
    vsnifty  move minus NIFTY's move since 09:15 %: <=-2 | -2..-0.5 | -0.5..0.5 | 0.5..2 | >=2
    sector   average move of the sector peers since 09:15 %: <=-1.5 | -1.5..-0.5 | -0.5..0.5 | 0.5..1.5 | >=1.5
    nifty    NIFTY move since 09:15 %: <=-1 | -1..-0.3 | -0.3..0.3 | 0.3..1 | >=1
    rvol     volume so far / (20-day avg daily volume x share of the session gone): <0.7 | 0.7..1.5 | 1.5..3 | >=3
    expiry   NIFTY weekly expiry day (approx.: last session on/before Thu, Tue from Sep 2025) | other
    range    position in the day's range so far (closes): <0.2 | 0.2..0.8 | >=0.8
  Cells      every single situation bucket, and tod x every other situation bucket; 3 horizons each
  Direction  long if the cell's discovery mean is > 0, else short (chosen in discovery, then fixed)

  DISCOVERY 2024-10-01 .. 2025-09-30. A cell qualifies with >= 300 trades, >= 60 days, mean net >= +0.10%
            a trade and t >= 3.0 on daily means (strict, because many cells are tried).
  HELD-OUT  2025-10-01 .. 2026-09-30, judged once, only the top 5 qualifying cells by discovery t.
            PASS = >= 100 trades, mean net >= +0.10%, t >= 2.0 on daily means, both held-out halves > 0.
  Anything that passes -> a separate paper book with a fixed 60-session promotion test (with the user's OK).

Reads Upstox history only (and the idea 3 cache for sectors, daily candles and NIFTY); places no orders and
touches no service or strategy. Downloads cache in /tmp/edge_cache so a stopped run resumes.
Usage (server, after the close):  python3 intraday_edge_map.py /tmp/idea3_universe.tsv
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

FROM, TO = dt.date(2024, 10, 1), dt.date(2026, 9, 30)
SPLIT = dt.date(2025, 10, 1)
NIFTY = "NSE_INDEX|Nifty 50"
IDEA3 = "/tmp/idea3_cache"
CACHE = "/tmp/edge_cache"
UA = "research-client/1.0"
NOTIONAL, MIN_PRICE, MIN_TURN = 200000, 200, 25e7
TIMES = list(range(9 * 60 + 45, 14 * 60 + 16, 30))  # 09:45 .. 14:15
CUT = 15 * 60 + 15
OPEN_IDX = 0  # 09:15 candle
HORIZONS = ("+30", "+60", "15:15")
Q_TRADES, Q_DAYS, Q_MEAN, Q_T = 300, 60, 0.10, 3.0
H_TRADES, H_MEAN, H_T, TOP = 100, 0.10, 2.0, 5
TUESDAY_FROM = dt.date(2025, 9, 1)

BUCKETS = {
    "gap": [-2, -0.5, 0.5, 2],
    "move": [-3, -1, 1, 3],
    "vsnifty": [-2, -0.5, 0.5, 2],
    "sector": [-1.5, -0.5, 0.5, 1.5],
    "nifty": [-1, -0.3, 0.3, 1],
    "rvol": [0.7, 1.5, 3],
    "range": [0.2, 0.8],
}
TOK = None


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


def token():
    with urllib.request.urlopen("http://localhost:9090/upstox/auth/token", timeout=15) as r:
        return r.read().decode().strip()


def api(url):
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + TOK, "Accept": "application/json",
                                               "User-Agent": UA})
    time.sleep(0.9)  # stay under Upstox's ~2,000 historical calls per 30 minutes
    for attempt in range(40):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as ex:
            if ex.code in (400, 404):
                return None
            # 429 = rate limit: wait it out instead of skipping the month
            time.sleep(60 if ex.code == 429 else 5 + 5 * min(attempt, 6))
        except Exception:
            time.sleep(5 + 5 * min(attempt, 6))
    raise RuntimeError("Upstox kept failing for " + url.split("?")[0])


def fname(prefix, key, extra=""):
    return prefix + key.replace("|", "_").replace(" ", "_").replace("/", "_") + extra + ".json"


def month_ranges(a, b):
    d = a.replace(day=1)
    while d <= b:
        nxt = (d.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
        yield max(a, d), min(b, nxt - dt.timedelta(days=1))
        d = nxt


def minutes(key, a, b):
    """{date: ([375 closes or None], [375 volumes])} for one month, cached."""
    path = os.path.join(CACHE, fname("m_", key, "_%s" % a))
    if os.path.exists(path):
        with open(path) as f:
            raw = json.load(f)
    else:
        j = api("https://api.upstox.com/v3/historical-candle/" + urllib.parse.quote(key, safe="")
                + "/minutes/1/%s/%s" % (b, a))
        if j is None:
            return {}
        raw = {}
        for c in j["data"]["candles"]:
            h, m = int(c[0][11:13]), int(c[0][14:16])
            i = h * 60 + m - 555
            if 0 <= i < 375:
                day = raw.setdefault(c[0][:10], [[None] * 375, [0] * 375])
                day[0][i] = round(c[4], 2)
                day[1][i] = int(c[5] or 0)
        with open(path, "w") as f:
            json.dump(raw, f)
    return {dt.date.fromisoformat(d): v for d, v in raw.items()}


def bucket(name, x):
    edges = BUCKETS[name]
    for i, e in enumerate(edges):
        if x < e:
            return i
    return len(edges)


def tod(t):
    return 0 if t == TIMES[0] else 1 if t < 11 * 60 else 2 if t < 13 * 60 else 3


def cost_pct(price, direction, exit_price):
    qty = max(1, int(NOTIONAL // price))
    buy_px, sell_px = (price, exit_price) if direction > 0 else (exit_price, price)
    buy, sell = buy_px * qty, sell_px * qty
    turnover = buy + sell
    exch, sebi = turnover * 0.00297 / 100, turnover * 10 / 1e7
    rs = 60 + sell * 0.025 / 100 + exch + sebi + buy * 0.003 / 100 + 0.18 * (60 + exch + sebi) + turnover * 0.03 / 100
    return rs / (price * qty) * 100


def expiry_days(days):
    out, weeks = set(), defaultdict(list)
    for d in days:
        weeks[d.isocalendar()[:2]].append(d)
    for ds in weeks.values():
        dow = 1 if ds[0] >= TUESDAY_FROM else 3
        c = [d for d in ds if d.weekday() <= dow]
        if c:
            out.add(max(c))
    return out


def load_idea3_daily(key):
    with open(os.path.join(IDEA3, fname("daily_", key))) as f:
        return {dt.date.fromisoformat(d): (c, v) for d, c, v in json.load(f)}


def idea3_nifty():
    out = {}
    for a, _ in month_ranges(FROM, TO):
        p = os.path.join(IDEA3, fname("min_", NIFTY, "_%s" % a))
        with open(p) as f:
            for d, arr in json.load(f).items():
                out[dt.date.fromisoformat(d)] = arr
    return out


def main():
    global TOK
    os.makedirs(CACHE, exist_ok=True)
    TOK = token()
    uni = [l.rstrip("\n").split("\t") for l in open(sys.argv[1]) if l.strip()]
    sectors = {}
    for sym, isin, key in uni:
        p = os.path.join(IDEA3, "sector_%s.json" % isin)
        if os.path.exists(p):
            s = json.load(open(p)).get("sector")
            if s:
                sectors[key] = s
    groups = defaultdict(list)
    for k, s in sectors.items():
        groups[s].append(k)
    groups = {s: ks for s, ks in groups.items() if len(ks) >= 4}
    stocks = sorted(k for ks in groups.values() for k in ks)
    sector_of = {k: s for s, ks in groups.items() for k in ks}
    log("stocks", len(stocks), "sectors", len(groups))

    nifty = idea3_nifty()
    months = list(month_ranges(FROM, TO))

    # point-in-time filters
    pit = {}
    for k in stocks:
        dd = load_idea3_daily(k)
        ds = sorted(dd)
        m = {}
        for i, d in enumerate(ds):
            if i >= 20 and FROM <= d <= TO:
                prev = ds[i - 20:i]
                m[d] = (dd[ds[i - 1]][0], sum(dd[x][0] * dd[x][1] for x in prev) / 20,
                        sum(dd[x][1] for x in prev) / 20)
        pit[k] = m

    # pass 1: download, and keep each stock's move since 09:15 at each decision time (for sector averages)
    moves = {}
    for i, k in enumerate(stocks):
        mv = {}
        for a, b in months:
            for d, (cl, _) in minutes(k, a, b).items():
                o = cl[OPEN_IDX]
                if o:
                    mv[d] = [((cl[t - 555] / o - 1) * 100) if cl[t - 555] else None for t in TIMES]
        moves[k] = mv
        if (i + 1) % 20 == 0:
            log("downloaded", i + 1, "/", len(stocks))
    days_all = sorted({d for mv in moves.values() for d in mv})
    exp = expiry_days(days_all)
    log("sessions", len(days_all))

    # pass 2: observations -> cells
    feats = ["gap", "move", "vsnifty", "sector", "nifty", "rvol", "expiry", "range"]
    agg = defaultdict(lambda: defaultdict(lambda: [0.0, 0.0, 0, 0.0]))  # (cell, h, period) -> day -> [sum, sumcost, n, ...]
    n_obs = 0
    for i, k in enumerate(stocks):
        peers = [p for p in groups[sector_of[k]] if p != k]
        data = {}
        for a, b in months:
            data.update(minutes(k, a, b))
        for d, (cl, vol) in data.items():
            if d not in pit[k] or d not in nifty:
                continue
            prior, turn, avgvol = pit[k][d]
            o, nm = cl[OPEN_IDX], nifty[d]
            if prior < MIN_PRICE or turn < MIN_TURN or not o or not nm[OPEN_IDX] or avgvol <= 0:
                continue
            period = 0 if d < SPLIT else 1
            gap = (o / prior - 1) * 100
            cumvol, lo, hi = 0, o, o
            idx_prev = 0
            for j, t in enumerate(TIMES):
                ti = t - 555
                for x in range(idx_prev, ti + 1):
                    cumvol += vol[x] or 0
                    if cl[x]:
                        lo, hi = min(lo, cl[x]), max(hi, cl[x])
                idx_prev = ti + 1
                c = cl[ti]
                if not c or not nm[ti]:
                    continue
                exits = {"+30": min(ti + 30, CUT - 555), "+60": min(ti + 60, CUT - 555), "15:15": CUT - 555}
                if any(not cl[e] for e in exits.values()):
                    continue
                mv = (c / o - 1) * 100
                nmv = (nm[ti] / nm[OPEN_IDX] - 1) * 100
                pm = [moves[p][d][j] for p in peers if d in moves[p] and moves[p][d][j] is not None]
                if not pm:
                    continue
                elapsed = (ti + 1) / 375
                f = {"gap": bucket("gap", gap), "move": bucket("move", mv), "vsnifty": bucket("vsnifty", mv - nmv),
                     "sector": bucket("sector", sum(pm) / len(pm)), "nifty": bucket("nifty", nmv),
                     "rvol": bucket("rvol", cumvol / (avgvol * elapsed)), "expiry": 1 if d in exp else 0,
                     "range": bucket("range", (c - lo) / (hi - lo) if hi > lo else 0.5)}
                td = tod(t)
                cells = [("tod", td)] + [(fn, f[fn]) for fn in feats] + [("tod*" + fn, (td, f[fn])) for fn in feats]
                for h, e in exits.items():
                    r = (cl[e] / c - 1) * 100
                    cst = cost_pct(c, 1, cl[e])
                    for cell in cells:
                        a_ = agg[(cell, h, period)][d]
                        a_[0] += r
                        a_[1] += cst
                        a_[2] += 1
                n_obs += 1
        if (i + 1) % 20 == 0:
            log("measured", i + 1, "/", len(stocks), "obs", n_obs)

    def summary(cell, h, period, direction=None):
        days = agg.get((cell, h, period), {})
        n = sum(v[2] for v in days.values())
        if n == 0:
            return None
        gross = sum(v[0] for v in days.values()) / n
        dirn = direction if direction is not None else (1 if gross > 0 else -1)
        nets = {d: (dirn * v[0] - v[1]) / v[2] for d, v in days.items()}
        mean = sum(dirn * v[0] - v[1] for v in days.values()) / n
        dm = [nets[d] for d in sorted(nets)]
        k = len(dm)
        mu = sum(dm) / k
        sd = math.sqrt(sum((x - mu) ** 2 for x in dm) / (k - 1)) if k > 1 else 0
        t = mu / (sd / math.sqrt(k)) if sd > 0 else 0
        half = sorted(nets)[k // 2] if k else None
        h1 = [dirn * v[0] - v[1] for d, v in days.items() if d < half]
        h1n = sum(v[2] for d, v in days.items() if d < half)
        h2n = n - h1n
        h2 = sum(dirn * v[0] - v[1] for d, v in days.items() if d >= half)
        return dict(cell=cell, h=h, dir=dirn, n=n, days=k, gross=gross, mean=mean, t=t,
                    h1=(sum(h1) / h1n) if h1n else float("nan"), h2=(h2 / h2n) if h2n else float("nan"))

    keys = sorted({(c, h) for (c, h, p) in agg if p == 0}, key=str)
    disc = [s for s in (summary(c, h, 0) for c, h in keys) if s]
    qual = sorted([s for s in disc if s["n"] >= Q_TRADES and s["days"] >= Q_DAYS and s["mean"] >= Q_MEAN
                   and s["t"] >= Q_T], key=lambda s: -s["t"])
    best = sorted(disc, key=lambda s: -s["t"])[:10]

    def name(s):
        return "%s=%s %s %s" % (s["cell"][0], s["cell"][1], s["h"], "LONG" if s["dir"] > 0 else "SHORT")

    out = ["INTRADAY EDGE MAP (pre-registered 2026-11-17; research only)",
           "Stocks %d in %d sectors, %d sessions %s..%s, %d observations, %d cells x 3 horizons tried in discovery"
           % (len(stocks), len(groups), len(days_all), days_all[0], days_all[-1], n_obs, len(keys) // 3),
           "Discovery %s..%s: qualify = >= %d trades, >= %d days, mean net >= +%.2f%%, t >= %.1f. Qualified: %d"
           % (FROM, SPLIT - dt.timedelta(days=1), Q_TRADES, Q_DAYS, Q_MEAN, Q_T, len(qual)),
           "", "Top 10 discovery cells by t (for context):",
           "%-52s %7s %5s %8s %8s %6s" % ("cell", "trades", "days", "gross %", "net %", "t")]
    for s in best:
        out.append("%-52s %7d %5d %8.3f %8.3f %6.2f" % (name(s), s["n"], s["days"], s["gross"], s["mean"], s["t"]))
    out += ["", "HELD-OUT %s..%s, top %d qualified cells, judged once: PASS = >= %d trades, net >= +%.2f%%, t >= %.1f, both halves > 0"
            % (SPLIT, TO, TOP, H_TRADES, H_MEAN, H_T),
            "%-52s %7s %5s %8s %6s %8s %8s  %s" % ("cell", "trades", "days", "net %", "t", "half 1", "half 2", "verdict")]
    passed = []
    for s in qual[:TOP]:
        hs = summary(s["cell"], s["h"], 1, s["dir"])
        if not hs:
            out.append("%-52s  no held-out data  FAIL" % name(s))
            continue
        ok = hs["n"] >= H_TRADES and hs["mean"] >= H_MEAN and hs["t"] >= H_T and hs["h1"] > 0 and hs["h2"] > 0
        passed += [name(s)] if ok else []
        out.append("%-52s %7d %5d %8.3f %6.2f %8.3f %8.3f  %s" % (name(s), hs["n"], hs["days"], hs["mean"], hs["t"],
                                                                hs["h1"], hs["h2"], "PASS" if ok else "FAIL"))
    if not qual:
        out.append("(no cell qualified in discovery)")
    out.append("")
    out.append("DECISION  " + ("PASS: " + "; ".join(passed) + " -> separate paper book with a 60-session promotion test (user's OK)"
                               if passed else "NOTHING passes the held-out year: no edge found in these situations (no re-tuning)."))
    print("\n".join(out))


if __name__ == "__main__":
    main()
