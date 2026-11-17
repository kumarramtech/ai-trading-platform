#!/usr/bin/env python3
"""
Idea 1: NIFTY intraday momentum (pre-registered 2026-11-17, research only).

Question: does NIFTY's move from the previous close to 09:45 predict its move from 15:00 to 15:29?
Rules (fixed with the user before any data was looked at):
  Data      NIFTY 50 1-minute candles from Upstox, 2022-01-01 .. 2026-11-17 (INVALID under 600 sessions)
  Signal    r1 = close of the 09:45 candle / previous day's official close - 1
  Trade     at the 15:00 candle close: long if r1 > 0, short if r1 < 0; exit at the 15:29 candle close
  Variants  A = every day; B = only |r1| >= 0.5%   (A preferred if both pass)
  Costs     NIFTY futures, 1 lot of 65: Rs 30 brokerage x 2, STT 0.05% on the sell value, exchange
            0.00173% and SEBI Rs 10/crore on both sides, stamp 0.002% on the buy, GST 18% on
            brokerage + exchange + SEBI, slippage 0.01% each side
  Pass      >= 250 trades, mean net >= +0.03% a trade, t >= 2.0 (daily), both date halves > 0,
            still > 0 with expiry days removed
Report only: r1 from the 09:15 open (no gap), exit at 15:15, expiry vs other days, hit rate.

Reads the Upstox historical API only (token from broker-auth on localhost:9090). Places no orders and
touches no service, table or strategy. Run on the server after the close:  python3 nifty_intraday_momentum.py
"""
import datetime as dt
import json
import math
import sys
import time
import urllib.parse
import urllib.request

KEY = "NSE_INDEX|Nifty 50"
FROM = dt.date(2022, 1, 1)
TO = dt.date(2026, 11, 17)
LOT = 65
MIN_SESSIONS, MIN_TRADES, MIN_MEAN, MIN_T = 600, 250, 0.03, 2.0
B_THRESHOLD = 0.5
TUESDAY_EXPIRY_FROM = dt.date(2025, 9, 1)  # NIFTY weekly expiry moved from Thursday to Tuesday


def token():
    with urllib.request.urlopen("http://localhost:9090/upstox/auth/token", timeout=15) as r:
        return r.read().decode().strip()


def get(tok, path):
    url = "https://api.upstox.com/v3/historical-candle/" + urllib.parse.quote(KEY, safe="") + path
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + tok, "Accept": "application/json",
                                                 "User-Agent": "research-client/1.0"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)["data"]["candles"]
        except Exception as ex:  # rate limit / transient
            if attempt == 3:
                raise
            time.sleep(2 + 3 * attempt)


