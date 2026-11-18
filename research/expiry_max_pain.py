#!/usr/bin/env python3
"""
Idea 2: NIFTY pulled toward max pain on expiry day (pre-registered 2026-11-18, rules approved by the user
before any data was looked at; research only).

  Data      NSE F&O bhavcopy (open interest per strike; old format up to 2024-07-05, UDiFF after) and NIFTY 50
            1-minute candles from Upstox. Every NIFTY weekly expiry day 2022-01 .. 2026-11-17.
  Max pain  from the PREVIOUS trading day's closing OI of the expiring series (known before expiry day opens):
            the listed strike K minimising sum_s callOI(s) * max(0, K - s) + putOI(s) * max(0, s - K).
  Signal    distance = (max pain - NIFTY) / NIFTY at the 13:00 candle close.
  Trade A   if |distance| >= 0.4%: 1 NIFTY futures lot (65) toward max pain at the 13:00 close, exit at the
            15:15 close. Variant B = the same with entry at 14:00 (A preferred if both pass).
  Prices    NIFTY index prices stand in for futures.
  Costs     Rs 30 x 2 brokerage, STT 0.05% on the sell value, exchange 0.00173% and SEBI Rs 10/crore both sides,
            stamp 0.002% on the buy, GST 18% on brokerage + exchange + SEBI, slippage 0.01% each side.
  Pass      (each variant) >= 80 trades, mean net >= +0.03%, t >= 2.0, both date halves > 0, mean > 0 for expiries
            from 2024-11-20, AND mean net above the placebo's (same trade toward the day's 09:15 open price with
            the same threshold and entry time; if the placebo does as well, it is plain mean reversion).
  INVALID   if more than 5% of expiry days miss the OI file or the minute candles.
  Report    placebo, hit rate, distance bands 0.4-0.8% and > 0.8%.
Places no orders and touches no service, table or strategy. Run on the server after the close.
"""
import csv
import datetime as dt
import io
import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

FROM, TO = dt.date(2022, 1, 1), dt.date(2026, 11, 17)
RECENT_FROM = dt.date(2024, 11, 20)
UDIFF_FROM = dt.date(2024, 7, 8)
LOT, THRESH = 65, 0.4
CACHE = "/tmp/bhav_oi"
UA = "research-client/1.0"
KEY = "NSE_INDEX|Nifty 50"


def http(url, headers=None, timeout=30):
    req = urllib.request.Request(url, headers=dict({"User-Agent": UA}, **(headers or {})))
    for attempt in range(8):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except urllib.error.HTTPError as ex:
            if ex.code in (400, 403, 404):
                return None
            time.sleep(60 if ex.code == 429 else 5 + 5 * attempt)
        except Exception:
            time.sleep(5 + 5 * attempt)
    return None


