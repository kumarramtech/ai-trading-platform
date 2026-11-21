#!/usr/bin/env python3
"""
Builds research/index_events_other.tsv: the SAME parse as index_events_parse.py (pre-registered 2026-11-18) but for
every NSE index in the scheduled reviews EXCEPT NIFTY 50, NIFTY Next 50 and NIFTY Midcap 150 (those were the
2026-11-18 study), including sectoral / thematic / strategy sections of the scheduled reviews. Built 2026-11-21
before any prices were looked at, for the out-of-sample check of the passed rule B1 (see index_other_study.py).
Original description follows.

Input: a directory of NSE Indices press releases (niftyindices.com/Press_Release/ind_prsDDMMYYYY.pdf) converted
with `pdftotext -raw` (one table row per line: "1 Company Name Ltd. SYMBOL"). Keeps only scheduled reviews
("periodic review" / "semi-annual review", decided by the Index Maintenance Sub-Committee) and, inside them,
only the sections for NIFTY 50, NIFTY Next 50 and NIFTY Midcap 150. Parts on ad-hoc replacements (scheme of
arrangement etc.) and sectoral/thematic reviews are skipped. These indices have a fixed size, so a section whose
exclusion and inclusion counts differ is reported on stderr and skipped.
Output columns: index, side (ADD/DROP), symbol (as printed in the release), announce_date (release date),
change_close_date (the "close of" date: the last close before the change), source file.
Usage: python3 index_events_parse.py <dir_with_txt> > index_events.tsv
"""
import datetime as dt
import os
import re
import sys

MONTH = r"(January|February|March|April|May|June|July|August|September|October|November|December)"
SKIP = ("NIFTY 50", "NIFTY NEXT 50", "NIFTY MIDCAP 150")
HEAD = re.compile(r"^\s*(?:\d+|[a-zA-Z])[\).]\s*(NIFTY[ A-Za-z0-9&:\-]*?)\s*(?:index)?\s*:?\s*$", re.I)
SCOPE = re.compile(r"replacements? on account of", re.I)
ROW = re.compile(r"^\s*\d+\.?\s+.*?\s((?=[A-Z0-9&\-]*[A-Z])[A-Z0-9][A-Z0-9&\-]{1,19})\s*$")


def parse(path):
    txt = open(path, encoding="utf-8", errors="replace").read()
    flat = re.sub(r"\s+", " ", txt)
    if "Index Maintenance Sub" not in flat or not re.search(r"periodic review|semi-annual review", flat, re.I):
        return []
    m = re.search(r"close of " + MONTH + r"\s+(\d{1,2}),?\s*(\d{4})", flat)
    if not m:
        return []
    close = dt.datetime.strptime("%s %s %s" % m.groups(), "%B %d %Y").date()
    fd = re.search(r"ind_prs(\d{2})(\d{2})(\d{4})", os.path.basename(path))
    ann = dt.date(int(fd.group(3)), int(fd.group(2)), int(fd.group(1)))
    secs, sec, side, scope, pending = {}, None, None, True, None
    for line in txt.splitlines():
        if SCOPE.search(line):
            scope = bool(re.search(r"semi-annual review|periodic review|annual review|quarterly review", line, re.I))
            sec = None
            continue
        h = HEAD.match(line)
        if h:
            name = re.sub(r"\s+", " ", h.group(1)).upper().strip()
            empty = name in secs and not (secs[name]["DROP"] or secs[name]["ADD"])
            sec = name if scope and name not in SKIP and (name not in secs or empty) else None
            side, pending = None, None
            if sec:
                secs[sec] = {"DROP": [], "ADD": []}
            continue
        if sec is None:
            continue
        low = line.lower()
        if "being excluded" in low:
            side = "DROP"
        elif "being included" in low:
            side = "ADD"
        elif low.startswith("note") or "applicable to" in low:
            side = None
        elif side:
            if re.match(r"^\s*\d+\.?\s*$", line):
                pending = line  # a row whose company name wraps onto the next lines
                continue
            if pending is not None:
                line, pending = pending + " " + line, (pending + " " + line if pending.count(" ") < 12 else None)
            r = ROW.match(line)
            if r:
                pending = None
                if r.group(1) not in secs[sec][side]:
                    secs[sec][side].append(r.group(1))
    out = []
    for sec, sides in secs.items():
        if len(sides["DROP"]) != len(sides["ADD"]):
            print("UNBALANCED %s %s: drop %s | add %s" % (os.path.basename(path), sec, " ".join(sides["DROP"]),
                                                        " ".join(sides["ADD"])), file=sys.stderr)
            continue
        for side, syms in sides.items():
            out += [(sec, side, s, ann, close, os.path.basename(path)) for s in syms]
    return out


def main():
    rows = []
    for f in sorted(os.listdir(sys.argv[1])):
        if f.endswith(".txt"):
            rows += parse(os.path.join(sys.argv[1], f))
    print("index\tside\tsymbol\tannounce_date\tchange_close_date\tsource")
    for r in sorted(set(rows), key=lambda r: (r[3], r[0], r[1], r[2])):
        print("\t".join(str(x) for x in r))


if __name__ == "__main__":
    main()
