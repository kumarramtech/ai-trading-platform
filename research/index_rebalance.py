#!/usr/bin/env python3
"""
Index rebalancing study (rules approved by the user 2026-11-18 before any prices were looked at; research only).

Events   research/index_events.tsv, built by index_events_parse.py from the official NSE Indices press releases:
         scheduled semi-annual reviews of NIFTY 50, NIFTY Next 50 and NIFTY Midcap 150 only (ad-hoc changes
         excluded). A stock named in several sections of one review is classified by the largest index it
         appears in (NIFTY 50 > Next 50 > Midcap 150), with that section's side. BSE Sensex: included only if
         its official releases were found (see the report).
Eligible price >= Rs 100 and 20-day average traded value >= Rs 10 crore on the day before entry.
Test A   run-up, daily closes 2018-2026: BUY each ADDED stock at the close of the first trading day after the
         announcement date, SELL at the close of the change date (the "close of" date in the release).
         Return minus NIFTY over the same closes; costs 0.33% a round trip. Removed stocks: reported only.
Test B   minute data, change dates Oct 2024 .. Sep 2026, return minus NIFTY over the same minutes, cost 0.13%:
         B1 change date: ADD long, DROP short, 09:20 close -> 15:25 close.
         B2 next trading day: ADD short, DROP long, 09:20 close -> 15:15 close.
Pass     (each test separately) >= 40 trades; mean net > 0 with t >= 2.0; mean net > 0 in both halves of the
         reviews (by date); mean net > 0 with the single best review removed; placebo (same stocks, same
         windows one year earlier, first trading day on/after date - 365) beaten by >= the round-trip cost,
         gross. If < 40 trades: NOT ENOUGH DATA.
Data     daily and 1-minute candles from the Upstox historical API (token from localhost:9090, read only);
         symbol -> ISIN from the NSE equity bhavcopy of the announcement date (as listed then), else
         instrument_master. Data-error rule (fixed before running): an A trade or A placebo window containing a
         one-day close move above 25% (split/bonus in unadjusted data) is excluded and counted.
Places no orders; touches no service, table or strategy.
Usage (server, after the close): python3 index_rebalance.py index_events.tsv
"""
import datetime as dt
import io
import json
import math
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from collections import defaultdict

UA = "research-client/1.0"
CACHE = "/tmp/idx_cache"
EDGE = "/tmp/edge_cache"
NIFTY = "NSE_INDEX|Nifty 50"
A_FROM, A_TO = dt.date(2018, 1, 1), dt.date(2026, 11, 17)
B_FROM, B_TO = dt.date(2024, 10, 1), dt.date(2026, 9, 30)
COST_A, COST_B = 0.33, 0.13
MIN_PRICE, MIN_TURN, JUMP = 100, 10e7, 25.0
RANK = {"NIFTY 50": 0, "NIFTY NEXT 50": 1, "NIFTY MIDCAP 150": 2, "SENSEX": 0}
TOK = None


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


def http(url, headers=None):
    req = urllib.request.Request(url, headers=dict({"User-Agent": UA}, **(headers or {})))
    for attempt in range(8):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read()
        except urllib.error.HTTPError as ex:
            if ex.code in (400, 403, 404):
                return None
            time.sleep(60 if ex.code == 429 else 5 + 5 * attempt)
        except Exception:
            time.sleep(5 + 5 * attempt)
    return None


def api(path):
    time.sleep(0.9)
    for attempt in range(40):
        try:
            req = urllib.request.Request("https://api.upstox.com/v3/historical-candle/" + path,
                                         headers={"Authorization": "Bearer " + TOK, "Accept": "application/json",
                                                  "User-Agent": UA})
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)["data"]["candles"]
        except urllib.error.HTTPError as ex:
            if ex.code in (400, 404):
                return []
            time.sleep(60 if ex.code == 429 else 5 + 5 * min(attempt, 6))
        except Exception:
            time.sleep(5 + 5 * min(attempt, 6))
    raise RuntimeError("Upstox kept failing: " + path)


def cached(name, fn):
    p = os.path.join(CACHE, name.replace("|", "_").replace(" ", "_").replace("/", "_") + ".json")
    if os.path.exists(p):
        return json.load(open(p))
    v = fn()
    json.dump(v, open(p, "w"))
    return v


# ------------------------------------------------------------------ data

def daily(key):
    """{date: (close, volume)} 2017-01-01 .. A_TO."""
    def fetch():
        out = {}
        for a, b in ((dt.date(2017, 1, 1), dt.date(2021, 12, 31)), (dt.date(2022, 1, 1), A_TO)):
            for c in api(urllib.parse.quote(key, safe="") + "/days/1/%s/%s" % (b, a)):
                out[c[0][:10]] = (float(c[4]), float(c[5] or 0))
        return out
    return {dt.date.fromisoformat(d): tuple(v) for d, v in cached("d_" + key, fetch).items()}