def minutes(tok):
    out = {}
    d = FROM
    while d <= TO:
        nxt = (d.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
        b = min(TO, nxt - dt.timedelta(days=1))
        raw = http("https://api.upstox.com/v3/historical-candle/" + urllib.parse.quote(KEY, safe="")
                   + "/minutes/1/%s/%s" % (b, d), {"Authorization": "Bearer " + tok, "Accept": "application/json"})
        time.sleep(0.9)
        if raw:
            for c in json.loads(raw)["data"]["candles"]:
                t = dt.datetime.fromisoformat(c[0])
                hm = t.strftime("%H:%M")
                if hm in ("09:15", "13:00", "14:00", "15:15"):
                    out.setdefault(t.date(), {})[hm] = (float(c[1]), float(c[4]))
                else:
                    out.setdefault(t.date(), {})
        d = nxt
    return out


def bhav(day):
    """{(expiry, strike, 'CE'|'PE'): open_interest} for NIFTY index options, or None if no file."""
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, "%s.json" % day)
    if os.path.exists(path):
        raw = json.load(open(path))
        return None if raw is None else {(dt.date.fromisoformat(e), k, t): oi for e, k, t, oi in raw}
    if day < UDIFF_FROM:
        url = "https://nsearchives.nseindia.com/content/historical/DERIVATIVES/%d/%s/fo%s%sbhav.csv.zip" % (
            day.year, day.strftime("%b").upper(), day.strftime("%d"), day.strftime("%b%Y").upper())
    else:
        url = "https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_%s_F_0000.csv.zip" % day.strftime("%Y%m%d")
    data = http(url)
    time.sleep(0.5)
    rows = None
    if data:
        try:
            z = zipfile.ZipFile(io.BytesIO(data))
            rd = csv.reader(io.TextIOWrapper(z.open(z.namelist()[0]), encoding="utf-8", errors="replace"))
            h = next(rd)
            rows = []
            if "SYMBOL" in h:
                ix = [h.index(x) for x in ("INSTRUMENT", "SYMBOL", "EXPIRY_DT", "STRIKE_PR", "OPTION_TYP", "OPEN_INT")]
                for r in rd:
                    if len(r) > max(ix) and r[ix[1]] == "NIFTY" and r[ix[0]] == "OPTIDX":
                        e = dt.datetime.strptime(r[ix[2]], "%d-%b-%Y").date()
                        rows.append((str(e), float(r[ix[3]]), r[ix[4]], float(r[ix[5]] or 0)))
            else:
                ix = [h.index(x) for x in ("FinInstrmTp", "TckrSymb", "XpryDt", "StrkPric", "OptnTp", "OpnIntrst")]
                for r in rd:
                    if len(r) > max(ix) and r[ix[1]] == "NIFTY" and r[ix[0]] == "IDO":
                        rows.append((r[ix[2]], float(r[ix[3]]), r[ix[4]], float(r[ix[5]] or 0)))
        except Exception:
            rows = None
    json.dump(rows, open(path, "w"))
    return None if rows is None else {(dt.date.fromisoformat(e), k, t): oi for e, k, t, oi in rows}


def max_pain(book, expiry):
    calls = {k: oi for (e, k, t), oi in book.items() if e == expiry and t == "CE"}
    puts = {k: oi for (e, k, t), oi in book.items() if e == expiry and t == "PE"}
    strikes = sorted(set(calls) | set(puts))
    if not strikes or sum(calls.values()) + sum(puts.values()) <= 0:
        return None
    return min(strikes, key=lambda K: sum(oi * max(0.0, K - s) for s, oi in calls.items())
               + sum(oi * max(0.0, s - K) for s, oi in puts.items()))


def cost_pct(entry, exit_, direction):
    buy_px, sell_px = (entry, exit_) if direction > 0 else (exit_, entry)
    buy, sell = buy_px * LOT, sell_px * LOT
    turnover = buy + sell
    brokerage = 60.0
    exchange = turnover * 0.00173 / 100
    sebi = turnover * 10 / 1e7
    rs = (brokerage + sell * 0.05 / 100 + exchange + sebi + buy * 0.002 / 100
          + 0.18 * (brokerage + exchange + sebi) + turnover * 0.01 / 100)
    return rs / (entry * LOT) * 100


