package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.repo.MinuteCandleRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MinuteCandleAggregatorTest {

    private final MinuteCandleStore store = createStore();
    private final MinuteCandleAggregator aggregator = new MinuteCandleAggregator(store);

    private static MinuteCandleStore createStore() {
        MinuteCandleRepository repository = mock(MinuteCandleRepository.class);
        when(repository.findBySymbolAndMinute(anyString(), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        return new MinuteCandleStore(repository);
    }

    @Test
    void emitsLatestRealCandleAcrossMinuteGapWithoutCreatingSyntheticCandle() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 23, 9, 30);

        assertNull(aggregator.process(tick("TEST", base, 100.0)));
        MinuteCandle completed = aggregator.process(tick("TEST", base.plusMinutes(2), 102.0));

        assertNotNull(completed);
        assertEquals(base, completed.getMinute());
        assertNull(store.get("TEST", base.plusMinutes(1)), "A missing minute must not be fabricated");
        assertNotNull(store.get("TEST", base.plusMinutes(2)));
    }

    @Test
    void ignoresLateTickThatWouldRewriteAnOlderCandle() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 23, 9, 30);
        aggregator.process(tick("TEST", base, 100.0));
        aggregator.process(tick("TEST", base.plusMinutes(1), 101.0));

        aggregator.process(tick("TEST", base, 99.0));

        assertEquals(100.0, store.get("TEST", base).getClose());
    }

    private Tick tick(String symbol, LocalDateTime time, double price) {
        long timestamp = time.atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli();
        return Tick.builder()
                .symbol(symbol)
                .lastTradedPrice(price)
                .timestamp(timestamp)
                .volume(10L)
                .build();
    }
}
