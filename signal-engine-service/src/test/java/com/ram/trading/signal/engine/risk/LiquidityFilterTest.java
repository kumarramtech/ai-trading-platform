package com.ram.trading.signal.engine.risk;

import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.indicator.Candle;
import com.ram.trading.signal.engine.indicator.service.TechnicalIndicatorTurnoverAccess;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LiquidityFilterTest {

    private final LiquidityFilter filter = new LiquidityFilter();

    private static TechnicalIndicatorResponse withTurnoverCrore(Double crore) {
        TechnicalIndicatorResponse indicator = new TechnicalIndicatorResponse();
        indicator.setAverageDailyTurnover(crore == null ? null : crore * 1e7);
        return indicator;
    }

    @Test
    void lowPricedStockIsSkipped() {
        // RENUKA 2026-10-27 at Rs 18.39.
        assertTrue(filter.rejection("RENUKA", 18.39, withTurnoverCrore(50.0)).isPresent());
    }

    @Test
    void thinStockIsSkipped() {
        String reason = filter.rejection("SMESTOCK", 120.0, withTurnoverCrore(0.8)).orElseThrow();
        assertTrue(reason.contains("turnover"), reason);
    }

    @Test
    void liquidStockPasses() {
        assertTrue(filter.rejection("IFCI", 60.62, withTurnoverCrore(150.0)).isEmpty());
    }

    @Test
    void unknownTurnoverIsNotBlockingAndMiddayChecksPriceOnly() {
        assertTrue(filter.rejection("X", 500.0, withTurnoverCrore(null)).isEmpty());
        assertTrue(filter.rejection("KIRLPNU", 529.5, null).isEmpty());
        assertTrue(filter.rejection("CHEAP", 20.0, null).isPresent());
    }

    @Test
    void turnoverIsTheMeanOfTheLastTwentySessions() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            // First 10 sessions huge, last 20 at Rs 100 x 100,000 = Rs 1 crore each.
            candles.add(Candle.builder()
                    .close(BigDecimal.valueOf(100))
                    .volume(i < 10 ? 10_000_000L : 100_000L)
                    .build());
        }

        assertEquals(1e7, TechnicalIndicatorTurnoverAccess.averageDailyTurnover(candles, 20), 0.01);
    }
}