def month_ranges(a, b):
    d = a
    while d <= b:
        nxt = (d.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
        yield d, min(b, nxt - dt.timedelta(days=1))
        d = nxt


def load(tok):
    minutes = {}  # date -> {"HH:MM": (open, close)}
    for a, b in month_ranges(FROM, TO):
        for c in get(tok, "/minutes/1/%s/%s" % (b, a)):
            t = dt.datetime.fromisoformat(c[0])
            minutes.setdefault(t.date(), {})[t.strftime("%H:%M")] = (float(c[1]), float(c[4]))
        time.sleep(0.3)
    daily = {}
    for year in range(FROM.year, TO.year + 1):
        a, b = max(FROM - dt.timedelta(days=10), dt.date(year, 1, 1)), min(TO, dt.date(year, 12, 31))
        for c in get(tok, "/days/1/%s/%s" % (b, a)):
            daily[dt.datetime.fromisoformat(c[0]).date()] = float(c[4])
        time.sleep(0.3)
    return minutes, daily


def expiry_days(sessions):
    """Approximate weekly expiries: the last session on or before Thursday (Tuesday from Sep 2025) each week."""
    s = sorted(sessions)
    out = set()
    by_week = {}
    for d in s:
        by_week.setdefault(d.isocalendar()[:2], []).append(d)
    for days in by_week.values():
        target_dow = 1 if days[0] >= TUESDAY_EXPIRY_FROM else 3  # Mon=0
        cands = [d for d in days if d.weekday() <= target_dow]
        if cands:
            out.add(max(cands))
    return out


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


def stats(name, rows):
    """rows: list of (date, net_pct, gross_pct, hit)."""
    n = len(rows)
    if n < 2:
        return dict(name=name, n=n, mean=float("nan"), gross=float("nan"), t=float("nan"), h1=float("nan"),
                    h2=float("nan"), noexp=float("nan"), hit=float("nan"), passes=False)
    nets = [r[1] for r in rows]
    mean = sum(nets) / n
    sd = math.sqrt(sum((x - mean) ** 2 for x in nets) / (n - 1))
    t = mean / (sd / math.sqrt(n)) if sd > 0 else 0
    half = len(rows) // 2
    s = sorted(rows)
    h1 = sum(r[1] for r in s[:half]) / max(1, half)
    h2 = sum(r[1] for r in s[half:]) / max(1, n - half)
    ne = [r[1] for r in rows if not r[4]]
    noexp = sum(ne) / len(ne) if ne else float("nan")
    hit = 100.0 * sum(1 for r in rows if r[3]) / n
    passes = n >= MIN_TRADES and mean >= MIN_MEAN and t >= MIN_T and h1 > 0 and h2 > 0 and noexp > 0
    return dict(name=name, n=n, mean=mean, gross=sum(r[2] for r in rows) / n, t=t, h1=h1, h2=h2,
                noexp=noexp, hit=hit, passes=passes)


def main():
    tok = token()
    minutes, daily = load(tok)
    sessions = sorted(d for d in minutes if FROM <= d <= TO)
    closes = sorted(daily)
    exp = expiry_days(sessions)
    variants = {k: [] for k in ("A", "B", "A_open", "A_1515", "A_expiry", "A_other")}
    skipped = 0
    for d in sessions:
        m = minutes[d]
        prev = [c for c in closes if c < d]
        if not prev or not all(k in m for k in ("09:15", "09:45", "15:00", "15:15", "15:29")):
            skipped += 1
            continue
        pc = daily[prev[-1]]
        r1 = (m["09:45"][1] / pc - 1) * 100
        r1_open = (m["09:45"][1] / m["09:15"][0] - 1) * 100
        entry = m["15:00"][1]

        def row(signal, exit_):
            if signal == 0:
                return None
            direction = 1 if signal > 0 else -1
            gross = direction * (exit_ / entry - 1) * 100
            return (d, gross - cost_pct(entry, exit_, direction), gross, gross > 0, d in exp)

        a = row(r1, m["15:29"][1])
        if a:
            variants["A"].append(a)
            variants["A_expiry" if d in exp else "A_other"].append(a)
            if abs(r1) >= B_THRESHOLD:
                variants["B"].append(a)
        o = row(r1_open, m["15:29"][1])
        if o:
            variants["A_open"].append(o)
        e = row(r1, m["15:15"][1])
        if e:
            variants["A_1515"].append(e)

    labels = [("A", "A every day (DECIDES)"), ("B", "B |r1| >= 0.5% (DECIDES)"),
              ("A_open", "A, r1 from 09:15 open (no gap)"), ("A_1515", "A, exit 15:15"),
              ("A_expiry", "A, expiry days only"), ("A_other", "A, non-expiry days")]
    res = {k: stats(lbl, variants[k]) for k, lbl in labels}
    valid = len(sessions) - skipped >= MIN_SESSIONS
    out = ["NIFTY INTRADAY MOMENTUM (idea 1, pre-registered 2026-11-17; research only)",
           "Data %s .. %s: %d sessions with minute data, %d skipped (missing candles), %d approx. expiry days%s"
           % (sessions[0] if sessions else "-", sessions[-1] if sessions else "-", len(sessions), skipped, len(exp),
              "" if valid else "  ** INVALID: under %d sessions **" % MIN_SESSIONS),
           "Pass: >= %d trades, mean net >= +%.2f%%, t >= %.1f, both halves > 0, > 0 without expiry days"
           % (MIN_TRADES, MIN_MEAN, MIN_T),
           "Costs about %.3f%% a round trip at NIFTY 25,000" % cost_pct(25000, 25000, 1), "",
           "%-34s %6s %9s %9s %7s %8s %8s %9s %6s  %s" % ("variant", "trades", "net %", "gross %", "t",
                                                         "half 1", "half 2", "no-exp", "hit%", "verdict")]
    for k, _ in labels:
        s = res[k]
        verdict = ("PASS" if s["passes"] and valid else "FAIL") if k in ("A", "B") else "info"
        out.append("%-34s %6d %9.4f %9.4f %7.2f %8.4f %8.4f %9.4f %6.1f  %s" % (
            s["name"], s["n"], s["mean"], s["gross"], s["t"], s["h1"], s["h2"], s["noexp"], s["hit"], verdict))
    if res["A"]["n"]:
        out.append("")
        out.append("Net Rs per lot at NIFTY 25,000: A %.0f, B %.0f a trade" % (
            res["A"]["mean"] / 100 * 25000 * LOT, res["B"]["mean"] / 100 * 25000 * LOT if res["B"]["n"] else 0))
    out.append("")
    if not valid:
        out.append("DECISION  INVALID: not enough sessions; no verdict.")
    elif res["A"]["passes"]:
        out.append("DECISION  A passes: build a separate NIFTY-futures paper book for 60 sessions (with the user's OK).")
    elif res["B"]["passes"]:
        out.append("DECISION  B passes: build a separate NIFTY-futures paper book for 60 sessions (with the user's OK).")
    else:
        out.append("DECISION  NEITHER passes: idea 1 is dropped as pre-registered (no re-tuning); next is idea 3.")
    print("\n".join(out))


if __name__ == "__main__":
    sys.exit(main())
