package com.ram.trading.signal.engine.risk;

import com.ram.trading.signal.engine.client.TradeExecutionClient;
import com.ram.trading.signal.engine.client.TradeExecutionClient.TradeExecutionPosition;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import com.ram.trading.signal.engine.service.RiskManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Live mode must enforce Signal Engine's trading-risk limits against Trade Execution positions. */
class LiveRiskManagementTest {

    private PaperTradeRepository paperTradeRepository;
    private LiveTradeSnapshotService snapshotService;
    private RiskManagementService riskManagementService;

    @BeforeEach
    void setUp() {
        paperTradeRepository = mock(PaperTradeRepository.class);
        snapshotService = mock(LiveTradeSnapshotService.class);
        when(snapshotService.isLiveMode()).thenReturn(true);
        riskManagementService = new RiskManagementService(paperTradeRepository, snapshotService);
        ReflectionTestUtils.setField(riskManagementService, "maxDailyLoss", 2000d);
        ReflectionTestUtils.setField(riskManagementService, "maxOpenTrades", 2);
        ReflectionTestUtils.setField(riskManagementService, "maxTradesPerSymbolPerDay", 3);
    }

    private static TradeExecutionPosition position(String symbol, String status, String realizedPnl) {
        return new TradeExecutionPosition(
                1L, 1L, symbol, "NSE_EQ|TEST", "BUY", 2, 2, new BigDecimal("100"),
                null, null, null, status, null, null, null, null, null, null, null,
                realizedPnl == null ? null : new BigDecimal(realizedPnl), null,
                null, "CLOSED".equals(status) ? LocalDateTime.now() : null, null, null, false);
    }

    @Test
    void liveDailyLossLimitBlocksNewEntriesAndIgnoresPaperTrades() {
        when(snapshotService.freshTrades()).thenReturn(Optional.of(List.of(
                position("TCS", "CLOSED", "-1500"),
                position("INFY", "CLOSED", "-600"))));

        RiskCheckResponse response = riskManagementService.validateTrade("HDFCBANK");

        assertFalse(response.isAllowed());
        assertTrue(response.getViolations().contains("Daily loss limit exceeded. Trading halted."));
        verifyNoInteractions(paperTradeRepository);
    }

    @Test
    void liveEntryWithinLimitsIsAllowed() {
        when(snapshotService.freshTrades()).thenReturn(Optional.of(List.of(
                position("TCS", "CLOSED", "-1500"),
                position("INFY", "CLOSED", "300"))));

        RiskCheckResponse response = riskManagementService.validateTrade("HDFCBANK");

        assertTrue(response.isAllowed(), () -> "Unexpected violations: " + response.getViolations());
    }

    @Test
    void liveOpenPositionLimitAndDuplicateSymbolAreEnforced() {
        when(snapshotService.freshTrades()).thenReturn(Optional.of(List.of(
                position("TCS", "OPEN", null),
                position("INFY", "EXIT_PENDING", null))));

        RiskCheckResponse response = riskManagementService.validateTrade("TCS");

        assertFalse(response.isAllowed());
        assertTrue(response.getViolations().contains("Maximum open trades reached."));
        assertTrue(response.getViolations().contains("Open live position already exists for symbol: TCS"));
    }

    @Test
    void liveReentryCooldownBlocksTheSameSymbolRightAfterAnExit() {
        when(snapshotService.freshTrades()).thenReturn(Optional.of(List.of(
                position("TCS", "CLOSED", "-250"))));

        RiskCheckResponse sameSymbol = riskManagementService.validateTrade("TCS");
        RiskCheckResponse otherSymbol = riskManagementService.validateTrade("INFY");

        assertFalse(sameSymbol.isAllowed());
        assertTrue(sameSymbol.getViolations().stream()
                .anyMatch(v -> v.startsWith("Re-entry cooldown active for symbol: TCS")));
        assertTrue(otherSymbol.isAllowed(), () -> "Unexpected violations: " + otherSymbol.getViolations());
    }

    @Test
    void staleOrMissingLiveDataFailsClosed() {
        when(snapshotService.freshTrades()).thenReturn(Optional.empty());

        RiskCheckResponse response = riskManagementService.validateTrade("TCS");

        assertFalse(response.isAllowed());
        assertEquals(1, response.getViolations().size());
    }

    @Test
    void snapshotOlderThanMaxAgeIsNotTrusted() {
        TradeExecutionClient client = mock(TradeExecutionClient.class);
        when(client.getSessionTrades()).thenReturn(Mono.just(List.of(position("TCS", "OPEN", null))));
        MutableClock clock = new MutableClock(Instant.parse("2026-10-28T05:00:00Z"));
        LiveTradeSnapshotService service = new LiveTradeSnapshotService(
                client, "live", Duration.ofSeconds(30), Duration.ofSeconds(5), clock);

        assertTrue(service.freshTrades().isEmpty(), "No snapshot fetched yet");
        service.refresh();
        assertEquals(1, service.freshTrades().orElseThrow().size());

        clock.advance(Duration.ofSeconds(31));
        assertTrue(service.freshTrades().isEmpty(), "A snapshot older than max age must fail closed");
    }

    @Test
    void paperModeNeverCallsTradeExecution() {
        TradeExecutionClient client = mock(TradeExecutionClient.class);
        LiveTradeSnapshotService service = new LiveTradeSnapshotService(
                client, "paper", Duration.ofSeconds(30), Duration.ofSeconds(5), Clock.systemUTC());

        service.refresh();

        verifyNoInteractions(client);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
