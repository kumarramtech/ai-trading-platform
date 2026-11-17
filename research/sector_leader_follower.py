#!/usr/bin/env python3
"""
Idea 3: sector leader -> laggard followers (pre-registered 2026-11-17, research only).

When a sector's most traded stock makes a sharp move of its own, do the sector's laggards catch up?
Rules (fixed with the user before any data was looked at):
  Data      1-minute candles 2024-10-01 .. 2026-09-30; stocks = today's top 500 by turnover; sector from
            the Upstox fundamentals profile; only sectors with >= 4 stocks
  Leader    the sector's highest 20-day average turnover stock (prior 20 sessions, point in time)
  Followers the other stocks with prior close >= 200 and 20-day average turnover >= Rs 25 crore
  Trigger   09:45-14:00, first per sector per day: leader's 15-minute return >= +1.5% or <= -1.5% AND it
            beats NIFTY's 15-minute return by >= 1.0% in that direction
  Trade     at the trigger-minute close, every follower that moved less than half the leader's move
            (signed) in those 15 minutes, in the leader's direction, Rs 2 lakh notional each
  Variants  A = exit after 30 minutes, B = exit after 60 minutes (both cut at 15:15); A preferred
  Costs     Rs 30 x 2 brokerage, STT 0.025% on the sell, exchange 0.00297% and SEBI Rs 10/crore on both
            sides, stamp 0.003% on the buy, GST 18% on brokerage + exchange + SEBI, slippage 0.03% a side
  Pass      >= 200 trades, mean net >= +0.10% a trade, t >= 2.0 on daily means, both date halves > 0,
            still > 0 without the biggest sector
Report only: followers minus NIFTY over the same window, trigger 1.0% / 2.0%, all followers.

Reads Upstox history and the instrument table only; places no orders, touches no service or strategy.
Usage (on the server, after the close):  python3 sector_leader_follower.py universe.tsv
universe.tsv = symbol<TAB>isin<TAB>instrument_key (top 500 by turnover, from the DB). Downloads are cached
in /tmp/idea3_cache so an interrupted run resumes.
"""
import datetime as dt
import json
import math
import os
import sys
import time
import urllib.parse
import urllib.request
from collections import defaultdict

FROM, TO = dt.date(2024, 10, 1), dt.date(2026, 9, 30)
NIFTY = "NSE_INDEX|Nifty 50"
CACHE = "/tmp/idea3_cache"
LOOKBACK = 15
TRIG, REL = 1.5, 1.0
NOTIONAL = 200000
MIN_PRICE, MIN_TURN = 200, 25e7
WIN_START, WIN_END, CUT = "09:45", "14:00", "15:15"
MIN_TRADES, MIN_MEAN, MIN_T = 200, 0.10, 2.0
UA = "research-client/1.0"


def token():
    with urllib.request.urlopen("http://localhost:9090/upstox/auth/token", timeout=15) as r:
        return r.read().decode().strip()


TOK = None


def api(url):
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + TOK, "Accept": "application/json",
                                               "User-Agent": UA})
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as ex:
            if ex.code in (400, 404):
                return None
            time.sleep(2 + 4 * attempt)
        except Exception:
            time.sleep(2 + 4 * attempt)
    return None


def cached(name, fetch):
    path = os.path.join(CACHE, name.replace("|", "_").replace(" ", "_").replace("/", "_") + ".json")
    if os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    data = fetch()
    if data is not None:
        with open(path, "w") as f:
            json.dump(data, f)
    return data


def hist(key, path):
    return api("https://api.upstox.com/v3/historical-candle/" + urllib.parse.quote(key, safe="") + path)


def sector(isin):
    def f():
        j = api("https://api.upstox.com/v2/fundamentals/%s/profile" % isin)
        return {"sector": (j or {}).get("data", {}).get("sector")} if j else {"sector": None}
    return cached("sector_" + isin, f)["sector"]


