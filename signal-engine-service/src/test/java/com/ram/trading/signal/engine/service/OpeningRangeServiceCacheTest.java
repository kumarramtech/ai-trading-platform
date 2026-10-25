package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.premarket.OpeningRange;
import com.ram.trading.signal.engine.repo.MinuteCandleRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpeningRangeServiceCacheTest {

    @Test
    @SuppressWarnings("unchecked")
    void doesNotReusePreviousTradingDaysOpeningRange() throws Exception {
        MinuteCandleRepository repository = mock(MinuteCandleRepository.class);
        when(repository.findBySymbolAndMinute(anyString(), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        OpeningRangeService service = new OpeningRangeService(new MinuteCandleStore(repository));

        Field field = OpeningRangeService.class.getDeclaredField("openingRanges");
        field.setAccessible(true);
        Map<String, OpeningRange> cache = (Map<String, OpeningRange>) field.get(service);

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        OpeningRange stale = OpeningRange.builder()
                .symbol("TEST")
                .tradingDate(today.minusDays(1))
                .complete(true)
                .build();
        cache.put("TEST", stale);

        assertNull(service.get("TEST"));
        assertNull(cache.get("TEST"));
    }
}
