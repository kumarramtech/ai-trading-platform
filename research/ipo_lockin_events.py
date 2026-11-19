#!/usr/bin/env python3
"""
IPO lock-in study, step 1: event list (rules approved by the user 2026-11-19; built from NSE CM bhavcopies only,
no prices are used).

Listing  the first trading day an ISIN appears in the NSE CM bhavcopy (any series), 2024-01-01 .. 2026-11-18,
         kept only when that first series is EQ or BE (mainboard; SME SM/ST, REIT/InvIT etc. are left out), the
         first day is on/after 2024-03-01 (two months of look-back so old stocks are not mistaken for new), and
         no earlier ISIN of the same company (first 7 characters, e.g. INE758T) was seen (splits, renames).
         Demergers and relistings are removed by hand afterwards (EXCLUDE below, each with its reason).
Dates    allotment = 2 trading days before listing (T+3 timetable). Event day = first trading day on/after
         allotment + 30 days (L1, anchor 50%), + 90 days (L2, anchor rest), + 6 calendar months (L3, pre-IPO
         non-promoter shareholders). Kept when the event day is 2024-10-01 .. 2026-11-18.
Usage    python3 ipo_lockin_events.py > ipo_lockin_events.tsv   (stderr: the listing table for the hand check)
"""
import csv
import datetime as dt
import io
import os
import sys
import time
import urllib.error
import urllib.request
import zipfile

UA = "research-client/1.0"
CACHE = "/tmp/cm_bhav"
FROM, TO = dt.date(2024, 1, 1), dt.date(2026, 11, 18)
LIST_FROM = dt.date(2024, 3, 1)
EV_FROM, EV_TO = dt.date(2024, 10, 1), dt.date(2026, 11, 18)
UDIFF_FROM = dt.date(2024, 7, 8)
# Hand check of the listing table (2026-11-19, before any prices): not an IPO with a lock-in.
# 1) batch days: any day with >= BATCH new listings is NSE taking on companies already listed on BSE only.
BATCH = 10
# 2) by name: demergers / schemes, relistings, companies already listed on BSE or an SME platform.
_DEMERGER = ("SCILAL BOROSCI RAYMONDLSL REVATHIEQU SANOFICONR OCCLLTD ROSSTECH ITCHOTELS ONESOURCE JUBLCPL "
             "BAJAJINDEF PRABHA BLUSPRING DIGITIDE ENRIN ABLBL RAYMONDREL CHEMBONDCH STLNETWORK CPEDU PIRAMALFIN "
             "TMCV ADVENTHTL KSR SKFINDUS BUILDPRO KWIL DSFCL DCMSIL VAML VISL VOGL VEDPOWER AGL GSPLTRANS PRIMO")
_RELIST = "NTL UEL CCCL ATLASCYCLE SWANDEF WAAREEINDO VIVIMEDLAB AHLWEST DSKULKARNI PROTEAN"
_ALREADY_LISTED = ("NIRAJISPAT LANCORHOL VHLTD BESTAGRO SINCLAIR AIIL UDAICEMENT GLOSTERLTD BALUFORGE FILATFASH RETAIL "
                   "AGIIL RELTD AVANTEL RPEL RUBFILA MANCREDIT SUYOG GLOBALE MFML SURAJLTD EUREKAFORB XTGLOBAL INDOUS "
                   "UYFINCORP SRD LLOYDSENT VINCOFE AFFORDABLE AVL MASTERTR KPEL KMEW RACLGEAR ODIGMA ANUHPHR "
                   "DDEVPLSTIK NAVKARURB SUPREME MOSCHIP MCLOUD NIBE ORCHASP NATCAPSUQ GUJTHEM BHARATSE CURAA ISHANCH "
                   "WAAREERTL CIFL TINNARUBR RAJOOENG KALYANI PICCADIL FISCHER BELLACASA ARFIN SGFIN EFCIL GCSL "
                   "RNBDENIMS SGMART TIGERLOGS RHETAN FINKURVE SYSTMTXC MARKOLINES MONEYBOXX MODIS SHILCTECH PAUSHAKLTD "
                   "JAYKAY MODINATUR RUDRA ALGOQUANT HALDER ADVAIT CEINSYS BONLON AARNAV TCC INA AEPL MARSONS SINGERIND "
                   "RMC NIMBSPROJ SAHLIBHFI SAPPL SONAL TECHNVISN THAKDEV ZFSTEERING NEAGI SAYAJIHOTL GRADIENTE "
                   "MERCURYEV RIR SHUKRAPHAR TECHNOCRAF ADDIND LAKSHMIMIL MPDL MYSORPETRO NICCOPAR SRIKPRIND STLSTRINF "
                   "UPHOT WHBRADY YOGI BLEL SAMBANDAM APOORVA VEEFIN")
EXCLUDE = {}
for _names, _why in ((_DEMERGER, "demerger/scheme"), (_RELIST, "relisting"), (_ALREADY_LISTED, "already listed on BSE/SME")):
    for _s in _names.split():
        EXCLUDE[_s] = _why


