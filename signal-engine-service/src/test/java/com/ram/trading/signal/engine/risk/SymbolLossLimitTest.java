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

/**
 * Day rules from 2026-10-26: only max-daily-loss halts the whole day; a symbol
 * with 3 real losses today is blocked while other symbols keep trading.
 */
class SymbolLossLimitTest {

    private static final String SYMBOL_LOSSES = "Maximum daily losses reached for symbol: ";
    private static final String STREAK = "Maximum consecutive losses reached.";
    private static final String DAILY_LOSS = "Daily loss limit exceeded. Trading halted.";

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
        ReflectionTestUtils.setField(service, "maxTradesPerSymbolPerDay", 6);
    }

    private static PaperTrade paper(String symbol, int minute, double pnl) {
        return PaperTrade.builder()
                .symbol(symbol)
                .entryPrice(1000.0)
                .quantity(50)
                .profitLoss(pnl)
                .entryTime(LocalDateTime.now().withHour(10).withMinute(minute))
                .exitTime(LocalDateTime.now().withHour(10).withMinute(minute + 5))
                .build();
    }

    @Test
    void paperSymbolWithThreeRealLossesIsBlockedButOthersTrade() {
        List<PaperTrade> infy = List.of(
                paper("INFY", 10, -200.0),
                paper("INFY", 20, -150.0),
                paper("INFY", 30, -180.0));
        when(snapshotService.isLiveMode()).thenReturn(false);
        when(repository.findBySymbol("INFY")).thenReturn(infy);
        when(repository.findBySymbol("TCS")).thenReturn(List.of());
        when(repository.findAll()).thenReturn(infy);
        when(repository.findAllByOrderByEntryTimeDesc()).thenReturn(infy);

        assertTrue(service.validateTrade("INFY").getViolations().contains(SYMBOL_LOSSES + "INFY"));
        // -530 for the day and three losses in a row: the streak is off by default.
        assertTrue(service.validateTrade("TCS").isAllowed());
    }

    @Test
    void paperScratchesAndWinsDoNotCountAsSymbolLosses() {
        when(snapshotService.isLiveMode()).thenReturn(false);
        when(repository.findBySymbol("INFY")).thenReturn(List.of(
                paper("INFY", 10, -200.0),
                paper("INFY", 20, -20.0),   // scratch: < 0.10% of Rs 50,000
                paper("INFY", 30, 300.0),
                paper("INFY", 40, -180.0)));

        assertFalse(service.validateTrade("INFY").getViolations().contains(SYMBOL_LOSSES + "INFY"));
    }

    @Test
    void paperDailyLossStillHaltsEverySymbol() {
        when(snapshotService.isLiveMode()).thenReturn(false);
        when(repository.findBySymbol("TCS")).thenReturn(List.of());
        when(repository.findAll()).thenReturn(List.of(
                paper("INFY", 10, -1200.0),
                paper("SBIN", 20, -800.0)));

        assertTrue(service.validateTrade("TCS").getViolations().contains(DAILY_LOSS));
    }

    @Test
    void paperStreakCanStillBeEnabled() {
        ReflectionTestUtils.setField(service, "maxConsecutiveLosses", 3);
        List<PaperTrade> losses = List.of(
                paper("A", 30, -200.0),
                paper("B", 20, -200.0),
                paper("C", 10, -200.0));
        when(snapshotService.isLiveMode()).thenReturn(false);
        when(repository.findAllByOrderByEntryTimeDesc()).thenReturn(losses);

        assertTrue(service.validateTrade("TCS").getViolations().contains(STREAK));
    }

    private static TradeExecutionPosition live(String symbol, String pnl, int minutesAgo) {
        return new TradeExecutionPosition(
                1L, 1L, symbol, "NSE_EQ|TEST", "SELL", 50, 50, new BigDecimal("1000"),
                null, null, null, "CLOSED", null, null, null, null, null, null, null,
                new BigDecimal(pnl), null,
                null, LocalDateTime.now().minusMinutes(minutesAgo), null, null, false);
    }

    @Test
    void liveDailyLossCountsCharges() {
        // Five shorts of 50 @ 1000 covered @ 1007.80: Rs 1,950 gross loss, ~Rs 2,275 after charges.
        List<TradeExecutionPosition> trades = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            trades.add(new TradeExecutionPosition(
                    (long) i, (long) i, "S" + i, "NSE_EQ|TEST", "SELL", 50, 50, new BigDecimal("1000"),
                    null, null, null, "CLOSED", null, null, 50, 50, 0, new BigDecimal("1007.80"), null,
                    new BigDecimal("-390"), null,
                    null, LocalDateTime.now().minusMinutes(60 - i), null, null, false));
        }
        when(snapshotService.isLiveMode()).thenReturn(true);
        when(snapshotService.freshTrades()).thenReturn(Optional.of(trades));

        assertTrue(service.validateTrade("TCS").getViolations().contains(DAILY_LOSS));
    }

    @Test
    void liveSymbolWithThreeRealLossesIsBlockedButOthersTrade() {
        when(snapshotService.isLiveMode()).thenReturn(true);
        when(snapshotService.freshTrades()).thenReturn(Optional.of(List.of(
                live("INFY", "-200", 90),
                live("INFY", "-150", 60),
                live("INFY", "-20", 45),    // scratch
                live("INFY", "-180", 30))));

        assertTrue(service.validateTrade("INFY").getViolations().contains(SYMBOL_LOSSES + "INFY"));
        assertTrue(service.validateTrade("TCS").isAllowed());
    }
}
