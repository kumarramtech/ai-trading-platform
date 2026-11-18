#!/usr/bin/env python3
"""
Idea 4 (new numbering 2026-11-18): NIFTY overnight hold (pre-registered 2026-11-18 morning, research only).

Question: does NIFTY gain overnight by enough to beat futures costs and carry?
Rules (approved by the user before any data was looked at):
  Data      NIFTY 50 1-minute candles from Upstox, 2022-01-01 .. 2026-11-17 (INVALID under 900 usable nights)
  Trade     buy 1 NIFTY futures lot (65) at the 15:25 candle close every session; sell at the next session's
            09:20 candle close
  Costs     Rs 30 x 2 brokerage, STT 0.05% on the sell value, exchange 0.00173% and SEBI Rs 10/crore on both
            sides, stamp 0.002% on the buy, GST 18% on brokerage + exchange + SEBI, slippage 0.01% each side
            PLUS carry: 6.5% a year x calendar nights / 365 (the futures premium decays toward spot)
  Pass      >= 900 trades, mean net >= +0.03% a trade, t >= 2.0, both date halves > 0,
            mean net > 0 from 2024-11-20 onward
Report only: weekends vs weeknights, after an up day vs a down day, the five worst nights.

Reads the Upstox historical API only (token from broker-auth on localhost:9090). Places no orders and
touches no service, table or strategy. Run on the server after the close:  python3 nifty_overnight_hold.py
"""
import datetime as dt
import json
import math
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

KEY = "NSE_INDEX|Nifty 50"
FROM, TO = dt.date(2022, 1, 1), dt.date(2026, 11, 17)
RECENT_FROM = dt.date(2024, 11, 20)
LOT = 65
CARRY_PA = 6.5
MIN_NIGHTS, MIN_TRADES, MIN_MEAN, MIN_T = 900, 900, 0.03, 2.0


def token():
    with urllib.request.urlopen("http://localhost:9090/upstox/auth/token", timeout=15) as r:
        return r.read().decode().strip()


def get(tok, path):
    url = "https://api.upstox.com/v3/historical-candle/" + urllib.parse.quote(KEY, safe="") + path
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + tok, "Accept": "application/json",
                                                 "User-Agent": "research-client/1.0"})
    time.sleep(0.9)
    for attempt in range(20):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)["data"]["candles"]
        except urllib.error.HTTPError as ex:
            if ex.code in (400, 404):
                return []
            time.sleep(60 if ex.code == 429 else 5 + 5 * min(attempt, 6))
        except Exception:
            time.sleep(5 + 5 * min(attempt, 6))
    raise RuntimeError("Upstox call failed: " + path)