def http(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read()
        except urllib.error.HTTPError as ex:
            if ex.code in (400, 403, 404):
                return None
            time.sleep(10 + 10 * attempt)
        except Exception:
            time.sleep(5 + 5 * attempt)
    return None


def bhav(day):
    """[(symbol, series, isin, name)] for a trading day, or None (holiday / missing)."""
    p = os.path.join(CACHE, day.isoformat() + ".csv")
    if os.path.exists(p):
        text = open(p, encoding="utf-8").read()
        return None if text == "NONE" else list(csv.reader(io.StringIO(text)))
    if day >= UDIFF_FROM:
        url = "https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_%s_F_0000.csv.zip" % day.strftime("%Y%m%d")
    else:
        url = "https://nsearchives.nseindia.com/content/historical/EQUITIES/%d/%s/cm%s%sbhav.csv.zip" % (
            day.year, day.strftime("%b").upper(), day.strftime("%d"), day.strftime("%b%Y").upper())
    data = http(url)
    time.sleep(0.4)
    rows = None
    if data:
        try:
            z = zipfile.ZipFile(io.BytesIO(data))
            lines = z.read(z.namelist()[0]).decode("utf-8", "replace").splitlines()
            head = [h.strip() for h in lines[0].split(",")]
            si = head.index("TckrSymb") if "TckrSymb" in head else head.index("SYMBOL")
            sr = head.index("SctySrs") if "SctySrs" in head else head.index("SERIES")
            ii = head.index("ISIN")
            ni = head.index("FinInstrmNm") if "FinInstrmNm" in head else None
            rows = []
            for l in csv.reader(lines[1:]):
                if len(l) > max(si, sr, ii):
                    rows.append([l[si].strip(), l[sr].strip(), l[ii].strip(), l[ni].strip() if ni is not None else ""])
        except Exception:
            rows = None
    with open(p, "w", encoding="utf-8") as f:
        if rows is None:
            f.write("NONE")
        else:
            csv.writer(f).writerows(rows)
    return rows


def main():
    os.makedirs(CACHE, exist_ok=True)
    first = {}          # isin -> (day, symbol, series, name)
    companies = {}      # isin[:7] -> first day seen
    cal = []
    d = FROM
    while d <= TO:
        if d.weekday() < 5:
            rows = bhav(d)
            if rows:
                cal.append(d)
                for sym, ser, isin, name in rows:
                    if isin and isin not in first:
                        first[isin] = (d, sym, ser, name)
                    if isin:
                        companies.setdefault(isin[:7], d)
        d += dt.timedelta(days=1)
    print("trading days %d, %s .. %s" % (len(cal), cal[0], cal[-1]), file=sys.stderr)

    def on_or_after(x):
        for c in cal:
            if c >= x:
                return c
        return None

    def add_months(x, m):
        y, mo = x.year + (x.month - 1 + m) // 12, (x.month - 1 + m) % 12 + 1
        last = [31, 29 if y % 4 == 0 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][mo - 1]
        return dt.date(y, mo, min(x.day, last))

    listings = []
    for isin, (day, sym, ser, name) in sorted(first.items(), key=lambda kv: kv[1][0]):
        if day < LIST_FROM or ser not in ("EQ", "BE") or not isin.startswith("INE"):
            continue
        if companies[isin[:7]] < day:
            continue
        listings.append((day, sym, ser, isin, name))
    per_day = {}
    for day, *_ in listings:
        per_day[day] = per_day.get(day, 0) + 1
    for day, sym, *_ in listings:
        if per_day[day] >= BATCH:
            EXCLUDE.setdefault(sym, "batch day (%d listings)" % per_day[day])
    print("mainboard new listings %d, excluded by hand %d, kept %d" % (
        len(listings), sum(1 for l in listings if l[1] in EXCLUDE), sum(1 for l in listings if l[1] not in EXCLUDE)),
        file=sys.stderr)
    print("LISTING\tsymbol\tseries\tisin\tname\texcluded", file=sys.stderr)
    for day, sym, ser, isin, name in listings:
        print("LISTING\t%s\t%s\t%s\t%s\t%s\t%s" % (day, sym, ser, isin, name, EXCLUDE.get(sym, "")), file=sys.stderr)

    print("index\tside\tsymbol\tannounce_date\tchange_close_date\tsource")
    n = {}
    for day, sym, ser, isin, name in listings:
        if sym in EXCLUDE:
            continue
        i = cal.index(day)
        if i < 2:
            continue
        allot = cal[i - 2]
        for test, free in (("L1", allot + dt.timedelta(days=30)), ("L2", allot + dt.timedelta(days=90)),
                           ("L3", add_months(allot, 6))):
            e = on_or_after(free)
            if e and EV_FROM <= e <= EV_TO:
                print("IPO LOCKIN\t%s\t%s\t%s\t%s\tlisted %s %s, allotment ~%s" % (test, sym, day, e, day, isin, allot))
                n[test] = n.get(test, 0) + 1
    print("events (before eligibility): %s" % n, file=sys.stderr)


if __name__ == "__main__":
    main()
