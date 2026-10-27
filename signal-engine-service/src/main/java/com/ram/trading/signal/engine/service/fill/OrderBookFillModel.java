package com.ram.trading.signal.engine.service.fill;

import com.ram.trading.signal.engine.dto.market.Tick;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prices paper fills the way a live MARKET order fills: it takes the opposite
 * side of the order book level by level (a buy lifts the asks, a sell hits the
 * bids) and pays the volume-weighted price of what it consumed. Quantity
 * beyond the visible depth pays the worst visible level plus a penalty.
 *
 * Returns empty when no fresh book is known (indices, a restart, a symbol not
 * ticking) or the book looks wrong (its fill is implausibly far from the
 * reference price); the caller then applies its fixed slippage.
 */
@Component
@Slf4j
public class OrderBookFillModel {

    @Value("${paper.trading.book-fill.enabled:true}")
    private boolean enabled = true;

    /** A book older than this is not used. */
    @Value("${paper.trading.book-fill.max-age-ms:10000}")
    private long maxAgeMillis = 10_000;

    /** Extra cost for quantity beyond the 5 visible levels, % beyond the worst level. */
    @Value("${paper.trading.book-fill.beyond-depth-penalty-pct:0.10}")
    private double beyondDepthPenaltyPct = 0.10;

    /** A book fill further than this from the reference price is treated as bad data. */
    @Value("${paper.trading.book-fill.max-deviation-pct:2.0}")
    private double maxDeviationPct = 2.0;

    private final Map<String, Book> books = new ConcurrentHashMap<>();

    /** Called for every live tick; keeps the latest two-sided book per symbol. */
    public void update(Tick tick) {

        if (!enabled || tick == null || tick.getSymbol() == null
                || !twoSided(tick.getBidPrices(), tick.getBidQuantities(), tick.getAskPrices(), tick.getAskQuantities())) {
            return;
        }

        books.put(tick.getSymbol(), new Book(
                List.copyOf(tick.getBidPrices()),
                List.copyOf(tick.getBidQuantities()),
                List.copyOf(tick.getAskPrices()),
                List.copyOf(tick.getAskQuantities()),
                System.currentTimeMillis()));
    }

    /**
     * @param referencePrice price the order is sent at (LTP or the stop/target trigger)
     * @param buying         true to buy (entry long / cover short), false to sell
     */
    public Optional<Fill> bookFill(String symbol, double referencePrice, boolean buying, int quantity) {

        Book book = symbol == null ? null : books.get(symbol);

        if (!enabled || book == null || quantity <= 0
                || System.currentTimeMillis() - book.receivedAt() > maxAgeMillis) {
            return Optional.empty();
        }

        List<Double> prices = buying ? book.askPrices() : book.bidPrices();
        List<Long> sizes = buying ? book.askQuantities() : book.bidQuantities();

        double cost = 0;
        long remaining = quantity;
        int levelsUsed = 0;

        for (int i = 0; i < prices.size() && remaining > 0; i++) {
            long take = Math.min(remaining, sizes.get(i));
            cost += take * prices.get(i);
            remaining -= take;
            levelsUsed++;
        }

        if (remaining > 0) {
            double worst = prices.get(prices.size() - 1);
            double beyond = buying
                    ? worst * (1 + beyondDepthPenaltyPct / 100.0)
                    : worst * (1 - beyondDepthPenaltyPct / 100.0);
            cost += remaining * beyond;
        }

        double price = cost / quantity;

        if (referencePrice > 0 && Math.abs(price - referencePrice) / referencePrice * 100.0 > maxDeviationPct) {
            log.warn("PAPER FILL | Book fill rejected as implausible | Symbol={} | Side={} | Qty={} | BookFill={} | Reference={}",
                    symbol, buying ? "BUY" : "SELL", quantity, round2(price), referencePrice);
            return Optional.empty();
        }

        double bestBid = book.bidPrices().get(0);
        double bestAsk = book.askPrices().get(0);
        double mid = (bestBid + bestAsk) / 2.0;

        Fill fill = new Fill(
                price,
                levelsUsed,
                remaining > 0,
                mid > 0 ? round2((bestAsk - bestBid) / mid * 100.0) : null);

        log.info("PAPER FILL | Symbol={} | Side={} | Qty={} | Source=ORDER_BOOK | Fill={} | Reference={} | Levels={} | BeyondDepth={} | SpreadPct={}",
                symbol, buying ? "BUY" : "SELL", quantity, round2(price), referencePrice,
                levelsUsed, fill.beyondDepth(), fill.spreadPct());

        return Optional.of(fill);
    }

    private static boolean twoSided(List<Double> bidPrices, List<Long> bidQuantities,
                                    List<Double> askPrices, List<Long> askQuantities) {
        return bidPrices != null && askPrices != null && bidQuantities != null && askQuantities != null
                && !bidPrices.isEmpty() && !askPrices.isEmpty()
                && bidPrices.size() == bidQuantities.size()
                && askPrices.size() == askQuantities.size()
                && askPrices.get(0) >= bidPrices.get(0);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** price is unrounded; callers round to paise. */
    public record Fill(double price, int levelsUsed, boolean beyondDepth, Double spreadPct) {
    }

    private record Book(
            List<Double> bidPrices,
            List<Long> bidQuantities,
            List<Double> askPrices,
            List<Long> askQuantities,
            long receivedAt) {
    }
}
