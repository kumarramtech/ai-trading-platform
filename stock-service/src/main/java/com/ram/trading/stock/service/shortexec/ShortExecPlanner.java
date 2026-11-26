package com.ram.trading.stock.service.shortexec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure decisions of the intraday short executor (no database, no broker). The paper book decides; this copies:
 * <ul>
 *   <li>ENTER: a paper short that is OPEN today and not copied yet, while fewer than {@code maxPositions} of this
 *       executor's shorts are open or being entered, before {@code entryUntil}, and only when the book is not paused.
 *       Paper order is kept (FADE: biggest move first; F&amp;O exclusion: as recorded). Quantity floor(notional / last
 *       price); a SELL limit at most buffer % below the last price; a protective buy stop {@code stopPct} % above;</li>
 *   <li>COVER: an open short whose paper trade is CLOSED (copying the paper exit: its stop, day stop or time exit), or
 *       everything at {@code forceCover} (before the broker's 15:20 intraday square-off). In DRY_RUN a last price at or
 *       above the protective stop also covers it (in LIVE the broker's stop order does that).</li>
 * </ul>
 */
final class ShortExecPlanner {

    private ShortExecPlanner() {
    }

    /** A paper short of today (status OPEN or CLOSED; exitReason as the paper book wrote it). */
    record Paper(long id, String symbol, String status, String exitReason) {
    }

    /** One of this executor's shorts today that is not final: status ENTERING, OPEN or EXITING. */
    record Position(long id, String symbol, long paperId, int qty, Double stop, String status) {
    }

    record Rules(int maxPositions, double notional, double stopPct, double bufferPct, LocalTime entryUntil,
                 LocalTime forceCover) {
    }

    record Enter(Paper paper, int qty, double ltp, double limit, Double stop) {
        double value() {
            return qty * ltp;
        }
    }

    record Cover(Position position, String reason, double ltp) {
    }

    /** A paper short this executor will not copy today (recorded, so it is not looked at again). */
    record Skip(Paper paper, String reason) {
    }

    /** {@code notes}: passing reasons (no price yet), retried next minute. */
    record Plan(List<Enter> enters, List<Cover> covers, List<Skip> skips, List<String> notes) {
    }

    /**
     * @param paper   today's paper shorts, in paper order
     * @param mine    this executor's non-final shorts today
     * @param copied  paper ids this executor already acted on today (any status, including skipped or rejected)
     * @param ltp     last prices by symbol
     * @param tick    tick sizes in rupees by symbol
     * @param paused  why new entries are blocked (book loss limit), or null
     */
    static Plan plan(List<Paper> paper, List<Position> mine, Set<Long> copied, Map<String, Double> ltp,
                     Map<String, Double> tick, LocalTime now, Rules r, String paused, boolean dryRun) {
        List<Enter> enters = new ArrayList<>();
        List<Cover> covers = new ArrayList<>();
        List<Skip> skips = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean late = !now.isBefore(r.forceCover());

        for (Position p : mine) {
            if (!"OPEN".equals(p.status())) {
                continue; // ENTERING / EXITING: the order checks finish them
            }
            Double px = ltp.get(p.symbol());
            Paper src = paper.stream().filter(x -> x.id() == p.paperId()).findFirst().orElse(null);
            if (late) {
                covers.add(new Cover(p, "TIME " + r.forceCover(), px == null ? 0 : px));
            } else if (src != null && "CLOSED".equals(src.status())) {
                covers.add(new Cover(p, "PAPER " + (src.exitReason() == null ? "CLOSED" : src.exitReason()),
                        px == null ? 0 : px));
            } else if (dryRun && p.stop() != null && px != null && px >= p.stop()) {
                covers.add(new Cover(p, "STOP", px));
            }
        }

        if (late || !now.isBefore(r.entryUntil())) {
            return new Plan(enters, covers, skips, notes);
        }
        long busy = mine.stream().filter(p -> !"EXITING".equals(p.status())).count()
                - covers.size();
        for (Paper p : paper) {
            if (!"OPEN".equals(p.status()) || copied.contains(p.id())) {
                continue;
            }
            if (paused != null) {
                skips.add(new Skip(p, "book paused: " + paused));
                continue;
            }
            if (busy >= r.maxPositions()) {
                skips.add(new Skip(p, r.maxPositions() + " positions already open"));
                continue;
            }
            Double px = ltp.get(p.symbol()), t = tick.get(p.symbol());
            if (px == null || px <= 0 || t == null || t <= 0) {
                notes.add(p.symbol() + ": no price or tick yet (retried next minute)");
                continue;
            }
            int qty = (int) Math.floor(r.notional() / px);
            if (qty < 1) {
                skips.add(new Skip(p, String.format("one share Rs %.0f is above the Rs %.0f size", px, r.notional())));
                continue;
            }
            double limit = align(px * (1 - r.bufferPct() / 100), t, RoundingMode.CEILING);
            Double stop = r.stopPct() > 0 ? align(px * (1 + r.stopPct() / 100), t, RoundingMode.CEILING) : null;
            enters.add(new Enter(p, qty, px, limit, stop));
            busy++;
        }
        return new Plan(enters, covers, skips, notes);
    }

    /** Why new entries stop: the book's total loss since its start reached the limit, or a losing streak. */
    static String pauseReason(List<Double> closedNets, double maxBookLoss, int maxLossStreak) {
        double total = closedNets.stream().mapToDouble(Double::doubleValue).sum();
        if (maxBookLoss > 0 && total <= -maxBookLoss) {
            return String.format("total Rs %.0f reached the Rs -%.0f book stop", total, maxBookLoss);
        }
        if (maxLossStreak > 0 && closedNets.size() >= maxLossStreak
                && closedNets.subList(closedNets.size() - maxLossStreak, closedNets.size()).stream().allMatch(x -> x < 0)) {
            return maxLossStreak + " losing trades in a row";
        }
        return null;
    }

    /**
     * Upstox intraday (MIS) equity charges for one short round trip (sell to open, buy to cover), the same rates as the
     * FADE paper book's IntradayChargesCalculator: brokerage 0.1% capped at Rs 20 per order, STT 0.025% on the sell,
     * NSE transaction 0.00297% and SEBI 0.0001% on both sides, stamp duty 0.003% on the buy, GST 18% on brokerage +
     * transaction + SEBI. Check against an Upstox contract note whenever rates change.
     */
    record Charges(double brokerage, double stt, double exchangeTxn, double sebiFee, double stampDuty, double gst,
                   double total) {
    }

    static Charges shortCharges(int qty, double entry, double exit) {
        if (qty <= 0 || entry <= 0 || exit <= 0) {
            return new Charges(0, 0, 0, 0, 0, 0, 0);
        }
        double sell = qty * entry, buy = qty * exit, turnover = sell + buy;
        double brokerage = Math.min(sell * 0.1 / 100, 20) + Math.min(buy * 0.1 / 100, 20);
        double stt = sell * 0.025 / 100;
        double exchange = turnover * 0.00297 / 100;
        double sebi = turnover * 0.0001 / 100;
        double stamp = buy * 0.003 / 100;
        double gst = (brokerage + exchange + sebi) * 18 / 100;
        return new Charges(round(brokerage), round(stt), round(exchange), round(sebi), round(stamp), round(gst),
                round(brokerage + stt + exchange + sebi + stamp + gst));
    }

    /** A short's [gross, all charges, net = gross - charges] in rupees. */
    static double[] shortNet(int qty, double entry, double exit) {
        double gross = qty * (entry - exit);
        double charges = shortCharges(qty, entry, exit).total();
        return new double[]{round(gross), charges, round(gross - charges)};
    }

    /** The price on the stock's tick, rounded in the given direction. */
    static double align(double price, double tick, RoundingMode mode) {
        BigDecimal t = BigDecimal.valueOf(tick);
        BigDecimal clean = BigDecimal.valueOf(price).setScale(6, RoundingMode.HALF_UP);
        return clean.divide(t, 0, mode).multiply(t).doubleValue();
    }

    static double round(double x) {
        return Math.round(x * 100) / 100.0;
    }
}
