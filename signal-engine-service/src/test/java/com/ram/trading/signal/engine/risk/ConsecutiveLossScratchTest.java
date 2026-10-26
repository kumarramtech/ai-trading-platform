package com.ram.trading.signal.engine.risk;

import com.ram.trading.signal.engine.client.TradeExecutionClient.TradeExecutionPosition;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import com.ram.trading.signal.engine.service.RiskManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Break-even scratches must not count toward the daily losing-streak breaker. */
class ConsecutiveLossScratchTest {

    private static final String STREAK = "Maximum consecutive losses reached.";

    private PaperTradeRepository repository;
    private LiveTradeSnapshotService snapshotService;
    private RiskManagementService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaperTradeRepository.class);
        snapshotService = mock(LiveTradeSnapshotService.class);
        service = new RiskManagementService(repository, snapshotService);
        ReflectionTestUtils.setField(service, "maxDailyLoss", 2000d);
        ReflectionTestUtils.setField(service, "maxOpenTrades", 2);
        ReflectionTestUtils.setField(service, "maxTradesPerSymbolPerDay", 3);
        ReflectionTestUtils.setField(service, "maxConsecutiveLosses", 3);
    }

    private static PaperTrade paper(int minute, double entry, int qty, double pnl) {
        return PaperTrade.builder()
                .symbol("S" + minute)
                .entryPrice(entry)
                .quantity(qty)
                .profitLoss(pnl)
                .entryTime(LocalDateTime.now().withHour(9).withMinute(minute))
                .exitTime(LocalDateTime.now())
                .build();
    }

    @Test
    void paperScratchesDoNotExtendTheStreak() {
        // 2026-10-26 sequence, newest first: COCHINSHIP, ASHOKA, MARICO, ADFFOODS, NBCC.
        when(snapshotService.isLiveMode()).thenReturn(false);
        when(repository.findAllByOrderByEntryTimeDesc()).thenReturn(List.of(
                paper(48, 1135.6, 44, -198.0),
                paper(44, 97.88, 510, -30.6),
                paper(37, 762.8, 65, -3.25),
                paper(32, 233.9, 213, -21.3),
                paper(31, 67.79, 737, 22.11)));

        assertFalse(service.validateTrade("TCS").getViolations().contains(STREAK));
    }

    @Test
    void paperRealLossesStillTripTheBreaker() {
        when(snapshotService.isLiveMode()).thenReturn(false);
        when(repository.findAllByOrderByEntryTimeDesc()).thenReturn(List.of(
                paper(50, 1000.0, 50, -200.0),
                paper(45, 100.0, 500, -10.0),   // scratch, skipped
                paper(40, 500.0, 100, -180.0),
                paper(35, 200.0, 250, -190.0)));

        assertTrue(service.validateTrade("TCS").getViolations().contains(STREAK));
    }

    private static TradeExecutionPosition live(String symbol, String entry, int qty, String pnl, int minutesAgo) {
        return new TradeExecutionPosition(
                1L, 1L, symbol, "NSE_EQ|TEST", "SELL", qty, qty, new BigDecimal(entry),
                null, null, null, "CLOSED", null, null, null, null, null, null, null,
                new BigDecimal(pnl), null,
                null, LocalDateTime.now().minusMinutes(minutesAgo), null, null, false);
    }

    @Test
    void liveScratchesDoNotExtendTheStreak() {
        when(snapshotService.isLiveMode()).thenReturn(true);
        when(snapshotService.freshTrades()).thenReturn(Optional.of(List.of(
                live("A", "1000", 50, "-200", 20),
                live("B", "100", 500, "-20", 30),
                live("C", "500", 100, "-30", 40))));

        assertFalse(service.validateTrade("TCS").getViolations().contains(STREAK));
    }
}