def month_ranges(a, b):
    d = a
    while d <= b:
        nxt = (d.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
        yield d, min(b, nxt - dt.timedelta(days=1))
        d = nxt


def load(tok):
    minutes = {}  # date -> {"HH:MM": close}
    for a, b in month_ranges(FROM, TO):
        for c in get(tok, "/minutes/1/%s/%s" % (b, a)):
            t = dt.datetime.fromisoformat(c[0])
            minutes.setdefault(t.date(), {})[t.strftime("%H:%M")] = float(c[4])
    return minutes


def cost_pct(entry, exit_, nights):
    buy, sell = entry * LOT, exit_ * LOT
    turnover = buy + sell
    brokerage = 60.0
    exchange = turnover * 0.00173 / 100
    sebi = turnover * 10 / 1e7
    rs = (brokerage + sell * 0.05 / 100 + exchange + sebi + buy * 0.002 / 100
          + 0.18 * (brokerage + exchange + sebi) + turnover * 0.01 / 100)
    return rs / buy * 100 + CARRY_PA * nights / 365


def summary(name, rows):
    """rows: list of (date, net, gross)."""
    n = len(rows)
    if n < 2:
        return dict(name=name, n=n, mean=float("nan"), gross=float("nan"), t=float("nan"),
                    h1=float("nan"), h2=float("nan"), recent=float("nan"), win=float("nan"), passes=False)
    nets = [r[1] for r in rows]
    mean = sum(nets) / n
    sd = math.sqrt(sum((x - mean) ** 2 for x in nets) / (n - 1))
    t = mean / (sd / math.sqrt(n)) if sd > 0 else 0
    s = sorted(rows)
    half = n // 2
    h1 = sum(r[1] for r in s[:half]) / half
    h2 = sum(r[1] for r in s[half:]) / (n - half)
    rc = [r[1] for r in rows if r[0] >= RECENT_FROM]
    recent = sum(rc) / len(rc) if rc else float("nan")
    win = 100.0 * sum(1 for x in nets if x > 0) / n
    passes = n >= MIN_TRADES and mean >= MIN_MEAN and t >= MIN_T and h1 > 0 and h2 > 0 and recent > 0
    return dict(name=name, n=n, mean=mean, gross=sum(r[2] for r in rows) / n, t=t, h1=h1, h2=h2,
                recent=recent, win=win, passes=passes)


def main():
    minutes = load(token())
    days = sorted(d for d in minutes if FROM <= d <= TO)
    rows, weekend, weeknight, after_up, after_down = [], [], [], [], []
    skipped = 0
    for i in range(len(days) - 1):
        d, nd = days[i], days[i + 1]
        m, nm = minutes[d], minutes[nd]
        if "15:25" not in m or "09:20" not in nm:
            skipped += 1
            continue
        entry, exit_ = m["15:25"], nm["09:20"]
        nights = (nd - d).days
        gross = (exit_ / entry - 1) * 100
        r = (d, gross - cost_pct(entry, exit_, nights), gross)
        rows.append(r)
        (weekend if nights > 1 else weeknight).append(r)
        if i > 0 and "15:29" in minutes[days[i - 1]]:
            (after_up if entry >= minutes[days[i - 1]]["15:29"] else after_down).append(r)

    valid = len(rows) >= MIN_NIGHTS
    res = [summary("A every night (DECIDES)", rows), summary("weekends / holidays (info)", weekend),
           summary("weeknights (info)", weeknight), summary("after an up day (info)", after_up),
           summary("after a down day (info)", after_down)]
    out = ["NIFTY OVERNIGHT HOLD (pre-registered 2026-11-18; research only)",
           "Data %s .. %s: %d sessions, %d usable nights, %d skipped (missing candles)%s"
           % (days[0], days[-1], len(days), len(rows), skipped,
              "" if valid else "  ** INVALID: under %d nights **" % MIN_NIGHTS),
           "Pass: >= %d trades, mean net >= +%.2f%%, t >= %.1f, both halves > 0, > 0 from %s"
           % (MIN_TRADES, MIN_MEAN, MIN_T, RECENT_FROM),
           "Costs about %.3f%% a round trip + carry %.3f%% a weeknight at NIFTY 25,000"
           % (cost_pct(25000, 25000, 0), CARRY_PA / 365), "",
           "%-30s %6s %9s %9s %7s %8s %8s %9s %6s  %s" % ("variant", "trades", "net %", "gross %", "t",
                                                         "half 1", "half 2", "recent", "win%", "verdict")]
    for k, s in enumerate(res):
        verdict = ("PASS" if s["passes"] and valid else "FAIL") if k == 0 else "info"
        out.append("%-30s %6d %9.4f %9.4f %7.2f %8.4f %8.4f %9.4f %6.1f  %s" % (
            s["name"], s["n"], s["mean"], s["gross"], s["t"], s["h1"], s["h2"], s["recent"], s["win"], verdict))
    out.append("")
    out.append("Five worst nights (gross %): " + ", ".join(
        "%s %.2f" % (r[0], r[2]) for r in sorted(rows, key=lambda r: r[2])[:5]))
    a = res[0]
    if a["n"]:
        out.append("Net Rs per lot at NIFTY 25,000: %.0f a night" % (a["mean"] / 100 * 25000 * LOT))
    out.append("")
    if not valid:
        out.append("DECISION  INVALID: not enough nights; no verdict.")
    elif a["passes"]:
        out.append("DECISION  PASSES: propose a separate NIFTY-futures paper book for 60 sessions (needs the user's OK).")
    else:
        out.append("DECISION  FAILS: the overnight hold is dropped as pre-registered (no re-tuning).")
    print("\n".join(out))


if __name__ == "__main__":
    sys.exit(main())