def daily(key):
    def f():
        j = hist(key, "/days/1/%s/%s" % (TO, FROM - dt.timedelta(days=45)))
        return [[c[0][:10], c[4], c[5]] for c in j["data"]["candles"]] if j else []
    return {dt.date.fromisoformat(d): (c, v) for d, c, v in cached("daily_" + key, f)}


def month_ranges(a, b):
    d = a.replace(day=1)
    while d <= b:
        nxt = (d.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
        yield max(a, d), min(b, nxt - dt.timedelta(days=1))
        d = nxt


def minutes_month(key, a, b):
    """{date: {"HH:MM": close}} for one month."""
    def f():  # compact: {date: [close at 09:15, 09:16, ... 15:29 or None]}
        j = hist(key, "/minutes/1/%s/%s" % (b, a))
        if not j:
            return {}
        out = {}
        for c in j["data"]["candles"]:
            i = mins(c[0][11:16]) - 555
            if 0 <= i < 375:
                out.setdefault(c[0][:10], [None] * 375)[i] = round(c[4], 2)
        return out
    out = {}
    for d, arr in cached("min_%s_%s" % (key, a), f).items():
        out[dt.date.fromisoformat(d)] = {hhmm(555 + i): c for i, c in enumerate(arr) if c is not None}
    return out


def hhmm(t):
    return "%02d:%02d" % divmod(t, 60)


def mins(s):
    h, m = s.split(":")
    return int(h) * 60 + int(m)


def cost_pct(price, direction, exit_price):
    qty = max(1, int(NOTIONAL // price))
    buy_px, sell_px = (price, exit_price) if direction > 0 else (exit_price, price)
    buy, sell = buy_px * qty, sell_px * qty
    turnover = buy + sell
    exch, sebi = turnover * 0.00297 / 100, turnover * 10 / 1e7
    rs = 60 + sell * 0.025 / 100 + exch + sebi + buy * 0.003 / 100 + 0.18 * (60 + exch + sebi) + turnover * 0.03 / 100
    return rs / (price * qty) * 100


def stats(name, trades):
    """trades: list of dicts with day, net, sector."""
    n = len(trades)
    res = dict(name=name, n=n, days=0, mean=float("nan"), gross=float("nan"), t=float("nan"), h1=float("nan"),
               h2=float("nan"), nobig=float("nan"), passes=False)
    if n < 2:
        return res
    byday = defaultdict(list)
    for t in trades:
        byday[t["day"]].append(t["net"])
    dm = [sum(v) / len(v) for _, v in sorted(byday.items())]
    k = len(dm)
    mu = sum(dm) / k
    sd = math.sqrt(sum((x - mu) ** 2 for x in dm) / (k - 1)) if k > 1 else 0
    days = sorted(byday)
    mid = days[len(days) // 2]
    h1 = [t["net"] for t in trades if t["day"] < mid]
    h2 = [t["net"] for t in trades if t["day"] >= mid]
    counts = defaultdict(int)
    for t in trades:
        counts[t["sector"]] += 1
    big = max(counts, key=counts.get)
    nb = [t["net"] for t in trades if t["sector"] != big]
    mean = sum(t["net"] for t in trades) / n
    res.update(days=k, mean=mean, gross=sum(t["gross"] for t in trades) / n,
               t=mu / (sd / math.sqrt(k)) if sd > 0 else 0,
               h1=sum(h1) / len(h1) if h1 else float("nan"), h2=sum(h2) / len(h2) if h2 else float("nan"),
               nobig=sum(nb) / len(nb) if nb else float("nan"), big=big)
    res["passes"] = (n >= MIN_TRADES and mean >= MIN_MEAN and res["t"] >= MIN_T and res["h1"] > 0
                     and res["h2"] > 0 and res["nobig"] > 0)
    return res


def main():
    global TOK
    os.makedirs(CACHE, exist_ok=True)
    TOK = token()
    uni = []
    with open(sys.argv[1]) as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) >= 3 and p[2]:
                uni.append((p[0], p[1], p[2]))
    log = lambda *a: print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)
    log("universe", len(uni))

    sectors = {}
    for i, (sym, isin, key) in enumerate(uni):
        s = sector(isin)
        if s:
            sectors[key] = s
    groups = defaultdict(list)
    for sym, isin, key in uni:
        if key in sectors:
            groups[sectors[key]].append(key)
    groups = {s: ks for s, ks in groups.items() if len(ks) >= 4}
    log("sectors with >= 4 stocks:", len(groups), "stocks:", sum(len(v) for v in groups.values()))

    dailies = {}
    for s, ks in groups.items():
        for k in ks:
            dailies[k] = daily(k)
    log("daily candles loaded")

    # point-in-time 20-day turnover and prior close per stock per day
    days_all = sorted({d for dd in dailies.values() for d in dd if FROM <= d <= TO})
    pit = {}  # key -> {day: (prior_close, avg_turn)}
    for k, dd in dailies.items():
        ds = sorted(dd)
        m = {}
        for i, d in enumerate(ds):
            if i >= 20 and FROM <= d <= TO:
                prev = ds[i - 20:i]
                m[d] = (dd[ds[i - 1]][0], sum(dd[x][0] * dd[x][1] for x in prev) / 20)
        pit[k] = m
    leader = {}  # (sector, day) -> key
    for s, ks in groups.items():
        for d in days_all:
            c = [(pit[k][d][1], k) for k in ks if d in pit[k]]
            if c:
                leader[(s, d)] = max(c)[1]
    leaders = sorted(set(leader.values()))
    log("distinct leaders:", len(leaders))

    months = list(month_ranges(FROM, TO))
    nifty = {}
    for a, b in months:
        nifty.update(minutes_month(NIFTY, a, b))
    # triggers per threshold, one leader at a time (only its months in memory)
    trig = {th: [] for th in (1.0, TRIG, 2.0)}
    led = defaultdict(list)
    for (s, d), k in leader.items():
        led[k].append((s, d))
    for i, k in enumerate(leaders):
        lmin = {}
        for m0 in sorted({d.replace(day=1) for _, d in led[k]}):
            a, b = next((a, b) for a, b in months if a.replace(day=1) == m0)
            lmin.update(minutes_month(k, a, b))
        for s, d in led[k]:
            lm, nm = lmin.get(d), nifty.get(d)
            if not lm or not nm:
                continue
            for th in trig:
                for t in range(mins(WIN_START), mins(WIN_END) + 1):
                    a, b = hhmm(t - LOOKBACK), hhmm(t)
                    if a in lm and b in lm and a in nm and b in nm:
                        rl = (lm[b] / lm[a] - 1) * 100
                        rn = (nm[b] / nm[a] - 1) * 100
                        sign = 1 if rl > 0 else -1
                        if abs(rl) >= th and sign * (rl - rn) >= REL:
                            trig[th].append(dict(sector=s, day=d, t=t, leader=k, rl=rl, sign=sign))
                            break
        log("leader", i + 1, "/", len(leaders))
    log("triggers:", {th: len(v) for th, v in trig.items()})

    need = defaultdict(set)  # follower key -> months needed
    for v in trig.values():
        for tr in v:
            for k in groups[tr["sector"]]:
                if k != tr["leader"]:
                    need[k].add(tr["day"].replace(day=1))
    configs = [("A", "A exit +30 min (DECIDES)", TRIG, 30, True), ("B", "B exit +60 min (DECIDES)", TRIG, 60, True),
               ("A10", "A, trigger 1.0%", 1.0, 30, True), ("A20", "A, trigger 2.0%", 2.0, 30, True),
               ("Aall", "A, all followers (not only laggards)", TRIG, 30, False)]
    found = {c[0]: [] for c in configs}

    def follower_trades(k, fmin, th, hold, laggards_only):
        out = []
        for tr in trig[th]:
            if k not in groups[tr["sector"]] or k == tr["leader"]:
                continue
            d, t, sign = tr["day"], tr["t"], tr["sign"]
            if d not in pit.get(k, {}):
                continue
            prior_close, turn = pit[k][d]
            if prior_close < MIN_PRICE or turn < MIN_TURN:
                continue
            fm = fmin.get(d)
            a, b, e = hhmm(t - LOOKBACK), hhmm(t), hhmm(min(t + hold, mins(CUT)))
            if not fm or a not in fm or b not in fm or e not in fm:
                continue
            rf = (fm[b] / fm[a] - 1) * 100
            if laggards_only and sign * rf >= 0.5 * abs(tr["rl"]):
                continue
            gross = sign * (fm[e] / fm[b] - 1) * 100
            net = gross - cost_pct(fm[b], sign, fm[e])
            nm = nifty.get(d, {})
            vs = gross - sign * ((nm[e] / nm[b] - 1) * 100) if b in nm and e in nm else float("nan")
            out.append(dict(day=d, net=net, gross=gross, sector=tr["sector"], vs=vs))
        return out

    # one follower at a time, so only its own months are in memory
    for i, (k, ms) in enumerate(need.items()):
        fmin = {}
        for m0 in sorted(ms):
            a, b = next((a, b) for a, b in months if a.replace(day=1) == m0)
            fmin.update(minutes_month(k, a, b))
        for key, _, th, hold, lag in configs:
            found[key].extend(follower_trades(k, fmin, th, hold, lag))
        if (i + 1) % 25 == 0:
            log("followers", i + 1, "/", len(need))

    rows = [(key, lbl, found[key]) for key, lbl, _, _, _ in configs]
    res = {k: stats(lbl, tr) for k, lbl, tr in rows}
    out = ["SECTOR LEADER -> FOLLOWERS (idea 3, pre-registered 2026-11-17; research only)",
           "Data %s .. %s: %d sessions, %d sectors (>= 4 stocks), %d stocks, %d distinct leaders; triggers 1.0/1.5/2.0%%: %d/%d/%d"
           % (FROM, TO, len(days_all), len(groups), sum(len(v) for v in groups.values()), len(leaders),
              len(trig[1.0]), len(trig[TRIG]), len(trig[2.0])),
           "Pass: >= %d trades, mean net >= +%.2f%%, t >= %.1f (daily means), both halves > 0, > 0 without the biggest sector"
           % (MIN_TRADES, MIN_MEAN, MIN_T),
           "Costs about %.3f%% a round trip at Rs 1,000" % cost_pct(1000, 1, 1000), "",
           "%-38s %6s %5s %8s %8s %6s %8s %8s %8s %8s  %s" % ("variant", "trades", "days", "net %", "gross %", "t",
                                                            "half 1", "half 2", "no-big", "vsNIFTY", "verdict")]
    for k, lbl, tr in rows:
        s = res[k]
        vs = [x["vs"] for x in tr if not math.isnan(x["vs"])]
        verdict = ("PASS" if s["passes"] else "FAIL") if k in ("A", "B") else "info"
        out.append("%-38s %6d %5d %8.3f %8.3f %6.2f %8.3f %8.3f %8.3f %8.3f  %s" % (
            s["name"], s["n"], s["days"], s["mean"], s["gross"], s["t"], s["h1"], s["h2"], s["nobig"],
            sum(vs) / len(vs) if vs else float("nan"), verdict))
    if res["A"]["n"]:
        out.append("Biggest sector (A): %s" % res["A"].get("big"))
    out.append("")
    if res["A"]["passes"]:
        out.append("DECISION  A passes: build a separate paper book (with the user's OK).")
    elif res["B"]["passes"]:
        out.append("DECISION  B passes: build a separate paper book (with the user's OK).")
    else:
        out.append("DECISION  NEITHER passes: idea 3 is dropped as pre-registered (no re-tuning); next is idea 2.")
    print("\n".join(out))


if __name__ == "__main__":
    main()
