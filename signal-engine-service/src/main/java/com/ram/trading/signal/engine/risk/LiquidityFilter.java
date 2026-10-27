package com.ram.trading.signal.engine.risk;

import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Keeps entries to stocks that can be exited near the planned price.
 *
 * Paper fills assume a 0.03% slippage that a thin or very low-priced stock
 * will not give live: a Rs 18 stock moves in 0.3% ticks (RENUKA 2026-10-27,
 * 2,272 shares), and an SME counter with a few lakh of daily value cannot
 * absorb a Rs 40,000 position without moving. Paper results on such stocks
 * would not survive going live, so they are not traded in paper either.
 */
@Component
@Slf4j
public class LiquidityFilter {

    /** Minimum share price, rupees (0 = off). */
    @Value("${trading.liquidity.min-price:50}")
    private double minPrice = 50;

    /** Minimum average daily traded value over 20 sessions, rupees crore (0 = off). */
    @Value("${trading.liquidity.min-average-turnover-crore:5}")
    private double minAverageTurnoverCrore = 5;

    /**
     * @return the reason to skip, or empty when the stock is liquid enough.
     *         Unknown turnover (no volume history) is allowed with a warning,
     *         so a data gap cannot silently block every entry.
     */
    public Optional<String> rejection(String symbol, Double price, TechnicalIndicatorResponse indicator) {

        if (minPrice > 0 && price != null && price > 0 && price < minPrice) {
            return Optional.of(String.format("price %.2f below minimum %.0f", price, minPrice));
        }

        if (minAverageTurnoverCrore <= 0) {
            return Optional.empty();
        }

        if (indicator == null) {
            // Price-only check (MIDDAY candidates carry no daily history here).
            return Optional.empty();
        }

        Double turnover = indicator.getAverageDailyTurnover();

        if (turnover == null) {
            log.warn("LIQUIDITY | Average turnover unknown, not filtered | Symbol={}", symbol);
            return Optional.empty();
        }

        double turnoverCrore = turnover / 1e7;

        if (turnoverCrore < minAverageTurnoverCrore) {
            return Optional.of(String.format(
                    "average daily turnover Rs %.2f cr below minimum Rs %.0f cr",
                    turnoverCrore, minAverageTurnoverCrore));
        }

        return Optional.empty();
    }
}
