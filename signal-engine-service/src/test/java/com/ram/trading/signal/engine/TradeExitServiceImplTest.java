package com.ram.trading.signal.engine;

import com.ram.trading.signal.engine.client.TradeExecutionClient;
import com.ram.trading.signal.engine.client.NotificationClient;
import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.exit.ExitOrchestrator;
import com.ram.trading.signal.engine.exit.impl.TradeExitServiceImpl;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import com.ram.trading.signal.engine.service.PaperTradingService;
import com.ram.trading.signal.engine.service.TrailingStopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TradeExitServiceImplTest {

    private PaperTradeRepository paperTradeRepository;
    private TradeExecutionClient tradeExecutionClient;
    private ExitOrchestrator exitOrchestrator;
    private PaperTradingService paperTradingService;
    private TrailingStopService trailingStopService;
    private TradeExitServiceImpl service;

    @BeforeEach
    void setUp() {
        paperTradeRepository = mock(PaperTradeRepository.class);
        tradeExecutionClient = mock(TradeExecutionClient.class);
        exitOrchestrator = mock(ExitOrchestrator.class);
        paperTradingService = mock(PaperTradingService.class);
        trailingStopService = mock(TrailingStopService.class);

        service = new TradeExitServiceImpl(
                paperTradeRepository,
                tradeExecutionClient,
                exitOrchestrator,
                paperTradingService,
                trailingStopService);

        ReflectionTestUtils.setField(service, "executionTradeMode", "live");
        ReflectionTestUtils.setField(service, "liveExecutionEnabled", true);
        ReflectionTestUtils.setField(service, "maxLossPerTrade", 250.0d);
    }

    @Test
    void hardLossIncludesPreviouslyRealizedPnlAfterPartialExit() {
        long signalId = 42L;

        TradeExecutionClient.TradeExecutionPosition position =
                new TradeExecutionClient.TradeExecutionPosition(
                        1L,
                        signalId,
                        "TEST",
                        "NSE_EQ|TEST",
                        "BUY",
                        10,
                        10,
                        new BigDecimal("100.00"),
                        new BigDecimal("50.00"),
                        new BigDecimal("50.00"),
                        new BigDecimal("150.00"),
                        "OPEN",
                        "entry-order",
                        null,
                        null,
                        8,
                        2,
                        new BigDecimal("75.00"),
                        new BigDecimal("75.00"),
                        new BigDecimal("-200.00"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        false);

        // Remaining position unrealized P&L = (70 - 100) * 2 = -60.
        // Cumulative realized P&L = -200, so whole-trade P&L = -260.
        // The hard-loss threshold is 250; checking only unrealized P&L would miss it.
        when(tradeExecutionClient.getOpenTrades()).thenReturn(Mono.just(List.of(position)));
        when(tradeExecutionClient.exitTrade(any()))
                .thenReturn(Mono.just(new TradeExecutionClient.TradeExecutionResponse(
                        false, signalId, "TEST", "BUY", 2, new BigDecimal("70.00"),
                        null, "exit-order", "EXIT_PENDING",
                        "Exit submitted; reconciliation pending", 0, null)));

        Tick tick = Tick.builder()
                .symbol("TEST")
                .lastTradedPrice(70.0d)
                .build();

        StepVerifier.create(service.evaluateExit(tick)).verifyComplete();

        verify(tradeExecutionClient).exitTrade(argThat(request ->
                request.signalId().equals(signalId)
                        && request.quantity() == 2
                        && "MARKET".equals(request.orderType())));
    }

    @Test
    void liveStopLossTriggerRequestsExitThroughTradeExecution() {
        TradeExecutionClient.TradeExecutionPosition position = new TradeExecutionClient.TradeExecutionPosition(
                1L, 45L, "TEST", "NSE_EQ|TEST", "BUY", 10, 10,
                new BigDecimal("100.00"), new BigDecimal("90.00"), new BigDecimal("90.00"),
                new BigDecimal("120.00"), "OPEN", "entry-order", null, null,
                0, 10, null, null, BigDecimal.ZERO, null, null, null, null, null, false);
        when(tradeExecutionClient.getOpenTrades()).thenReturn(Mono.just(List.of(position)));
        when(tradeExecutionClient.exitTrade(any())).thenReturn(Mono.just(
                new TradeExecutionClient.TradeExecutionResponse(false, 45L, "TEST", "BUY", 10,
                        new BigDecimal("89.00"), null, "exit-order", "EXIT_PENDING",
                        "reconciliation pending", 0, null)));

        Tick tick = Tick.builder().symbol("TEST").lastTradedPrice(89.00d).build();
        StepVerifier.create(service.evaluateExit(tick)).verifyComplete();

        verify(tradeExecutionClient).exitTrade(argThat(request ->
                request.signalId().equals(45L)
                        && request.quantity() == 10
                        && "MARKET".equals(request.orderType())));
        verify(tradeExecutionClient, never()).updateProtection(any());
    }

    @Test
    void liveTargetExtendsOnceWhenTargetIsReachedAndEnoughTimeIsConfigured() {
        ReflectionTestUtils.setField(service, "dynamicTargetEnabled", true);
        ReflectionTestUtils.setField(service, "minMinutesRemainingForTargetExtension", -10000L);
        ReflectionTestUtils.setField(service, "targetExtensionPercent", 25.0d);

        TradeExecutionClient.TradeExecutionPosition position = new TradeExecutionClient.TradeExecutionPosition(
                1L, 43L, "TEST", "NSE_EQ|TEST", "BUY", 10, 10,
                new BigDecimal("100.00"), new BigDecimal("90.00"), new BigDecimal("90.00"),
                new BigDecimal("110.00"), "OPEN", "entry-order", null, null,
                0, 10, null, null, BigDecimal.ZERO, null, null, null, null, null, false);
        TradeExecutionClient.TradeExecutionPosition updated = new TradeExecutionClient.TradeExecutionPosition(
                1L, 43L, "TEST", "NSE_EQ|TEST", "BUY", 10, 10,
                new BigDecimal("100.00"), new BigDecimal("90.00"), new BigDecimal("90.00"),
                new BigDecimal("112.50"), "OPEN", "entry-order", null, null,
                0, 10, null, null, BigDecimal.ZERO, null, null, null, null, null, true);

        when(tradeExecutionClient.getOpenTrades()).thenReturn(Mono.just(List.of(position)));
        when(tradeExecutionClient.updateProtection(any())).thenReturn(Mono.just(updated));

        Tick tick = Tick.builder().symbol("TEST").lastTradedPrice(110.00d).build();
        StepVerifier.create(service.evaluateExit(tick)).verifyComplete();

        verify(tradeExecutionClient).updateProtection(argThat(request ->
                request.signalId().equals(43L)
                        && request.stopLoss() == null
                        && request.targetPrice().compareTo(new BigDecimal("112.50")) == 0
                        && Boolean.TRUE.equals(request.targetExtended())));
        verify(tradeExecutionClient, never()).exitTrade(any());
    }

    @Test
    void liveTargetExtensionFailureFallsBackToExitWithoutRetryingExtension() {
        ReflectionTestUtils.setField(service, "dynamicTargetEnabled", true);
        ReflectionTestUtils.setField(service, "minMinutesRemainingForTargetExtension", -10000L);
        ReflectionTestUtils.setField(service, "targetExtensionPercent", 25.0d);

        TradeExecutionClient.TradeExecutionPosition position = new TradeExecutionClient.TradeExecutionPosition(
                1L, 44L, "TEST", "NSE_EQ|TEST", "BUY", 10, 10,
                new BigDecimal("100.00"), new BigDecimal("90.00"), new BigDecimal("90.00"),
                new BigDecimal("110.00"), "OPEN", "entry-order", null, null,
                0, 10, null, null, BigDecimal.ZERO, null, null, null, null, null, false);
        when(tradeExecutionClient.getOpenTrades()).thenReturn(Mono.just(List.of(position)));
        when(tradeExecutionClient.updateProtection(any()))
                .thenReturn(Mono.error(new RuntimeException("simulated protection update timeout")));
        when(tradeExecutionClient.exitTrade(any())).thenReturn(Mono.just(
                new TradeExecutionClient.TradeExecutionResponse(false, 44L, "TEST", "BUY", 10,
                        new BigDecimal("110.00"), null, "exit-order", "EXIT_PENDING",
                        "reconciliation pending", 0, null)));

        Tick tick = Tick.builder().symbol("TEST").lastTradedPrice(110.00d).build();
        StepVerifier.create(service.evaluateExit(tick)).verifyComplete();

        verify(tradeExecutionClient, times(1)).updateProtection(any());
        verify(tradeExecutionClient, times(1)).exitTrade(argThat(request ->
                request.signalId().equals(44L) && request.quantity() == 10));
    }
    @Test
    void liveBuyPositionPersistsAndConfirmsImprovedTrailingStop() {
        TradeExecutionClient.TradeExecutionPosition position = livePosition(
                51L, "BUY", "100.00", "90.00", "90.00", "130.00");
        TradeExecutionClient.TradeExecutionPosition updated = livePosition(
                51L, "BUY", "100.00", "90.00", "100.00", "130.00");
        when(tradeExecutionClient.getOpenTrades()).thenReturn(Mono.just(List.of(position)));
        when(trailingStopService.calculateLiveTrailingStop(
                eq("BUY"), eq(new BigDecimal("100.00")), eq(new BigDecimal("90.00")),
                eq(new BigDecimal("90.0")), eq(105.0d), eq(10)))
                .thenReturn(Optional.of(new BigDecimal("100.00")));
        when(tradeExecutionClient.updateProtection(any())).thenReturn(Mono.just(updated));

        StepVerifier.create(service.evaluateExit(
                Tick.builder().symbol("TEST").lastTradedPrice(105.0d).build()))
                .verifyComplete();

        verify(tradeExecutionClient).updateProtection(argThat(request ->
                request.signalId().equals(51L)
                        && new BigDecimal("100.00").compareTo(request.stopLoss()) == 0
                        && request.targetPrice() == null
                        && request.targetExtended() == null));
        verify(tradeExecutionClient, never()).exitTrade(any());
    }

    @Test
    void liveSellPositionPersistsAndConfirmsImprovedTrailingStop() {
        TradeExecutionClient.TradeExecutionPosition position = livePosition(
                52L, "SELL", "100.00", "110.00", "110.00", "70.00");
        TradeExecutionClient.TradeExecutionPosition updated = livePosition(
                52L, "SELL", "100.00", "110.00", "100.00", "70.00");
        when(tradeExecutionClient.getOpenTrades()).thenReturn(Mono.just(List.of(position)));
        when(trailingStopService.calculateLiveTrailingStop(
                eq("SELL"), eq(new BigDecimal("100.00")), eq(new BigDecimal("110.00")),
                eq(new BigDecimal("110.0")), eq(95.0d), eq(10)))
                .thenReturn(Optional.of(new BigDecimal("100.00")));
        when(tradeExecutionClient.updateProtection(any())).thenReturn(Mono.just(updated));

        StepVerifier.create(service.evaluateExit(
                Tick.builder().symbol("TEST").lastTradedPrice(95.0d).build()))
                .verifyComplete();

        verify(tradeExecutionClient).updateProtection(argThat(request ->
                request.signalId().equals(52L)
                        && new BigDecimal("100.00").compareTo(request.stopLoss()) == 0));
        verify(tradeExecutionClient, never()).exitTrade(any());
    }

    @Test
    void liveTrailingStopIsNotUpdatedWhenCalculatorHasNoImprovement() {
        TradeExecutionClient.TradeExecutionPosition position = livePosition(
                53L, "BUY", "100.00", "90.00", "100.00", "130.00");
        when(tradeExecutionClient.getOpenTrades()).thenReturn(Mono.just(List.of(position)));
        when(trailingStopService.calculateLiveTrailingStop(any(), any(), any(), any(), anyDouble(), anyInt()))
                .thenReturn(Optional.empty());

        StepVerifier.create(service.evaluateExit(
                Tick.builder().symbol("TEST").lastTradedPrice(105.0d).build()))
                .verifyComplete();

        verify(tradeExecutionClient, never()).updateProtection(any());
        verify(tradeExecutionClient, never()).exitTrade(any());
    }

    @Test
    void liveTrailingStopRequiresExactOpenPersistedValueConfirmation() {
        TradeExecutionClient.TradeExecutionPosition position = livePosition(
                54L, "BUY", "100.00", "90.00", "90.00", "130.00");
        TradeExecutionClient.TradeExecutionPosition mismatched = livePosition(
                54L, "BUY", "100.00", "90.00", "99.00", "130.00");
        when(tradeExecutionClient.getOpenTrades()).thenReturn(Mono.just(List.of(position)));
        when(trailingStopService.calculateLiveTrailingStop(any(), any(), any(), any(), anyDouble(), anyInt()))
                .thenReturn(Optional.of(new BigDecimal("100.00")));
        when(tradeExecutionClient.updateProtection(any())).thenReturn(Mono.just(mismatched));

        StepVerifier.create(service.evaluateExit(
                Tick.builder().symbol("TEST").lastTradedPrice(105.0d).build()))
                .verifyComplete();

        verify(tradeExecutionClient, times(1)).updateProtection(any());
        verify(tradeExecutionClient, never()).exitTrade(any());
    }

    @Test
    void liveTrailingStopUpdateErrorDoesNotBlindlyRetryOrSubmitExit() {
        TradeExecutionClient.TradeExecutionPosition position = livePosition(
                55L, "BUY", "100.00", "90.00", "90.00", "130.00");
        when(tradeExecutionClient.getOpenTrades()).thenReturn(Mono.just(List.of(position)));
        when(trailingStopService.calculateLiveTrailingStop(any(), any(), any(), any(), anyDouble(), anyInt()))
                .thenReturn(Optional.of(new BigDecimal("100.00")));
        when(tradeExecutionClient.updateProtection(any()))
                .thenReturn(Mono.error(new RuntimeException("simulated timeout")));

        StepVerifier.create(service.evaluateExit(
                Tick.builder().symbol("TEST").lastTradedPrice(105.0d).build()))
                .verifyComplete();

        verify(tradeExecutionClient, times(1)).updateProtection(any());
        verify(tradeExecutionClient, never()).exitTrade(any());
    }


    @Test
    void liveTargetHitRequestsExitWhenTargetExtensionIsDisabled() {
        TradeExecutionClient.TradeExecutionPosition position = livePosition(
                56L, "BUY", "100.00", "90.00", "90.00", "110.00");
        when(tradeExecutionClient.getOpenTrades()).thenReturn(Mono.just(List.of(position)));
        when(tradeExecutionClient.exitTrade(any())).thenReturn(Mono.just(
                new TradeExecutionClient.TradeExecutionResponse(
                        false, 56L, "TEST", "BUY", 10, new BigDecimal("110.00"),
                        null, "exit-order-56", "EXIT_PENDING",
                        "Exit submitted; reconciliation pending", 0, null)));

        StepVerifier.create(service.evaluateExit(
                Tick.builder().symbol("TEST").lastTradedPrice(110.00d).build()))
                .verifyComplete();

        verify(tradeExecutionClient, times(1)).exitTrade(argThat(request ->
                request.signalId().equals(56L)
                        && request.quantity() == 10
                        && "MARKET".equals(request.orderType())));
        verify(tradeExecutionClient, never()).updateProtection(any());
    }

    @Test
    void liveExitTimeoutIsNotAutomaticallyRetried() {
        TradeExecutionClient.TradeExecutionPosition position = livePosition(
                57L, "BUY", "100.00", "90.00", "90.00", "120.00");
        when(tradeExecutionClient.getOpenTrades()).thenReturn(Mono.just(List.of(position)));
        when(tradeExecutionClient.exitTrade(any()))
                .thenReturn(Mono.error(new RuntimeException("simulated exit timeout")));

        StepVerifier.create(service.evaluateExit(
                Tick.builder().symbol("TEST").lastTradedPrice(89.00d).build()))
                .verifyComplete();

        verify(tradeExecutionClient, times(1)).exitTrade(argThat(request ->
                request.signalId().equals(57L)
                        && request.quantity() == 10
                        && "MARKET".equals(request.orderType())));
    }

    @Test
    void rejectedLiveExitResponseIsNotAutomaticallyRetried() {
        TradeExecutionClient.TradeExecutionPosition position = livePosition(
                58L, "SELL", "100.00", "110.00", "110.00", "80.00");
        when(tradeExecutionClient.getOpenTrades()).thenReturn(Mono.just(List.of(position)));
        when(tradeExecutionClient.exitTrade(any())).thenReturn(Mono.just(
                new TradeExecutionClient.TradeExecutionResponse(
                        false, 58L, "TEST", "SELL", 10, null,
                        null, "exit-order-58", "REJECTED",
                        "Simulated broker rejection", 0, "REJECTED")));

        StepVerifier.create(service.evaluateExit(
                Tick.builder().symbol("TEST").lastTradedPrice(111.00d).build()))
                .verifyComplete();

        verify(tradeExecutionClient, times(1)).exitTrade(argThat(request ->
                request.signalId().equals(58L)
                        && request.quantity() == 10
                        && "MARKET".equals(request.orderType())));
    }

    @Test
    void liveTrailingStopUsesRealCalculatorAndRequiresPersistedConfirmation() {
        TradeExecutionClient.TradeExecutionPosition position = livePosition(
                59L, "BUY", "100.00", "90.00", "90.00", "130.00");
        TradeExecutionClient.TradeExecutionPosition persisted = livePosition(
                59L, "BUY", "100.00", "90.00", "100.30", "130.00");

        when(tradeExecutionClient.getOpenTrades()).thenReturn(Mono.just(List.of(position)));
        when(tradeExecutionClient.updateProtection(any())).thenReturn(Mono.just(persisted));

        TrailingStopService realCalculator = new TrailingStopService(
                paperTradeRepository, mock(NotificationClient.class),
                new com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator());
        TradeExitServiceImpl integrationService = new TradeExitServiceImpl(
                paperTradeRepository,
                tradeExecutionClient,
                exitOrchestrator,
                paperTradingService,
                realCalculator);
        ReflectionTestUtils.setField(integrationService, "executionTradeMode", "live");
        ReflectionTestUtils.setField(integrationService, "liveExecutionEnabled", true);
        ReflectionTestUtils.setField(integrationService, "maxLossPerTrade", 250.0d);

        StepVerifier.create(integrationService.evaluateExit(
                Tick.builder().symbol("TEST").lastTradedPrice(110.00d).build()))
                .verifyComplete();

        verify(tradeExecutionClient).updateProtection(argThat(request ->
                request.signalId().equals(59L)
                        && new BigDecimal("100.30").compareTo(request.stopLoss()) == 0
                        && request.targetPrice() == null
                        && request.targetExtended() == null));
        verify(tradeExecutionClient, never()).exitTrade(any());
    }

    @Test
    void liveExitEvaluationIsBlockedWhenLiveExecutionFlagIsFalse() {
        ReflectionTestUtils.setField(service, "executionTradeMode", "live");
        ReflectionTestUtils.setField(service, "liveExecutionEnabled", false);

        StepVerifier.create(service.evaluateExit(
                Tick.builder().symbol("TEST").lastTradedPrice(80.00d).build()))
                .verifyComplete();

        verifyNoInteractions(tradeExecutionClient);
        verifyNoInteractions(paperTradeRepository);
        verifyNoInteractions(paperTradingService);
    }

    @Test
    void paperModeUsesPaperRepositoryAndNeverCallsLiveExecutionClient() {
        ReflectionTestUtils.setField(service, "executionTradeMode", "paper");
        // Even a true live-enabled flag must not route paper mode to the broker client.
        ReflectionTestUtils.setField(service, "liveExecutionEnabled", true);
        when(paperTradeRepository.findTopBySymbolAndStatusOrderByEntryTimeDesc(
                "TEST", SignalStatus.OPEN)).thenReturn(Optional.empty());

        StepVerifier.create(service.evaluateExit(
                Tick.builder().symbol("TEST").lastTradedPrice(100.00d).build()))
                .verifyComplete();

        verify(paperTradeRepository).findTopBySymbolAndStatusOrderByEntryTimeDesc(
                "TEST", SignalStatus.OPEN);
        verifyNoInteractions(tradeExecutionClient);
        verifyNoInteractions(paperTradingService);
    }

    @Test
    void unknownExecutionModeFailsClosedWithoutCallingExecutionDependencies() {
        ReflectionTestUtils.setField(service, "executionTradeMode", "staging");

        StepVerifier.create(service.evaluateExit(
                Tick.builder().symbol("TEST").lastTradedPrice(100.00d).build()))
                .expectError(IllegalStateException.class)
                .verify();

        verifyNoInteractions(tradeExecutionClient);
        verifyNoInteractions(paperTradeRepository);
        verifyNoInteractions(paperTradingService);
    }

    @Test
    void invalidMarketTickDoesNotInvokePaperOrLiveExitDependencies() {
        StepVerifier.create(service.evaluateExit(
                Tick.builder().symbol("TEST").lastTradedPrice(Double.NaN).build()))
                .verifyComplete();

        verifyNoInteractions(tradeExecutionClient);
        verifyNoInteractions(paperTradeRepository);
        verifyNoInteractions(paperTradingService);
    }

    @Test
    void ticksDuringAnInFlightLiveExitDoNotSubmitAnotherExit() {
        TradeExecutionClient.TradeExecutionPosition position =
                livePosition(70L, "BUY", "100.00", "90.00", "90.00", "120.00");
        when(tradeExecutionClient.getOpenTrades()).thenReturn(Mono.just(List.of(position)));
        reactor.core.publisher.Sinks.One<TradeExecutionClient.TradeExecutionResponse> pendingExit =
                reactor.core.publisher.Sinks.one();
        when(tradeExecutionClient.exitTrade(any())).thenReturn(pendingExit.asMono());

        Tick tick = Tick.builder().symbol("TEST").lastTradedPrice(89.00d).build();

        // The tick request completes immediately while the exit is still in flight.
        StepVerifier.create(service.evaluateExit(tick)).verifyComplete();
        StepVerifier.create(service.evaluateExit(tick)).verifyComplete();
        verify(tradeExecutionClient, times(1)).exitTrade(any());

        pendingExit.tryEmitValue(new TradeExecutionClient.TradeExecutionResponse(
                true, 70L, "TEST", "SELL", 10, new BigDecimal("89.00"), null,
                "exit-order", "CLOSED", "closed", 10, null));

        // Once the exit finished, the snapshot is refreshed instead of reused.
        StepVerifier.create(service.evaluateExit(tick)).verifyComplete();
        verify(tradeExecutionClient, times(2)).getOpenTrades();
    }

    @Test
    void openPositionsAreReadOncePerSnapshotWindowAcrossTicks() {
        when(tradeExecutionClient.getOpenTrades()).thenReturn(Mono.just(List.of()));
        ReflectionTestUtils.setField(service, "openPositionsCacheMs", 60_000L);

        StepVerifier.create(service.evaluateExit(Tick.builder().symbol("AAA").lastTradedPrice(10.0d).build()))
                .verifyComplete();
        StepVerifier.create(service.evaluateExit(Tick.builder().symbol("BBB").lastTradedPrice(20.0d).build()))
                .verifyComplete();

        verify(tradeExecutionClient, times(1)).getOpenTrades();
    }

    private TradeExecutionClient.TradeExecutionPosition livePosition(
            Long signalId, String side, String entry, String initialStop,
            String currentStop, String target) {
        return new TradeExecutionClient.TradeExecutionPosition(
                1L, signalId, "TEST", "NSE_EQ|TEST", side, 10, 10,
                new BigDecimal(entry), new BigDecimal(initialStop), new BigDecimal(currentStop),
                new BigDecimal(target), "OPEN", "entry-order", null, null,
                0, 10, null, null, BigDecimal.ZERO, null, null, null, null, null, false);
    }

}
