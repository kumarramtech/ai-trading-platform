#!/usr/bin/env python3
"""
Idea 1 (2026-11-18): NIFTY weekly iron condor, with and without a "paid well" filter
(pre-registered 2026-11-18, rules approved by the user before any option data was looked at; research only).

  Data      NSE F&O bhavcopy (official daily files: old fo<DD><MON><YYYY>bhav.csv.zip up to 2024-07-05,
            UDiFF BhavCopy_NSE_FO_0_0_0_<YYYYMMDD>_F_0000.csv.zip after), NIFTY 50 and India VIX daily closes
            from Upstox. Weekly expiries Jan 2022 .. 2026-11-17.
  Entry     at the close of the first trading day after an expiry, on the next (nearest) expiry.
            Spot = NIFTY close that day. Sell the first call strike >= spot x 1.02 and the first put strike
            <= spot x 0.98; buy the first call strike >= spot x 1.035 and the first put strike <= spot x 0.965.
  Prices    the contract's daily close. A price is MISSING if the contract is absent, its close <= 0 or it did
            not trade that day (volume 0, so the close is stale). Every leg pays slippage of max(0.5 pt, 3% of
            its price) on entry and on exit. Skip the week (not counted as missing) if credit after slippage
            < 10 pts.
  Exit      held to expiry, settled at intrinsic from NIFTY's close on the expiry day (NSE settles at the
            close); the exit also pays the slippage above (shorts bought back at intrinsic + slip, longs sold
            at max(0, intrinsic - slip)).
  Variant A trade only if India VIX (entry-day close) >= 1.2 x NIFTY 10-day realised volatility (stdev of the
            last 10 daily log returns x sqrt(252) x 100), and skip any week whose holding window
            (entry day, expiry] contains a Union Budget, an RBI MPC decision or general-election results
            (fixed list below).
  Variant B every week (the paper book's rule).
  Costs     the paper book's model per lot of 65: Rs 30 x 8 orders, STT 0.15% of premium sold, exchange 0.03503%
            of turnover, SEBI Rs 10/crore, stamp 0.003% of buys, GST 18% on brokerage + exchange + SEBI.
  Pass      (each variant) >= 60 trades; mean net >= Rs 200 per lot per trade; t >= 2.0; both date halves > 0;
            mean > 0 for entries from 2024-11-20; max drawdown <= one year's average profit.
  INVALID   if more than 5% of weeks miss a needed price.
  Decision  A passes -> propose a second, filtered paper book beside the current one (user's OK).
            Only B passes -> keep the paper book as it is. Neither -> retire the condor book after its
            current trade.
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
LOT = 65
SHORT_PCT, LONG_PCT, MIN_CREDIT = 2.0, 3.5, 10.0
VIX_RATIO = 1.2
CACHE = "/tmp/bhav_nifty"
UA = "research-client/1.0"

EVENTS = [dt.date.fromisoformat(d) for d in (
    # Union Budget
    "2022-02-01", "2023-02-01", "2024-02-01", "2024-07-23", "2025-02-01", "2026-02-01",
    # RBI MPC decisions (2026 from the published schedule as known; dates after mid-2026 assumed)
    "2022-02-10", "2022-04-08", "2022-05-04", "2022-06-08", "2022-08-05", "2022-09-30", "2022-12-07",
    "2023-02-08", "2023-04-06", "2023-06-08", "2023-08-10", "2023-10-06", "2023-12-08",
    "2024-02-08", "2024-04-05", "2024-06-07", "2024-08-08", "2024-10-09", "2024-12-06",
    "2025-02-07", "2025-04-09", "2025-06-06", "2025-08-06", "2025-10-01", "2025-12-05",
    "2026-02-06", "2026-04-08", "2026-06-05", "2026-08-06", "2026-10-01",
    # general-election results
    "2024-06-04",
)]


# ---------------------------------------------------------------- data
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


def upstox_daily(tok, key):
    out = {}
    for year in range(FROM.year - 1, TO.year + 1):
        a, b = dt.date(year, 1, 1), min(TO, dt.date(year, 12, 31))
        raw = http("https://api.upstox.com/v3/historical-candle/" + urllib.parse.quote(key, safe="")
                   + "/days/1/%s/%s" % (b, a), {"Authorization": "Bearer " + tok, "Accept": "application/json"})
        time.sleep(0.9)
        if raw:
            for c in json.loads(raw)["data"]["candles"]:
                out[dt.datetime.fromisoformat(c[0]).date()] = float(c[4])
    return out


def bhav(day):
    """{(expiry, strike, 'CE'|'PE'): (close, volume)} for NIFTY index options, or None if no file."""
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, "%s.json" % day)
    if os.path.exists(path):
        raw = json.load(open(path))
        return None if raw is None else {(dt.date.fromisoformat(e), float(k), t): (c, v) for e, k, t, c, v in raw}
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
                ix = [h.index(x) for x in ("INSTRUMENT", "SYMBOL", "EXPIRY_DT", "STRIKE_PR", "OPTION_TYP", "CLOSE", "CONTRACTS")]
                for r in rd:
                    if len(r) > max(ix) and r[ix[1]] == "NIFTY" and r[ix[0]] == "OPTIDX":
                        e = dt.datetime.strptime(r[ix[2]], "%d-%b-%Y").date()
                        rows.append((str(e), float(r[ix[3]]), r[ix[4]], float(r[ix[5]] or 0), float(r[ix[6]] or 0)))
            else:
                ix = [h.index(x) for x in ("FinInstrmTp", "TckrSymb", "XpryDt", "StrkPric", "OptnTp", "ClsPric", "TtlTradgVol")]
                for r in rd:
                    if len(r) > max(ix) and r[ix[1]] == "NIFTY" and r[ix[0]] == "IDO":
                        rows.append((r[ix[2]], float(r[ix[3]]), r[ix[4]], float(r[ix[5]] or 0), float(r[ix[6]] or 0)))
        except Exception:
            rows = None
    json.dump(rows, open(path, "w"))
    return None if rows is None else {(dt.date.fromisoformat(e), k, t): (c, v) for e, k, t, c, v in rows}


# ---------------------------------------------------------------- rules
def slip(px):
    return max(0.5, 0.03 * px)


def charges(sold, bought, qty=LOT, orders=8, per_order=30.0, stt_pct=0.15):
    sell, buy = sold * qty, bought * qty
    turn = sell + buy
    brokerage = orders * per_order
    exch, sebi = turn * 0.0003503, turn * 1e-6
    return brokerage + sell * stt_pct / 100 + exch + sebi + buy * 0.00003 + 0.18 * (brokerage + exch + sebi)


def realised_vol(closes, days, i):
    if i < 10:
        return None
    rets = [math.log(closes[days[j]] / closes[days[j - 1]]) for j in range(i - 9, i + 1)]
    mu = sum(rets) / 10
    return math.sqrt(sum((r - mu) ** 2 for r in rets) / 9) * math.sqrt(252) * 100


def summary(name, trades):
    """trades: list of (entry_day, net_rs)."""
    n = len(trades)
    if n < 2:
        return "%-30s %5d  too few" % (name, n), False
    s = sorted(trades)
    nets = [x[1] for x in s]
    mean = sum(nets) / n
    sd = math.sqrt(sum((x - mean) ** 2 for x in nets) / (n - 1))
    t = mean / (sd / math.sqrt(n)) if sd > 0 else 0
    h1 = sum(nets[:n // 2]) / (n // 2)
    h2 = sum(nets[n // 2:]) / (n - n // 2)
    rc = [x[1] for x in s if x[0] >= RECENT_FROM]
    recent = sum(rc) / len(rc) if rc else float("nan")
    eq = peak = dd = 0.0
    for x in nets:
        eq += x
        peak = max(peak, eq)
        dd = max(dd, peak - eq)
    years = max(0.25, (s[-1][0] - s[0][0]).days / 365.25)
    per_year = sum(nets) / years
    win = 100.0 * sum(1 for x in nets if x > 0) / n
    ok = (n >= 60 and mean >= 200 and t >= 2.0 and h1 > 0 and h2 > 0 and recent > 0
          and per_year > 0 and dd <= per_year)
    line = "%-30s %5d %8.0f %6.2f %8.0f %8.0f %8.0f %9.0f %9.0f %6.0f %5.1f" % (
        name, n, mean, t, h1, h2, recent, dd, per_year, min(nets), win)
    return line, ok


def main():
    tok = urllib.request.urlopen("http://localhost:9090/upstox/auth/token", timeout=15).read().decode().strip()
    nifty = upstox_daily(tok, "NSE_INDEX|Nifty 50")
    vix = upstox_daily(tok, "NSE_INDEX|India VIX")
    days = sorted(d for d in nifty if d <= TO)
    pos = {d: i for i, d in enumerate(days)}

    def next_day(d):
        later = [x for x in days if x > d]
        return later[0] if later else None

    first = bhav(next(d for d in days if d >= FROM))
    expiry = min(e for e, _, _ in first if e >= FROM)
    weeks, missing, low_credit = 0, [], 0
    tr = {"A": [], "B": []}
    log = []
    while True:
        entry = next_day(expiry)
        if entry is None:
            break
        book = bhav(entry)
        probe = book if book else bhav(next_day(entry) or entry)
        if not probe:
            break
        nxt = min((e for e, _, _ in probe if e > entry), default=None)
        if nxt is None or nxt > TO:
            break
        weeks += 1
        expiry_prev, expiry = expiry, nxt
        if not book or entry not in nifty or expiry not in nifty:
            missing.append(entry)
            continue
        spot = nifty[entry]
        strikes = sorted({k for e, k, _ in book if e == expiry})
        calls = [k for k in strikes if k >= spot * (1 + SHORT_PCT / 100)]
        wings_c = [k for k in strikes if k >= spot * (1 + LONG_PCT / 100)]
        puts = [k for k in strikes if k <= spot * (1 - SHORT_PCT / 100)]
        wings_p = [k for k in strikes if k <= spot * (1 - LONG_PCT / 100)]
        if not (calls and wings_c and puts and wings_p):
            missing.append(entry)
            continue
        sc, lc, sp, lp = calls[0], wings_c[0], puts[-1], wings_p[-1]
        legs = [book.get((expiry, sc, "CE")), book.get((expiry, lc, "CE")),
                book.get((expiry, sp, "PE")), book.get((expiry, lp, "PE"))]
        if any(x is None or x[0] <= 0 or x[1] <= 0 for x in legs):
            missing.append(entry)
            continue
        scp, lcp, spp, lpp = (x[0] for x in legs)
        sc_in, sp_in = scp - slip(scp), spp - slip(spp)
        lc_in, lp_in = lcp + slip(lcp), lpp + slip(lpp)
        credit = sc_in + sp_in - lc_in - lp_in
        if credit < MIN_CREDIT:
            low_credit += 1
            continue
        settle = nifty[expiry]
        iv = [max(0.0, settle - sc), max(0.0, settle - lc), max(0.0, sp - settle), max(0.0, lp - settle)]
        sc_out, sp_out = iv[0] + slip(iv[0]), iv[2] + slip(iv[2])
        lc_out, lp_out = max(0.0, iv[1] - slip(iv[1])), max(0.0, iv[3] - slip(iv[3]))
        debit = sc_out + sp_out - lc_out - lp_out
        sold = sc_in + sp_in + lc_out + lp_out
        bought = lc_in + lp_in + sc_out + sp_out
        net = (credit - debit) * LOT - charges(sold, bought)
        tr["B"].append((entry, net))
        rv = realised_vol(nifty, days, pos[entry])
        event = any(entry < ev <= expiry for ev in EVENTS)
        filt = rv is not None and entry in vix and vix[entry] >= VIX_RATIO * rv and not event
        if filt:
            tr["A"].append((entry, net))
        log.append("%s exp %s spot %.1f %g/%g CE %g/%g PE credit %.2f settle %.1f net %.0f%s" % (
            entry, expiry, spot, sc, lc, sp, lp, credit, settle, net, "  [A]" if filt else ""))

    valid = weeks > 0 and len(missing) <= 0.05 * weeks
    out = ["NIFTY IRON CONDOR, VIX FILTER (idea 1, pre-registered 2026-11-18; research only)",
           "NIFTY daily closes %d, India VIX daily closes %d" % (len(nifty), len(vix)),
           "Weeks %d, missing a needed price %d (%.1f%%), skipped for credit < %g pts %d%s" % (
               weeks, len(missing), 100.0 * len(missing) / max(1, weeks), MIN_CREDIT, low_credit,
               "" if valid else "  ** INVALID: over 5% of weeks missing **"),
           "Pass: >= 60 trades, mean net >= Rs 200/lot, t >= 2.0, both halves > 0, > 0 from %s, "
           "max drawdown <= one year's profit" % RECENT_FROM, "",
           "%-30s %5s %8s %6s %8s %8s %8s %9s %9s %6s %5s  %s" % (
               "variant (Rs per lot)", "n", "mean", "t", "half 1", "half 2", "recent", "max DD", "per year",
               "worst", "win%", "verdict")]
    res = {}
    for v, lbl in (("A", "A VIX filter (DECIDES)"), ("B", "B every week (DECIDES)")):
        line, ok = summary(lbl, tr[v])
        res[v] = ok and valid
        out.append(line + ("  PASS" if res[v] else "  FAIL"))
    out.append("")
    if not valid:
        out.append("DECISION  INVALID: too many weeks missing prices; no verdict.")
    elif res["A"]:
        out.append("DECISION  A passes: propose a second, filtered paper book beside the current one (user's OK).")
    elif res["B"]:
        out.append("DECISION  only B passes: keep the condor paper book as it is.")
    else:
        out.append("DECISION  NEITHER passes: retire the condor paper book after its current trade (no re-tuning).")
    if missing:
        out.append("Missing weeks (entry day): " + ", ".join(str(d) for d in missing[:40]))
    print("\n".join(out))
    with open("/tmp/condor_trades.txt", "w") as f:
        f.write("\n".join(log) + "\n")


if __name__ == "__main__":
    sys.exit(main())
