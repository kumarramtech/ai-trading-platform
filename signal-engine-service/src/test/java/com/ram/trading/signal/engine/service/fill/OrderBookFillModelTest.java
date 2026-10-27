package com.ram.trading.signal.engine.service.fill;

import com.ram.trading.signal.engine.dto.market.Tick;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookFillModelTest {

    private final OrderBookFillModel model = new OrderBookFillModel();

    private Tick book(String symbol) {
        // Bids 99.95 x 100, 99.90 x 200; asks 100.05 x 100, 100.10 x 200, 100.20 x 300.
        return Tick.builder()
                .symbol(symbol)
                .bidPrices(List.of(99.95, 99.90))
                .bidQuantities(List.of(100L, 200L))
                .askPrices(List.of(100.05, 100.10, 100.20))
                .askQuantities(List.of(100L, 200L, 300L))
                .build();
    }

    @Test
    void smallBuyFillsAtTheBestAsk() {
        model.update(book("ABC"));

        OrderBookFillModel.Fill fill = model.bookFill("ABC", 100.0, true, 50).orElseThrow();

        assertEquals(100.05, fill.price(), 1e-9);
        assertEquals(1, fill.levelsUsed());
        assertEquals(0.1, fill.spreadPct(), 1e-9);
    }

    @Test
    void largerBuyWalksTheBookAtTheVolumeWeightedPrice() {
        model.update(book("ABC"));

        // 100 @ 100.05 + 200 @ 100.10 + 100 @ 100.20 = 40,045 / 400 = 100.1125
        OrderBookFillModel.Fill fill = model.bookFill("ABC", 100.0, true, 400).orElseThrow();

        assertEquals(100.1125, fill.price(), 1e-9);
        assertEquals(3, fill.levelsUsed());
        assertFalse(fill.beyondDepth());
    }

    @Test
    void sellHitsTheBidsAndQuantityBeyondTheBookPaysAPenalty() {
        model.update(book("ABC"));

        // 100 @ 99.95 + 200 @ 99.90 + 100 beyond depth @ 99.90 x (1 - 0.10%) = 99.8001
        OrderBookFillModel.Fill fill = model.bookFill("ABC", 100.0, false, 400).orElseThrow();

        assertEquals((100 * 99.95 + 200 * 99.90 + 100 * 99.8001) / 400, fill.price(), 1e-9);
        assertTrue(fill.beyondDepth());
    }

    @Test
    void noBookStaleBookOrImplausibleBookLeavesTheFillToFixedSlippage() {
        assertTrue(model.bookFill("UNKNOWN", 100.0, true, 10).isEmpty());

        model.update(book("STALE"));
        ReflectionTestUtils.setField(model, "maxAgeMillis", -1L);
        assertTrue(model.bookFill("STALE", 100.0, true, 10).isEmpty());
        ReflectionTestUtils.setField(model, "maxAgeMillis", 10_000L);

        // Reference 90 vs book ~100: more than 2% apart, so the book is not trusted.
        model.update(book("ODD"));
        assertTrue(model.bookFill("ODD", 90.0, true, 10).isEmpty());
    }

    @Test
    void oneSidedOrCrossedTicksDoNotReplaceTheBook() {
        model.update(book("ABC"));
        model.update(Tick.builder().symbol("ABC")
                .bidPrices(List.of(101.0)).bidQuantities(List.of(10L))
                .askPrices(List.of(100.0)).askQuantities(List.of(10L))   // crossed
                .build());

        assertEquals(100.05, model.bookFill("ABC", 100.0, true, 10).orElseThrow().price(), 1e-9);
    }
}
