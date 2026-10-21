package com.ram.trading.trade.execution;

import com.ram.trading.trade.execution.client.BrokerExecutionClient;
import com.ram.trading.trade.execution.dto.ExecutedTradeRecord;
import com.ram.trading.trade.execution.dto.BrokerOrderResponse;
import com.ram.trading.trade.execution.dto.UpstoxOrderDetailsResponse;
import com.ram.trading.trade.execution.dto.TradeExitRequest;
import com.ram.trading.trade.execution.dto.TradeProtectionUpdateRequest;
import com.ram.trading.trade.execution.dto.TradeExecutionRequest;
import com.ram.trading.trade.execution.readiness.ExecutionReadiness;
import com.ram.trading.trade.execution.repository.ExecutionIdempotencyRecord;
import com.ram.trading.trade.execution.repository.ExecutionIdempotencyRepository;
import com.ram.trading.trade.execution.repository.ExecutedTradeRepository;
import com.ram.trading.trade.execution.service.TradeExecutionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class TradeExecutionServiceImplTest {

    private BrokerExecutionClient brokerExecutionClient;
    private ExecutionIdempotencyRepository idempotencyRepository;
    private ExecutedTradeRepository executedTradeRepository;
    private ExecutionReadiness executionReadiness;
    private TradeExecutionServiceImpl service;

    @BeforeEach
    void setUp() {
        brokerExecutionClient = mock(BrokerExecutionClient.class);
        idempotencyRepository = mock(ExecutionIdempotencyRepository.class);
        executedTradeRepository = mock(ExecutedTradeRepository.class);
        executionReadiness = new ExecutionReadiness();
        service = new TradeExecutionServiceImpl(
                brokerExecutionClient,
                idempotencyRepository,
                executedTradeRepository,
                executionReadiness);
    }

    @Test
    void exitRequestsMustFailClosedUntilStartupRecoveryIsReady() {
        TradeExitRequest request = TradeExitRequest.builder()
                .signalId(123L)
                .quantity(1)
                .orderType("MARKET")
                .build();

        var response = service.exitTrade(request).block();

        assertNotNull(response);
        assertEquals("SERVICE_RECOVERING", response.getStatus());
        verifyNoInteractions(
                brokerExecutionClient,
                idempotencyRepository,
                executedTradeRepository);
    }


    @Test
    void protectionUpdateMustFailClosedUntilStartupRecoveryIsReady() {
        TradeProtectionUpdateRequest request = new TradeProtectionUpdateRequest(
                123L, new BigDecimal("95.00"), null);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> service.updateProtection(request).block());

        verifyNoInteractions(executedTradeRepository);
        verifyNoInteractions(brokerExecutionClient, idempotencyRepository);
    }

    @Test
    void validProtectionUpdateMustBePersistedOnlyAfterReadinessIsReady() {
        executionReadiness.markReady();
        TradeProtectionUpdateRequest request = new TradeProtectionUpdateRequest(
                123L, new BigDecimal("95.00"), new BigDecimal("125.00"), true);
        ExecutedTradeRecord updated = ExecutedTradeRecord.builder()
                .signalId(123L)
                .symbol("TCS")
                .side("BUY")
                .averageEntryPrice(new BigDecimal("100.00"))
                .currentStopLoss(new BigDecimal("95.00"))
                .targetPrice(new BigDecimal("125.00"))
                .targetExtended(true)
                .status("OPEN")
                .build();

        when(executedTradeRepository.updateProtectionLevels(
                123L, new BigDecimal("95.00"), new BigDecimal("125.00"), true))
                .thenReturn(updated);

        ExecutedTradeRecord response = service.updateProtection(request).block();

        assertNotNull(response);
        assertEquals("OPEN", response.getStatus());
        assertEquals(0, response.getCurrentStopLoss().compareTo(new BigDecimal("95.00")));
        assertEquals(0, response.getTargetPrice().compareTo(new BigDecimal("125.00")));
        verify(executedTradeRepository).updateProtectionLevels(
                123L, new BigDecimal("95.00"), new BigDecimal("125.00"), true);
        verifyNoInteractions(brokerExecutionClient, idempotencyRepository);
    }

    @Test
    void trailingStopOnlyUpdateMustReturnPersistedStopWithoutBrokerOrder() {
        executionReadiness.markReady();
        TradeProtectionUpdateRequest request = new TradeProtectionUpdateRequest(
                124L, new BigDecimal("100.00"), null, null);
        ExecutedTradeRecord persisted = ExecutedTradeRecord.builder()
                .signalId(124L)
                .symbol("TCS")
                .side("BUY")
                .averageEntryPrice(new BigDecimal("100.00"))
                .initialStopLoss(new BigDecimal("90.00"))
                .currentStopLoss(new BigDecimal("100.00"))
                .targetPrice(new BigDecimal("130.00"))
                .status("OPEN")
                .build();

        when(executedTradeRepository.updateProtectionLevels(
                124L, new BigDecimal("100.00"), null, null))
                .thenReturn(persisted);

        ExecutedTradeRecord response = service.updateProtection(request).block();

        assertNotNull(response);
        assertEquals(124L, response.getSignalId().longValue());
        assertEquals("OPEN", response.getStatus());
        assertEquals(0, response.getCurrentStopLoss().compareTo(new BigDecimal("100.00")));
        verify(executedTradeRepository).updateProtectionLevels(
                124L, new BigDecimal("100.00"), null, null);
        verifyNoInteractions(brokerExecutionClient, idempotencyRepository);
    }

    @Test
    void protectionUpdateWithoutAnyPriceMustBeRejectedBeforeRepositoryAccess() {
        executionReadiness.markReady();
        TradeProtectionUpdateRequest request = new TradeProtectionUpdateRequest(
                123L, null, null);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> service.updateProtection(request).block());

        verifyNoInteractions(executedTradeRepository);
        verifyNoInteractions(brokerExecutionClient, idempotencyRepository);
    }

    @Test
    void targetExtensionFlagWithoutTargetPriceMustBeRejectedBeforeRepositoryAccess() {
        executionReadiness.markReady();
        TradeProtectionUpdateRequest request = new TradeProtectionUpdateRequest(
                123L, null, null, true);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> service.updateProtection(request).block());

        verifyNoInteractions(executedTradeRepository);
        verifyNoInteractions(brokerExecutionClient, idempotencyRepository);
    }

    @Test
    void exitAttemptTagsMustBeUniqueAndLegacyPendingTagsMustRemainStable() throws Exception {
        var method = TradeExecutionServiceImpl.class.getDeclaredMethod(
                "buildExitTag", Long.class, Integer.class, int.class, int.class);
        method.setAccessible(true);

        assertEquals("AI-X-123-1", method.invoke(service, 123L, 1, 0, 10));
        assertEquals("AI-X-123-2", method.invoke(service, 123L, 2, 0, 10));
        assertEquals("AI-EXIT-123-0-10", method.invoke(service, 123L, 0, 0, 10));
    }

    @Test
    void persistedPendingExitOrderTimeoutMustNotSubmitAnotherOrder() {
        executionReadiness.markReady();

        ExecutedTradeRecord trade = ExecutedTradeRecord.builder()
                .signalId(123L)
                .symbol("TCS")
                .instrumentKey("NSE_EQ|INE467B01029")
                .side("BUY")
                .requestedQuantity(10)
                .filledQuantity(10)
                .averageEntryPrice(new BigDecimal("100.00"))
                .status("EXIT_PENDING")
                .exitOrderId("existing-exit-order-123")
                .exitOrderQuantity(10)
                .exitFilledQuantity(0)
                .remainingQuantity(10)
                .exitAttempt(1)
                .build();

        when(executedTradeRepository.findBySignalId(123L))
                .thenReturn(Optional.of(trade));
        when(brokerExecutionClient.getOrderDetails("existing-exit-order-123"))
                .thenReturn(Mono.error(new RuntimeException("simulated broker timeout")));

        TradeExitRequest request = TradeExitRequest.builder()
                .signalId(123L)
                .quantity(10)
                .orderType("MARKET")
                .build();

        var response = service.exitTrade(request).block();

        assertNotNull(response);
        assertEquals("EXIT_PENDING", response.getStatus());
        assertEquals("existing-exit-order-123", response.getOrderId());

        verify(brokerExecutionClient, times(1))
                .getOrderDetails("existing-exit-order-123");
        verify(brokerExecutionClient, never()).executeOrder(any());
        verify(brokerExecutionClient, never()).findOrderByTag(any());
        verifyNoInteractions(idempotencyRepository);
    }

    @Test
    void invalidEntryRequestMustBeRejectedBeforeAnyBrokerOrRepositoryCall() {
        executionReadiness.markReady();

        TradeExecutionRequest request = TradeExecutionRequest.builder()
                .signalId(501L)
                .symbol("TCS")
                .instrumentKey("NSE_EQ|TEST")
                .signal("BUY")
                .quantity(1)
                .entryPrice(new BigDecimal("0"))
                .orderType("MARKET")
                .build();

        var response = service.executeTrade(request).block();

        assertNotNull(response);
        assertEquals("VALIDATION_FAILED", response.getStatus());
        verifyNoInteractions(brokerExecutionClient, idempotencyRepository, executedTradeRepository);
    }

    @Test
    void negativeBrokerFillQuantityMustRemainUnresolved() throws Exception {
        executionReadiness.markReady();
        TradeExecutionRequest request = validEntryRequest(506L);
        UpstoxOrderDetailsResponse.DataResponse details =
                new UpstoxOrderDetailsResponse.DataResponse();
        details.setOrderId("entry-506");
        details.setStatus("rejected");
        details.setFilledQuantity(-1);
        details.setAveragePrice(new BigDecimal("100.00"));
        when(idempotencyRepository.updatePending(
                506L, "entry-506", null, null,
                "Broker returned a negative cumulative filled quantity"))
                .thenReturn(Mono.empty());

        var method = TradeExecutionServiceImpl.class.getDeclaredMethod(
                "reconcileDetails", TradeExecutionRequest.class, String.class,
                UpstoxOrderDetailsResponse.DataResponse.class, int.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Mono<com.ram.trading.trade.execution.dto.TradeExecutionResponse> result =
                (Mono<com.ram.trading.trade.execution.dto.TradeExecutionResponse>)
                        method.invoke(service, request, "entry-506", details, 1);

        var response = result.block();
        assertNotNull(response);
        assertEquals("PENDING", response.getStatus());
        verify(idempotencyRepository).updatePending(
                506L, "entry-506", null, null,
                "Broker returned a negative cumulative filled quantity");
        verify(idempotencyRepository, never()).updateRejected(any(), any(), any());
        verifyNoInteractions(executedTradeRepository);
    }

    @Test
    void terminalPartialEntryFillMustPersistExposureAndTerminalizeClaim() throws Exception {
        executionReadiness.markReady();
        TradeExecutionRequest request = validEntryRequest(601L);
        UpstoxOrderDetailsResponse.DataResponse details = new UpstoxOrderDetailsResponse.DataResponse();
        details.setOrderId("entry-601");
        details.setStatus("cancelled");
        details.setFilledQuantity(1);
        details.setAveragePrice(new BigDecimal("100.00"));

        ExecutedTradeRecord savedTrade = ExecutedTradeRecord.builder()
                .signalId(601L).symbol("TCS").instrumentKey("NSE_EQ|TEST")
                .side("BUY").requestedQuantity(2).filledQuantity(1)
                .averageEntryPrice(new BigDecimal("100.00"))
                .status("OPEN").entryOrderId("entry-601")
                .exitFilledQuantity(0).remainingQuantity(1).build();
        when(executedTradeRepository.findBySignalId(601L)).thenReturn(Optional.empty());
        when(executedTradeRepository.save(any(ExecutedTradeRecord.class))).thenReturn(savedTrade);
        when(idempotencyRepository.updatePartialFilled(
                eq(601L), eq("entry-601"), eq(1), eq(new BigDecimal("100.00")), any()))
                .thenReturn(Mono.empty());

        var method = TradeExecutionServiceImpl.class.getDeclaredMethod(
                "reconcileDetails", TradeExecutionRequest.class, String.class,
                UpstoxOrderDetailsResponse.DataResponse.class, int.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Mono<com.ram.trading.trade.execution.dto.TradeExecutionResponse> result =
                (Mono<com.ram.trading.trade.execution.dto.TradeExecutionResponse>)
                        method.invoke(service, request, "entry-601", details, 1);

        var response = result.block();
        assertNotNull(response);
        assertEquals("PENDING", response.getStatus(),
                "The position exists but requires manual reconciliation of the unfilled remainder");
        verify(executedTradeRepository).save(any(ExecutedTradeRecord.class));
        verify(idempotencyRepository).updatePartialFilled(
                eq(601L), eq("entry-601"), eq(1), eq(new BigDecimal("100.00")), any());
        verify(idempotencyRepository, never()).updatePending(
                eq(601L), any(), any(), any(), any());
    }

    @Test
    void brokerHistoryFailureMustFailClosedWithoutSubmittingOrClaiming() {
        executionReadiness.markReady();

        TradeExecutionRequest request = validEntryRequest(502L);
        when(brokerExecutionClient.findOrderByTag("AI-502"))
                .thenReturn(Mono.error(new RuntimeException("simulated broker history timeout")));

        var response = service.executeTrade(request).block();

        assertNotNull(response);
        assertEquals("EXECUTION_CHECK_FAILED", response.getStatus());
        verify(brokerExecutionClient).findOrderByTag("AI-502");
        verify(brokerExecutionClient, never()).executeOrder(any());
        verifyNoInteractions(idempotencyRepository, executedTradeRepository);
    }

    @Test
    void existingClaimWithDifferentInstrumentMetadataMustFailClosed() {
        executionReadiness.markReady();
        TradeExecutionRequest request = validEntryRequest(505L);
        ExecutionIdempotencyRecord existing = new ExecutionIdempotencyRecord(
                505L, "AI-505", "TCS", "BUY", 2, "PENDING", null,
                0, null, null, "NSE_EQ|DIFFERENT", new BigDecimal("100.00"),
                null, new BigDecimal("99.00"), new BigDecimal("102.00"), null, null);

        when(brokerExecutionClient.findOrderByTag("AI-505")).thenReturn(Mono.empty());
        when(idempotencyRepository.find(505L)).thenReturn(Mono.just(existing));

        var response = service.executeTrade(request).block();

        assertNotNull(response);
        assertEquals("IDEMPOTENCY_CONFLICT", response.getStatus());
        verify(brokerExecutionClient, never()).executeOrder(any());
        verify(idempotencyRepository, never()).tryClaim(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void liveDisabledMustNotCreateNewEntryClaimOrSubmitOrder() {
        executionReadiness.markReady();

        TradeExecutionRequest request = validEntryRequest(503L);
        when(brokerExecutionClient.findOrderByTag("AI-503")).thenReturn(Mono.empty());
        when(idempotencyRepository.find(503L)).thenReturn(Mono.empty());

        var response = service.executeTrade(request).block();

        assertNotNull(response);
        assertEquals("LIVE_DISABLED", response.getStatus());
        verify(brokerExecutionClient).findOrderByTag("AI-503");
        verify(brokerExecutionClient, never()).executeOrder(any());
        verify(idempotencyRepository, never()).tryClaim(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void liveDisabledMustNotClaimOrSubmitNewExitOrder() {
        executionReadiness.markReady();

        ExecutedTradeRecord trade = ExecutedTradeRecord.builder()
                .signalId(504L)
                .symbol("TCS")
                .instrumentKey("NSE_EQ|TEST")
                .side("BUY")
                .requestedQuantity(10)
                .filledQuantity(10)
                .averageEntryPrice(new BigDecimal("100.00"))
                .status("OPEN")
                .exitFilledQuantity(0)
                .remainingQuantity(10)
                .build();

        when(executedTradeRepository.findBySignalId(504L)).thenReturn(Optional.of(trade));

        TradeExitRequest request = TradeExitRequest.builder()
                .signalId(504L)
                .quantity(10)
                .orderType("MARKET")
                .build();

        var response = service.exitTrade(request).block();

        assertNotNull(response);
        assertEquals("LIVE_DISABLED", response.getStatus());
        verify(executedTradeRepository, never()).claimExit(eq(504L), anyInt());
        verify(brokerExecutionClient, never()).executeOrder(any());
        verifyNoInteractions(idempotencyRepository);
    }


    @Test
    void confirmedPartialExitMustPersistFillAndLeaveRemainingPositionOpen() throws Exception {
        executionReadiness.markReady();

        // This test exercises the service orchestration. Repository-level tests
        // separately verify cumulative quantity and realized-P&L calculations.
        var liveEnabledField = TradeExecutionServiceImpl.class.getDeclaredField("liveEnabled");
        liveEnabledField.setAccessible(true);
        liveEnabledField.setBoolean(service, true);

        ExecutedTradeRecord openTrade = ExecutedTradeRecord.builder()
                .signalId(601L)
                .symbol("TCS")
                .instrumentKey("NSE_EQ|TEST")
                .side("BUY")
                .requestedQuantity(10)
                .filledQuantity(10)
                .averageEntryPrice(new BigDecimal("100.00"))
                .status("OPEN")
                .exitFilledQuantity(0)
                .remainingQuantity(10)
                .realizedPnl(BigDecimal.ZERO)
                .exitAttempt(0)
                .build();

        ExecutedTradeRecord claimedTrade = ExecutedTradeRecord.builder()
                .signalId(601L)
                .symbol("TCS")
                .instrumentKey("NSE_EQ|TEST")
                .side("BUY")
                .requestedQuantity(10)
                .filledQuantity(10)
                .averageEntryPrice(new BigDecimal("100.00"))
                .status("EXIT_PENDING")
                .exitOrderQuantity(2)
                .exitFilledQuantity(0)
                .remainingQuantity(10)
                .realizedPnl(BigDecimal.ZERO)
                .exitAttempt(1)
                .build();

        ExecutedTradeRecord persistedPendingTrade = ExecutedTradeRecord.builder()
                .signalId(601L)
                .symbol("TCS")
                .instrumentKey("NSE_EQ|TEST")
                .side("BUY")
                .requestedQuantity(10)
                .filledQuantity(10)
                .averageEntryPrice(new BigDecimal("100.00"))
                .status("EXIT_PENDING")
                .exitOrderId("exit-601")
                .exitOrderQuantity(2)
                .exitFilledQuantity(0)
                .remainingQuantity(10)
                .realizedPnl(BigDecimal.ZERO)
                .exitAttempt(1)
                .build();

        ExecutedTradeRecord partiallyExitedTrade = ExecutedTradeRecord.builder()
                .signalId(601L)
                .symbol("TCS")
                .side("BUY")
                .filledQuantity(10)
                .averageEntryPrice(new BigDecimal("100.00"))
                .status("OPEN")
                .exitOrderId("exit-601")
                .exitOrderQuantity(2)
                .exitFilledQuantity(2)
                .remainingQuantity(8)
                .exitAveragePrice(new BigDecimal("110.00"))
                .realizedPnl(new BigDecimal("20.00"))
                .build();

        when(executedTradeRepository.findBySignalId(601L))
                .thenReturn(Optional.of(openTrade),
                        Optional.of(claimedTrade),
                        Optional.of(persistedPendingTrade));
        when(executedTradeRepository.claimExit(601L, 2)).thenReturn(true);
        when(brokerExecutionClient.findOrderByTag("AI-X-601-1"))
                .thenReturn(Mono.empty());
        when(brokerExecutionClient.executeOrder(any()))
                .thenReturn(Mono.just(BrokerOrderResponse.builder()
                        .success(true)
                        .orderId("exit-601")
                        .status("OPEN")
                        .build()));
        when(executedTradeRepository.persistExitOrderDetailsIfPending(
                601L, "exit-601", 2)).thenReturn(true);

        UpstoxOrderDetailsResponse.DataResponse terminalDetails =
                new UpstoxOrderDetailsResponse.DataResponse();
        terminalDetails.setOrderId("exit-601");
        terminalDetails.setStatus("complete");
        terminalDetails.setFilledQuantity(2);
        terminalDetails.setAveragePrice(new BigDecimal("110.00"));
        when(brokerExecutionClient.getOrderDetails("exit-601"))
                .thenReturn(Mono.just(terminalDetails));
        when(executedTradeRepository.applyExitFill(
                601L, "exit-601", 2, new BigDecimal("110.00"), "complete"))
                .thenReturn(partiallyExitedTrade);

        TradeExitRequest request = TradeExitRequest.builder()
                .signalId(601L)
                .quantity(2)
                .orderType("MARKET")
                .build();

        var response = service.exitTrade(request).block();

        assertNotNull(response);
        assertEquals("PARTIAL_EXIT", response.getStatus());
        assertEquals("exit-601", response.getOrderId());
        assertEquals(2, response.getFilledQuantity());
        verify(executedTradeRepository).applyExitFill(
                601L, "exit-601", 2, new BigDecimal("110.00"), "complete");
        verify(brokerExecutionClient, times(1)).executeOrder(any());
        verify(executedTradeRepository, never()).releaseRejectedExitClaim(any());
    }

    private TradeExecutionRequest validEntryRequest(Long signalId) {
        return TradeExecutionRequest.builder()
                .signalId(signalId)
                .symbol("TCS")
                .instrumentKey("NSE_EQ|TEST")
                .signal("BUY")
                .quantity(2)
                .entryPrice(new BigDecimal("100.00"))
                .stopLoss(new BigDecimal("99.00"))
                .target(new BigDecimal("102.00"))
                .orderType("MARKET")
                .build();
    }

}