def summary(name, rows):
    """rows: list of (date, net, gross, dist_abs)."""
    n = len(rows)
    if n < 2:
        return dict(name=name, n=n, mean=float("nan"), gross=float("nan"), t=float("nan"), h1=float("nan"),
                    h2=float("nan"), recent=float("nan"), hit=float("nan"))
    s = sorted(rows)
    nets = [r[1] for r in s]
    mean = sum(nets) / n
    sd = math.sqrt(sum((x - mean) ** 2 for x in nets) / (n - 1))
    rc = [r[1] for r in s if r[0] >= RECENT_FROM]
    return dict(name=name, n=n, mean=mean, gross=sum(r[2] for r in s) / n, t=mean / (sd / math.sqrt(n)) if sd else 0,
                h1=sum(nets[:n // 2]) / (n // 2), h2=sum(nets[n // 2:]) / (n - n // 2),
                recent=sum(rc) / len(rc) if rc else float("nan"),
                hit=100.0 * sum(1 for r in s if r[2] > 0) / n)


def main():
    tok = urllib.request.urlopen("http://localhost:9090/upstox/auth/token", timeout=15).read().decode().strip()
    mins = minutes(tok)
    days = sorted(d for d in mins if FROM <= d <= TO)

    def prev_day(d):
        e = [x for x in days if x < d]
        return e[-1] if e else None

    first = bhav(days[1])
    expiry = min(e for e, _, _ in first if e >= days[1])
    n_exp, missing = 0, []
    tr = {k: [] for k in ("A", "B", "PA", "PB")}
    while expiry <= TO:
        n_exp += 1
        pd_ = prev_day(expiry)
        book = bhav(pd_) if pd_ else None
        probe = book if book else bhav(expiry)
        nxt = min((e for e, _, _ in probe if e > expiry), default=None) if probe else None
        m = mins.get(expiry, {})
        if not book or not all(k in m for k in ("09:15", "13:00", "14:00", "15:15")):
            missing.append(expiry)
        else:
            mp = max_pain(book, expiry)
            if mp is None:
                missing.append(expiry)
            else:
                exit_ = m["15:15"][1]
                day_open = m["09:15"][0]
                for v, hm, target in (("A", "13:00", mp), ("B", "14:00", mp),
                                      ("PA", "13:00", day_open), ("PB", "14:00", day_open)):
                    px = m[hm][1]
                    dist = (target - px) / px * 100
                    if abs(dist) >= THRESH:
                        direction = 1 if dist > 0 else -1
                        gross = direction * (exit_ / px - 1) * 100
                        tr[v].append((expiry, gross - cost_pct(px, exit_, direction), gross, abs(dist)))
        if nxt is None:
            break
        expiry = nxt

    valid = n_exp > 0 and len(missing) <= 0.05 * n_exp
    res = {k: summary(k, tr[k]) for k in tr}
    out = ["NIFTY EXPIRY MAX-PAIN PULL (idea 2, pre-registered 2026-11-18; research only)",
           "Expiry days %d, missing OI file or candles %d (%.1f%%)%s" % (
               n_exp, len(missing), 100.0 * len(missing) / max(1, n_exp),
               "" if valid else "  ** INVALID: over 5% missing **"),
           "Pass: >= 80 trades, mean net >= +0.03%, t >= 2.0, both halves > 0, > 0 from 2024-11-20, above placebo",
           "Costs about %.3f%% a round trip at NIFTY 25,000" % cost_pct(25000, 25000, 1), "",
           "%-34s %5s %8s %8s %6s %8s %8s %8s %5s  %s" % ("variant", "n", "net %", "gross %", "t", "half 1",
                                                       "half 2", "recent", "hit%", "verdict")]
    labels = {"A": "A max pain, enter 13:00 (DECIDES)", "B": "B max pain, enter 14:00 (DECIDES)",
              "PA": "placebo toward open, 13:00", "PB": "placebo toward open, 14:00"}
    passed = {}
    for k in ("A", "B", "PA", "PB"):
        s = res[k]
        if k in ("A", "B"):
            p = res["P" + k]
            ok = (valid and s["n"] >= 80 and s["mean"] >= 0.03 and s["t"] >= 2.0 and s["h1"] > 0 and s["h2"] > 0
                  and s["recent"] > 0 and (p["n"] < 2 or s["mean"] > p["mean"]))
            passed[k] = ok
            verdict = "PASS" if ok else "FAIL"
        else:
            verdict = "info"
        out.append("%-34s %5d %8.4f %8.4f %6.2f %8.4f %8.4f %8.4f %5.1f  %s" % (
            labels[k], s["n"], s["mean"], s["gross"], s["t"], s["h1"], s["h2"], s["recent"], s["hit"], verdict))
    out.append("")
    for k in ("A", "B"):
        for lo, hi in ((0.4, 0.8), (0.8, 99)):
            band = [r for r in tr[k] if lo <= r[3] < hi]
            s = summary(k, band)
            out.append("  %s distance %.1f-%s%%: n %d, net %.4f%%, gross %.4f%%, hit %.1f%%" % (
                k, lo, "%.1f" % hi if hi < 99 else "", s["n"], s["mean"], s["gross"], s["hit"]))
    out.append("")
    if not valid:
        out.append("DECISION  INVALID: too many expiry days missing data; no verdict.")
    elif passed["A"]:
        out.append("DECISION  A passes: propose a separate expiry-day NIFTY-futures paper book (user's OK).")
    elif passed["B"]:
        out.append("DECISION  B passes: propose a separate expiry-day NIFTY-futures paper book (user's OK).")
    else:
        out.append("DECISION  NEITHER passes: idea 2 is dropped as pre-registered (no re-tuning).")
    if missing:
        out.append("Missing expiry days: " + ", ".join(str(d) for d in missing[:40]))
    print("\n".join(out))


if __name__ == "__main__":
    sys.exit(main())
