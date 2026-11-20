package com.ram.trading.stock.service.momexec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure decisions of the momentum executor (no database, no broker): which delivery orders bring this executor's own
 * book in line with the paper momentum book. The paper book decides; this only copies its decisions:
 * <ul>
 *   <li>SELL every share of a stock the book holds that the paper book no longer holds;</li>
 *   <li>BUY a stock the paper book holds and the book does not: round(paper quantity x scale) shares,
 *       scale = capital / paper equity (1 when no capital is set, i.e. an exact copy);</li>
 *   <li>a paper top-up (its quantity rose since this book last looked) buys the scaled difference;</li>
 *   <li>never resizes otherwise, so day-to-day price moves create no orders;</li>
 *   <li>limit prices only: buys at most buffer % above the last price, sells at least buffer % below it, on the
 *       stock's own tick;</li>
 *   <li>buys stay within the capital cap (book value + pending buys + this order) and the per-order cap.</li>
 * </ul>
 */
final class MomentumExecPlanner {

    private MomentumExecPlanner() {
    }

    /** One stock in this executor's book: shares held, and the paper quantity seen at its last buy. */
    record BookHolding(String symbol, int qty, int paperQtySeen) {
    }

    record Limits(double capital, double perOrderCap, double bufferPct, int maxOrders) {
    }

    /** An order to place; {@code paperQty} is the paper quantity it answers (stored as "seen" when it fills). */
    record Order(String symbol, String side, int qty, double ltp, double limit, int paperQty, String reason) {
        double value() {
            return qty * limit;
        }
    }

    record Plan(double scale, List<Order> orders, List<String> skipped, Map<String, Integer> seenUpdates) {
    }

    /**
     * @param paper       paper book: symbol -&gt; quantity held now
     * @param paperEquity paper cash + holdings at the last prices
     * @param book        this executor's holdings (shares already filled)
     * @param pendingBuy  shares in buy orders sent and not final yet, by symbol
     * @param pendingSell symbols with a sell order sent and not final yet
     * @param ltp         last prices by symbol (missing = no price)
     * @param tick        tick size in rupees by symbol (missing = no tick: no order)
     */
    static Plan plan(Map<String, Integer> paper, double paperEquity, Map<String, BookHolding> book,
                     Map<String, Integer> pendingBuy, Set<String> pendingSell, Map<String, Double> ltp,
                     Map<String, Double> tick, Limits lim) {
        double scale = lim.capital() > 0 && paperEquity > 0 ? lim.capital() / paperEquity : 1.0;
        List<Order> orders = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();

        // 1. Sells: the paper book no longer holds it.
        for (BookHolding h : new TreeSet<>(book.keySet()).stream().map(book::get).toList()) {
            if (paper.containsKey(h.symbol()) || h.qty() <= 0 || pendingSell.contains(h.symbol())) {
                continue;
            }
            Double px = ltp.get(h.symbol()), t = tick.get(h.symbol());
            if (px == null || px <= 0 || t == null || t <= 0) {
                skipped.add(h.symbol() + " sell: no price or tick (retried next run)");
                continue;
            }
            orders.add(new Order(h.symbol(), "SELL", h.qty(), px, align(px * (1 - lim.bufferPct() / 100), t, false),
                    0, "paper book sold it"));
        }

        // Money already in the book (at the last prices) and in pending buys counts against the capital cap.
        double invested = 0;
        for (BookHolding h : book.values()) {
            Double px = ltp.get(h.symbol());
            invested += h.qty() * (px != null ? px : 0);
        }
        for (Map.Entry<String, Integer> e : pendingBuy.entrySet()) {
            Double px = ltp.get(e.getKey());
            invested += e.getValue() * (px != null ? px : 0);
        }
        // Shares being sold free their money only after the fill; do not spend it in the same run.

        // 2. Buys: new paper holdings, then paper top-ups.
        for (String s : new TreeSet<>(paper.keySet())) {
            int paperQty = paper.get(s);
            BookHolding h = book.get(s);
            int have = (h == null ? 0 : h.qty()) + pendingBuy.getOrDefault(s, 0);
            String reason;
            if (h == null || h.qty() == 0) {
                if (pendingBuy.getOrDefault(s, 0) > 0) {
                    continue; // already being bought
                }
                reason = "paper book bought it";
            } else if (paperQty > h.paperQtySeen()) {
                reason = "paper top-up " + h.paperQtySeen() + " -> " + paperQty;
            } else {
                continue;
            }
            int target = (int) Math.round(paperQty * scale);
            int qty = target - have;
            if (qty <= 0) {
                if (h != null && h.qty() > 0) {
                    seen.put(s, paperQty); // the top-up rounds to nothing at this scale: just remember it
                } else {
                    skipped.add(String.format("%s buy: %d paper shares x scale %.2f rounds to 0", s, paperQty, scale));
                }
                continue;
            }
            Double px = ltp.get(s), t = tick.get(s);
            if (px == null || px <= 0 || t == null || t <= 0) {
                skipped.add(s + " buy: no price or tick (retried next run)");
                continue;
            }
            double limit = align(px * (1 + lim.bufferPct() / 100), t, true);
            if (lim.perOrderCap() > 0 && qty * limit > lim.perOrderCap()) {
                int capped = (int) Math.floor(lim.perOrderCap() / limit);
                if (capped <= 0) {
                    skipped.add(String.format("%s buy: one share Rs %.0f is above the per-order cap Rs %.0f", s, limit,
                            lim.perOrderCap()));
                    continue;
                }
                skipped.add(String.format("%s buy: %d shares cut to %d by the per-order cap", s, qty, capped));
                qty = capped;
            }
            if (lim.capital() > 0 && invested + qty * limit > lim.capital()) {
                int fits = (int) Math.floor((lim.capital() - invested) / limit);
                if (fits <= 0) {
                    skipped.add(String.format("%s buy: capital cap Rs %.0f reached (in use Rs %.0f)", s, lim.capital(), invested));
                    continue;
                }
                skipped.add(String.format("%s buy: %d shares cut to %d by the capital cap", s, qty, fits));
                qty = fits;
            }
            invested += qty * limit;
            orders.add(new Order(s, "BUY", qty, px, limit, paperQty, reason));
        }

        if (orders.size() > lim.maxOrders()) {
            for (Order o : orders.subList(lim.maxOrders(), orders.size())) {
                skipped.add(o.symbol() + " " + o.side().toLowerCase() + ": above the " + lim.maxOrders()
                        + " orders-per-run cap (next run)");
            }
            orders = new ArrayList<>(orders.subList(0, lim.maxOrders()));
        }
        return new Plan(scale, orders, skipped, seen);
    }

    /** The price on the stock's tick, rounded so the limit is never looser than asked (buys down, sells up). */
    static double align(double price, double tick, boolean buy) {
        BigDecimal t = BigDecimal.valueOf(tick);
        BigDecimal clean = BigDecimal.valueOf(price).setScale(6, RoundingMode.HALF_UP);
        return clean.divide(t, 0, buy ? RoundingMode.FLOOR : RoundingMode.CEILING).multiply(t).doubleValue();
    }
}