def minutes(key, day):
    """[375 closes or None] for one day (09:15..15:29), from the edge-map cache when present."""
    a = day.replace(day=1)
    b = (a.replace(day=28) + dt.timedelta(days=4)).replace(day=1) - dt.timedelta(days=1)
    kf = key.replace("|", "_").replace(" ", "_")
    p = os.path.join(EDGE, "m_%s_%s.json" % (kf, max(a, B_FROM) if a <= B_FROM <= b else a))
    if os.path.exists(p):
        raw = json.load(open(p))
    else:
        def fetch():
            raw = {}
            for c in api(urllib.parse.quote(key, safe="") + "/minutes/1/%s/%s" % (b, a)):
                i = int(c[0][11:13]) * 60 + int(c[0][14:16]) - 555
                if 0 <= i < 375:
                    raw.setdefault(c[0][:10], [[None] * 375, [0] * 375])[0][i] = float(c[4])
            return raw
        raw = cached("m_%s_%s" % (key, a), fetch)
    v = raw.get(day.isoformat())
    return v[0] if v else None


def bhav_isin(day):
    """{symbol: isin} from the NSE equity bhavcopy of the last trading day on/before `day`."""
    def fetch():
        for back in range(8):
            d = day - dt.timedelta(days=back)
            if d >= dt.date(2024, 7, 8):
                url = "https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_%s_F_0000.csv.zip" % d.strftime("%Y%m%d")
            else:
                url = "https://nsearchives.nseindia.com/content/historical/EQUITIES/%d/%s/cm%s%sbhav.csv.zip" % (
                    d.year, d.strftime("%b").upper(), d.strftime("%d"), d.strftime("%b%Y").upper())
            data = http(url)
            time.sleep(0.5)
            if not data:
                continue
            try:
                z = zipfile.ZipFile(io.BytesIO(data))
                lines = z.read(z.namelist()[0]).decode("utf-8", "replace").splitlines()
            except Exception:
                continue
            head = [h.strip() for h in lines[0].split(",")]
            si = head.index("TckrSymb") if "TckrSymb" in head else head.index("SYMBOL")
            ii = head.index("ISIN")
            sr = head.index("SctySrs") if "SctySrs" in head else head.index("SERIES")
            out = {}
            for l in lines[1:]:
                f = [x.strip() for x in l.split(",")]
                if len(f) > max(si, ii, sr) and f[sr] in ("EQ", "BE", "BZ"):
                    out.setdefault(f[si], f[ii])
            return out
        return {}
    return cached("bhav_%s" % day, fetch)


def master_isin():
    q = "select trading_symbol, isin from instrument_master where segment='NSE_EQ' and isin is not null"
    out = subprocess.run(["sudo", "mysql", "-N", "ai_trading_platform", "-e", q], capture_output=True, text=True).stdout
    return dict(l.split("\t") for l in out.splitlines() if "\t" in l)


# ------------------------------------------------------------------ helpers

def on_or_after(days, d):
    for x in days:
        if x >= d:
            return x
    return None


def next_day(days, d):
    for x in days:
        if x > d:
            return x
    return None


def stats(xs):
    n = len(xs)
    if n < 2:
        return n, (xs[0] if xs else 0.0), 0.0
    mu = sum(xs) / n
    sd = math.sqrt(sum((x - mu) ** 2 for x in xs) / (n - 1))
    return n, mu, (mu / (sd / math.sqrt(n)) if sd > 0 else 0.0)


def verdict(name, trades, placebo, cost, out):
    """trades/placebo: [(review_date, gross_pct)]"""
    net = [g - cost for _, g in trades]
    n, mu, t = stats(net)
    out.append("%s: %d trades, gross %+.3f%%, net %+.3f%%, t %.2f, win %.0f%%" % (
        name, n, mu + cost, mu, t, 100 * sum(x > 0 for x in net) / max(1, n)))
    if n < 40:
        out.append("  -> NOT ENOUGH DATA (< 40 trades)")
        return "NOT ENOUGH DATA"
    reviews = sorted({r for r, _ in trades})
    by = defaultdict(list)
    for r, g in trades:
        by[r].append(g - cost)
    for r in reviews:
        out.append("    review %s: %3d trades, net %+.3f%%" % (r, len(by[r]), sum(by[r]) / len(by[r])))
    half = reviews[len(reviews) // 2] if len(reviews) > 1 else reviews[0]
    h1 = [g - cost for r, g in trades if r < half]
    h2 = [g - cost for r, g in trades if r >= half]
    best = max(reviews, key=lambda r: sum(by[r]))
    rest = [g - cost for r, g in trades if r != best]
    pn, pmu, pt = stats([g for _, g in placebo])
    checks = [("mean net > 0, t >= 2", mu > 0 and t >= 2.0),
              ("both halves > 0 (%+.3f / %+.3f)" % (stats(h1)[1], stats(h2)[1]), bool(h1) and bool(h2)
               and stats(h1)[1] > 0 and stats(h2)[1] > 0),
              ("without best review %s > 0 (%+.3f)" % (best, stats(rest)[1]), stats(rest)[1] > 0),
              ("beats placebo by >= cost, gross (placebo %d trades, gross %+.3f%%)" % (pn, pmu),
               pn > 0 and (mu + cost) - pmu >= cost)]
    for label, ok in checks:
        out.append("  [%s] %s" % ("PASS" if ok else "fail", label))
    v = "PASS" if all(ok for _, ok in checks) else "FAIL"
    out.append("  -> %s" % v)
    return v


# ------------------------------------------------------------------ main

def main():
    global TOK
    os.makedirs(CACHE, exist_ok=True)
    with urllib.request.urlopen("http://localhost:9090/upstox/auth/token", timeout=15) as r:
        TOK = r.read().decode().strip()
    rows = [l.rstrip("\n").split("\t") for l in open(sys.argv[1]) if l.strip()][1:]
    # one event per (review, stock): the largest index decides
    ev = {}
    for idx, side, sym, ann, close, src in rows:
        k = (close, sym)
        if k not in ev or RANK[idx] < RANK[ev[k][0]]:
            ev[k] = (idx, side, sym, dt.date.fromisoformat(ann), dt.date.fromisoformat(close))
    events = sorted(ev.values(), key=lambda e: (e[4], e[2]))
    log("events", len(events), "reviews", len({e[4] for e in events}))
    master = master_isin()
    nifty = daily(NIFTY)
    ndays = sorted(nifty)

    A, A_pl, A_drop, B1, B1_pl, B2, B2_pl = [], [], [], [], [], [], []
    miss, jumps, inelig = [], 0, 0
    for idx, side, sym, ann, close in events:
        isin = bhav_isin(ann).get(sym) or master.get(sym)
        if not isin:
            miss.append("%s(%s)" % (sym, close))
            continue
        key = "NSE_EQ|" + isin
        d = daily(key)
        if not d:
            miss.append("%s(%s)" % (sym, close))
            continue
        days = sorted(x for x in d if x in nifty)
        t1, e = next_day(days, ann), on_or_after(days, close)
        if not t1 or not e or e < t1:
            miss.append("%s(%s)" % (sym, close))
            continue
        j = days.index(t1) - 1
        if j < 20:
            miss.append("%s(%s)" % (sym, close))
            continue
        turn = sum(d[days[x]][0] * d[days[x]][1] for x in range(j - 19, j + 1)) / 20
        if d[days[j]][0] < MIN_PRICE or turn < MIN_TURN:
            inelig += 1
            continue
        direction = 1 if side == "ADD" else -1

        def window(a, b):
            """excess return % close a -> close b, or None if a one-day jump > JUMP% is inside."""
            seg = [x for x in days if a <= x <= b]
            for p, q in zip(seg, seg[1:]):
                if abs(d[q][0] / d[p][0] - 1) * 100 > JUMP:
                    return None
            return ((d[b][0] / d[a][0]) - (nifty[b][0] / nifty[a][0])) * 100

        if A_FROM <= t1 <= A_TO and t1 < e:
            r = window(t1, e)
            pa, pb = on_or_after(days, t1 - dt.timedelta(days=365)), on_or_after(days, e - dt.timedelta(days=365))
            rp = window(pa, pb) if pa and pb and pa < pb else None
            if r is None:
                jumps += 1
            elif side == "ADD":
                A.append((close, r))
                if rp is not None:
                    A_pl.append((close, rp))
            else:
                A_drop.append((close, r))

        if B_FROM <= e <= B_TO:
            for test, pl, day, sign, xi in ((B1, B1_pl, e, direction, 370),
                                             (B2, B2_pl, next_day(days, e), -direction, 360)):
                if not day:
                    continue
                for bucket, dd in ((test, day), (pl, on_or_after(days, day - dt.timedelta(days=365)))):
                    if not dd:
                        continue
                    s, n = minutes(key, dd), minutes(NIFTY, dd)
                    if s and n and s[5] and s[xi] and n[5] and n[xi]:
                        bucket.append((close, sign * ((s[xi] / s[5] - 1) - (n[xi] / n[5] - 1)) * 100))

    out = ["INDEX REBALANCING STUDY (pre-registered 2026-11-18; research only)",
           "events %d (%d reviews %s .. %s); no price data / ISIN: %d; ineligible: %d; excluded by the 25%% jump rule: %d"
           % (len(events), len({e[4] for e in events}), events[0][4], events[-1][4], len(miss), inelig, jumps),
           "missing: " + (", ".join(miss[:60]) + (" ..." if len(miss) > 60 else "") if miss else "none"), ""]
    res = {"A": verdict("TEST A  run-up: buy additions T+1 close -> change-date close (minus NIFTY)", A, A_pl, COST_A, out)}
    _, dm, dtv = stats([g for _, g in A_drop])
    out.append("  (report only) removals over the same window: %d, mean %+.3f%% vs NIFTY, t %.2f" % (len(A_drop), dm, dtv))
    out.append("")
    res["B1"] = verdict("TEST B1 change date 09:20 -> 15:25, ADD long / DROP short", B1, B1_pl, COST_B, out)
    out.append("")
    res["B2"] = verdict("TEST B2 next day 09:20 -> 15:15, ADD short / DROP long", B2, B2_pl, COST_B, out)
    out.append("")
    out.append("VERDICT: " + ", ".join("%s %s" % kv for kv in res.items()))
    print("\n".join(out))


if __name__ == "__main__":
    main()
