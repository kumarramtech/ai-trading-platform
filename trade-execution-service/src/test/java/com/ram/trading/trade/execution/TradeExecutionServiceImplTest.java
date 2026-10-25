package com.ram.trading.trade.execution;

import com.ram.trading.trade.execution.client.BrokerExecutionClient;
import com.ram.trading.trade.execution.dto.ExecutedTradeRecord;
import com.ram.trading.trade.execution.dto.BrokerOrderResponse;
import com.ram.trading.trade.execution.dto.UpstoxOrderDetailsResponse;
import com.ram.trading.trade.execution.dto.TradeExitRequest;
import com.ram.trading.trade.execution.dto.TradeProtectionUpdateRequest;
import com.ram.trading.trade.execution.dto.TradeExecutionRequest;
import com.ram.trading.trade.execution.readiness.ExecutionReadiness;
import com.ram.trading.trade.execution.repository.ExecutionIdempotencyRepository;
import com.ram.trading.trade.execution.repository.ExecutedTradeRepository;
import com.ram.trading.trade.execution.risk.ExecutionRiskGuard;
import com.ram.trading.trade.execution.service.TradeExecutionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                executionReadiness,
                riskGuardAt(WEDNESDAY_10_30_IST));
    }

    /** A regular trading-session instant (Wednesday 10:30 IST). */
    private static final Instant WEDNESDAY_10_30_IST = Instant.parse("2026-10-28T05:00:00Z");

    private ExecutionRiskGuard riskGuardAt(Instant now) {
        return new ExecutionRiskGuard(
                executedTradeRepository, idempotencyRepository,
                "Asia/Kolkata",
                new BigDecimal("10000"), 2, 6,
                Clock.fixed(now, ZoneId.of("Asia/Kolkata")));
    }

    @Test
    void runtimeReconciliationMustLeaveWorkingEntryPendingWithoutCancellingOrResubmitting() {
        ExecutedTradeRecord pendingEntry = ExecutedTradeRecord.builder()
                .signalId(701L)
                .symbol("TCS")
                .instrumentKey("NSE_EQ|INE467B01029")
                .side("BUY")
                .requestedQuantity(2)
                .filledQuantity(0)
                .averageEntryPrice(new BigDecimal("100.00"))
                .initialStopLoss(new BigDecimal("95.00"))
                .targetPrice(new BigDecimal("110.00"))
                .entryOrderId("entry-701")
                .status("ENTRY_PENDING")
                .build();
        UpstoxOrderDetailsResponse.DataResponse brokerDetails =
                new UpstoxOrderDetailsResponse.DataResponse();
        brokerDetails.setOrderId("entry-701");
        brokerDetails.setStatus("open");
        brokerDetails.setFilledQuantity(0);
        brokerDetails.setPendingQuantity(2);

        when(executedTradeRepository.findExitPendingTrades()).thenReturn(List.of());
        when(executedTradeRepository.findEntryPendingTrades()).thenReturn(List.of(pendingEntry));
        when(brokerExecutionClient.getOrderDetails("entry-701"))
                .thenReturn(Mono.just(brokerDetails));

        service.reconcilePendingOrdersAtRuntime().block();

        verify(brokerExecutionClient).getOrderDetails("entry-701");
        verify(brokerExecutionClient, never()).cancelOrder(any());
        verify(brokerExecutionClient, never()).executeOrder(any());
        verify(brokerExecutionClient, never()).executeExitOrder(any());
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

        ExecutedTradeRecord existing = ExecutedTradeRecord.builder()
                .signalId(123L)
                .symbol("TCS")
                .side("BUY")
                .averageEntryPrice(new BigDecimal("100.00"))
                .initialStopLoss(new BigDecimal("90.00"))
                .currentStopLoss(new BigDecimal("90.00"))
                .targetPrice(new BigDecimal("120.00"))
                .status("OPEN")
                .build();
        when(executedTradeRepository.findBySignalId(123L))
                .thenReturn(Optional.of(existing));
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

        ExecutedTradeRecord existing = ExecutedTradeRecord.builder()
                .signalId(124L)
                .symbol("TCS")
                .side("BUY")
                .averageEntryPrice(new BigDecimal("100.00"))
                .initialStopLoss(new BigDecimal("90.00"))
                .currentStopLoss(new BigDecimal("95.00"))
                .targetPrice(new BigDecimal("130.00"))
                .status("OPEN")
                .build();
        when(executedTradeRepository.findBySignalId(124L))
                .thenReturn(Optional.of(existing));
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
    void trailingStopUpdateMustModifyAndVerifyExistingBrokerProtectiveOrder() throws Exception {
        executionReadiness.markReady();
        enableLiveExecution();

        ExecutedTradeRecord existing = ExecutedTradeRecord.builder()
                .signalId(125L).symbol("TCS").instrumentKey("NSE_EQ|INE467B01029")
                .side("BUY").requestedQuantity(2).filledQuantity(2).remainingQuantity(2)
                .averageEntryPrice(new BigDecimal("100.00"))
                .initialStopLoss(new BigDecimal("90.00"))
                .currentStopLoss(new BigDecimal("95.00"))
                .targetPrice(new BigDecimal("120.00")).status("OPEN")
                .protectiveOrderId("stop-125").protectiveOrderStatus("ACTIVE").protectiveOrderQuantity(2)
                .protectiveTriggerPrice(new BigDecimal("95.00")).protectiveOrderTag("AI-SL-125")
                .build();
        ExecutedTradeRecord updated = ExecutedTradeRecord.builder()
                .signalId(125L).symbol("TCS").side("BUY").status("OPEN")
                .currentStopLoss(new BigDecimal("96.00")).targetPrice(new BigDecimal("120.00"))
                .protectiveOrderId("stop-125").protectiveOrderQuantity(2)
                .protectiveTriggerPrice(new BigDecimal("96.00")).protectiveOrderTag("AI-SL-125")
                .build();
        UpstoxOrderDetailsResponse.DataResponse brokerDetails = new UpstoxOrderDetailsResponse.DataResponse();
        brokerDetails.setOrderId("stop-125");
        brokerDetails.setStatus("open");
        brokerDetails.setQuantity(2);
        brokerDetails.setTriggerPrice(new BigDecimal("96.00"));

        when(executedTradeRepository.findBySignalId(125L)).thenReturn(Optional.of(existing));
        when(executedTradeRepository.claimProtectiveModification(125L, "stop-125")).thenReturn(true);
        when(brokerExecutionClient.modifyOrder(eq("stop-125"), any()))
                .thenReturn(Mono.just(BrokerOrderResponse.builder().success(true)
                        .orderId("stop-125").status("MODIFY_ACKNOWLEDGED").build()));
        when(brokerExecutionClient.getOrderDetails("stop-125")).thenReturn(Mono.just(brokerDetails));
        when(executedTradeRepository.updateProtectionLevels(
                125L, new BigDecimal("96.00"), null, null)).thenReturn(updated);
        when(executedTradeRepository.updateProtectiveOrderState(
                125L, "stop-125", "ACTIVE", 2, new BigDecimal("96.00"), "AI-SL-125"))
                .thenReturn(updated);

        ExecutedTradeRecord response = service.updateProtection(
                new TradeProtectionUpdateRequest(125L, new BigDecimal("96.00"), null, null)).block();

        assertNotNull(response);
        assertEquals(125L, response.getSignalId().longValue());
        verify(brokerExecutionClient).modifyOrder(eq("stop-125"), argThat(order ->
                "SELL".equals(order.getTransactionType())
                        && order.getQuantity() == 2
                        && "SL-M".equals(order.getOrderType())
                        && order.getTriggerPrice().compareTo(new BigDecimal("96.00")) == 0));
        verify(brokerExecutionClient).getOrderDetails("stop-125");
        verify(executedTradeRepository).updateProtectionLevels(
                125L, new BigDecimal("96.00"), null, null);
    }

    @Test
    void brokerStopModificationTimeoutMustNotPersistNewProtectionLevels() throws Exception {
        executionReadiness.markReady();
        enableLiveExecution();
        ExecutedTradeRecord existing = ExecutedTradeRecord.builder()
                .signalId(126L).symbol("TCS").instrumentKey("NSE_EQ|INE467B01029")
                .side("BUY").requestedQuantity(2).filledQuantity(2).remainingQuantity(2)
                .currentStopLoss(new BigDecimal("95.00")).status("OPEN")
                .protectiveOrderId("stop-126").protectiveOrderStatus("ACTIVE").protectiveOrderQuantity(2)
                .protectiveTriggerPrice(new BigDecimal("95.00")).protectiveOrderTag("AI-SL-126")
                .build();
        when(executedTradeRepository.findBySignalId(126L)).thenReturn(Optional.of(existing));
        when(executedTradeRepository.claimProtectiveModification(126L, "stop-126")).thenReturn(true);
        when(brokerExecutionClient.modifyOrder(eq("stop-126"), any()))
                .thenReturn(Mono.error(new RuntimeException("simulated broker timeout")));
        when(executedTradeRepository.updateProtectiveOrderState(
                126L, "stop-126", "RECONCILIATION_REQUIRED", null, null, null)).thenReturn(existing);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.updateProtection(new TradeProtectionUpdateRequest(
                        126L, new BigDecimal("96.00"), null, null)).block());

        verify(executedTradeRepository, never()).updateProtectionLevels(
                126L, new BigDecimal("96.00"), null, null);
        // The unconfirmed requested trigger must not overwrite the last confirmed broker trigger.
        verify(executedTradeRepository).updateProtectiveOrderState(
                126L, "stop-126", "RECONCILIATION_REQUIRED", null, null, null);
        verify(brokerExecutionClient, never()).executeOrder(any());
    }

    @Test
    void stopModificationMustBeRefusedUnlessBrokerStopIsConfirmedActive() throws Exception {
        executionReadiness.markReady();
        enableLiveExecution();
        ExecutedTradeRecord existing = ExecutedTradeRecord.builder()
                .signalId(127L).symbol("TCS").instrumentKey("NSE_EQ|INE467B01029")
                .side("BUY").requestedQuantity(2).filledQuantity(2).remainingQuantity(2)
                .currentStopLoss(new BigDecimal("95.00")).status("OPEN")
                .protectiveOrderId("stop-127").protectiveOrderStatus("RECONCILIATION_REQUIRED")
                .protectiveOrderQuantity(2)
                // A previous failed modification must not let an equal trigger skip the broker.
                .protectiveTriggerPrice(new BigDecimal("96.00")).protectiveOrderTag("AI-SL-127")
                .build();
        when(executedTradeRepository.findBySignalId(127L)).thenReturn(Optional.of(existing));
        when(executedTradeRepository.claimProtectiveModification(127L, "stop-127")).thenReturn(false);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.updateProtection(new TradeProtectionUpdateRequest(
                        127L, new BigDecimal("96.00"), null, null)).block());

        verify(executedTradeRepository, never()).updateProtectionLevels(any(), any(), any(), any());
        verify(brokerExecutionClient, never()).modifyOrder(any(), any());
    }

    private void enableLiveExecution() throws Exception {
        var liveEnabledField = TradeExecutionServiceImpl.class.getDeclaredField("liveEnabled");
        liveEnabledField.setAccessible(true);
        liveEnabledField.setBoolean(service, true);
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


    private ExecutedTradeRecord.ExecutedTradeRecordBuilder protectedOpenTrade(long signalId) {
        return ExecutedTradeRecord.builder()
                .signalId(signalId)
                .symbol("TCS")
                .instrumentKey("NSE_EQ|TEST")
                .side("BUY")
                .requestedQuantity(2)
                .filledQuantity(2)
                .averageEntryPrice(new BigDecimal("100.00"))
                .status("OPEN")
                .exitFilledQuantity(0)
                .remainingQuantity(2)
                .exitAttempt(0)
                .protectiveOrderId("stop-" + signalId)
                .protectiveOrderStatus("ACTIVE")
                .protectiveOrderQuantity(2)
                .protectiveTriggerPrice(new BigDecimal("95.00"));
    }

    private UpstoxOrderDetailsResponse.DataResponse orderDetails(
            String orderId, String status, int filled, String averagePrice) {
        UpstoxOrderDetailsResponse.DataResponse details = new UpstoxOrderDetailsResponse.DataResponse();
        details.setOrderId(orderId);
        details.setStatus(status);
        details.setQuantity(2);
        details.setFilledQuantity(filled);
        details.setTriggerPrice(new BigDecimal("95.00"));
        details.setAveragePrice(averagePrice == null ? null : new BigDecimal(averagePrice));
        return details;
    }

    @Test
    void marketExitMustCancelAndConfirmBrokerStopBeforeSubmitting() throws Exception {
        executionReadiness.markReady();
        enableLiveExecution();

        ExecutedTradeRecord open = protectedOpenTrade(505L).build();
        ExecutedTradeRecord stopCancelled = protectedOpenTrade(505L).protectiveOrderStatus("CANCELLED").build();
        ExecutedTradeRecord claimed = protectedOpenTrade(505L).protectiveOrderStatus("CANCELLED")
                .status("EXIT_PENDING").exitOrderQuantity(2).exitAttempt(1).build();
        ExecutedTradeRecord pending = protectedOpenTrade(505L).protectiveOrderStatus("CANCELLED")
                .status("EXIT_PENDING").exitOrderId("exit-505").exitOrderQuantity(2).exitAttempt(1).build();
        ExecutedTradeRecord closed = protectedOpenTrade(505L).protectiveOrderStatus("CANCELLED")
                .status("CLOSED").exitOrderId("exit-505").exitFilledQuantity(2).remainingQuantity(0)
                .exitAveragePrice(new BigDecimal("105.00")).build();

        when(executedTradeRepository.findBySignalId(505L))
                .thenReturn(Optional.of(open), Optional.of(claimed), Optional.of(pending));
        when(brokerExecutionClient.getOrderDetails("stop-505"))
                .thenReturn(Mono.just(orderDetails("stop-505", "trigger pending", 0, null)),
                        Mono.just(orderDetails("stop-505", "cancelled", 0, null)));
        when(brokerExecutionClient.cancelOrder("stop-505")).thenReturn(Mono.just(true));
        when(executedTradeRepository.updateProtectiveOrderState(505L, "stop-505", "CANCELLED", null, null, null))
                .thenReturn(stopCancelled);
        when(executedTradeRepository.claimExit(505L, 2)).thenReturn(true);
        when(brokerExecutionClient.findOrderByTag("AI-X-505-1")).thenReturn(Mono.empty());
        when(brokerExecutionClient.executeOrder(any())).thenReturn(Mono.just(BrokerOrderResponse.builder()
                .success(true).orderId("exit-505").status("ORDER_PLACED").build()));
        when(executedTradeRepository.persistExitOrderDetailsIfPending(505L, "exit-505", 2)).thenReturn(true);
        when(brokerExecutionClient.getOrderDetails("exit-505"))
                .thenReturn(Mono.just(orderDetails("exit-505", "complete", 2, "105.00")));
        when(executedTradeRepository.applyExitFill(505L, "exit-505", 2, new BigDecimal("105.00"), "complete"))
                .thenReturn(closed);

        var response = service.exitTrade(TradeExitRequest.builder()
                .signalId(505L).quantity(2).orderType("MARKET").build()).block();

        assertNotNull(response);
        assertEquals("CLOSED", response.getStatus());
        var order = inOrder(brokerExecutionClient, executedTradeRepository);
        order.verify(brokerExecutionClient).cancelOrder("stop-505");
        order.verify(executedTradeRepository).updateProtectiveOrderState(
                505L, "stop-505", "CANCELLED", null, null, null);
        order.verify(executedTradeRepository).claimExit(505L, 2);
        order.verify(brokerExecutionClient).executeOrder(argThat(exit ->
                "SELL".equals(exit.getTransactionType()) && exit.getQuantity() == 2));
    }

    @Test
    void brokerStopThatAlreadyFilledMustCloseTradeWithoutMarketExit() throws Exception {
        executionReadiness.markReady();
        enableLiveExecution();

        ExecutedTradeRecord closedByStop = protectedOpenTrade(506L).protectiveOrderStatus("FILL_APPLIED")
                .status("CLOSED").exitOrderId("stop-506").exitFilledQuantity(2).remainingQuantity(0)
                .exitAveragePrice(new BigDecimal("95.00")).build();
        when(executedTradeRepository.findBySignalId(506L))
                .thenReturn(Optional.of(protectedOpenTrade(506L).build()));
        when(brokerExecutionClient.getOrderDetails("stop-506"))
                .thenReturn(Mono.just(orderDetails("stop-506", "complete", 2, "95.00")));
        when(executedTradeRepository.applyProtectiveStopFill(506L, "stop-506", 2, new BigDecimal("95.00")))
                .thenReturn(closedByStop);

        var response = service.exitTrade(TradeExitRequest.builder()
                .signalId(506L).quantity(2).orderType("MARKET").build()).block();

        assertNotNull(response);
        assertEquals("CLOSED", response.getStatus());
        assertEquals("stop-506", response.getOrderId());
        verify(brokerExecutionClient, never()).cancelOrder(any());
        verify(brokerExecutionClient, never()).executeOrder(any());
        verify(executedTradeRepository, never()).claimExit(eq(506L), anyInt());
    }

    @Test
    void marketExitMustBeBlockedWhileBrokerStopIsStillWorkingAfterCancel() throws Exception {
        executionReadiness.markReady();
        enableLiveExecution();

        when(executedTradeRepository.findBySignalId(507L))
                .thenReturn(Optional.of(protectedOpenTrade(507L).build()));
        when(brokerExecutionClient.getOrderDetails("stop-507"))
                .thenReturn(Mono.just(orderDetails("stop-507", "trigger pending", 0, null)));
        when(brokerExecutionClient.cancelOrder("stop-507")).thenReturn(Mono.just(true));

        var response = service.exitTrade(TradeExitRequest.builder()
                .signalId(507L).quantity(2).orderType("MARKET").build()).block();

        assertNotNull(response);
        assertEquals("EXIT_BLOCKED", response.getStatus());
        verify(executedTradeRepository, never()).claimExit(eq(507L), anyInt());
        verify(brokerExecutionClient, never()).executeOrder(any());
    }

    @Test
    void positionFromAnEarlierSessionMustNeverBeExited() throws Exception {
        executionReadiness.markReady();
        enableLiveExecution();

        when(executedTradeRepository.findBySignalId(508L)).thenReturn(Optional.of(
                protectedOpenTrade(508L).openedAt(LocalDateTime.of(2026, 10, 27, 10, 0)).build()));

        var response = service.exitTrade(TradeExitRequest.builder()
                .signalId(508L).quantity(2).orderType("MARKET").build()).block();

        assertNotNull(response);
        assertEquals("STALE_POSITION", response.getStatus());
        verifyNoInteractions(brokerExecutionClient);
        verify(executedTradeRepository, never()).claimExit(eq(508L), anyInt());
    }

    @Test
    void runtimeReconciliationMustBookBrokerStopFillAgainstOpenTrade() throws Exception {
        enableLiveExecution();

        ExecutedTradeRecord open = protectedOpenTrade(520L).build();
        when(executedTradeRepository.findExitPendingTrades()).thenReturn(List.of());
        when(executedTradeRepository.findEntryPendingTrades()).thenReturn(List.of());
        when(executedTradeRepository.findOpenTrades()).thenReturn(List.of(open));
        when(brokerExecutionClient.getOrderDetails("stop-520"))
                .thenReturn(Mono.just(orderDetails("stop-520", "complete", 2, "95.00")));
        when(executedTradeRepository.applyProtectiveStopFill(520L, "stop-520", 2, new BigDecimal("95.00")))
                .thenReturn(protectedOpenTrade(520L).status("CLOSED").protectiveOrderStatus("FILL_APPLIED")
                        .exitFilledQuantity(2).remainingQuantity(0).build());

        service.reconcilePendingOrdersAtRuntime().block();

        verify(executedTradeRepository).applyProtectiveStopFill(520L, "stop-520", 2, new BigDecimal("95.00"));
        verify(brokerExecutionClient, never()).executeOrder(any());
        verify(brokerExecutionClient, never()).cancelOrder(any());
    }

    @Test
    void newLiveEntryBeyondOpenPositionLimitMustBeRejectedWithoutClaim() throws Exception {
        executionReadiness.markReady();
        enableLiveExecution();

        TradeExecutionRequest request = liveEntryRequest(510L);
        when(brokerExecutionClient.findOrderByTag("AI-510")).thenReturn(Mono.empty());
        when(idempotencyRepository.find(510L)).thenReturn(Mono.empty());
        when(executedTradeRepository.countActivePositions()).thenReturn(2);

        var response = service.executeTrade(request).block();

        assertNotNull(response);
        assertEquals("RISK_REJECTED", response.getStatus());
        verify(idempotencyRepository, never()).tryClaim(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(brokerExecutionClient, never()).executeOrder(any());
    }

    @Test
    void executionBackstopsMustNotGateEntriesByTimeOfDay() {
        // Entry windows are owned by Signal Engine; execution must not second-guess them.
        ExecutionRiskGuard lateGuard = riskGuardAt(Instant.parse("2026-10-28T09:35:00Z")); // 15:05 IST

        assertNull(lateGuard.evaluateNewEntry(liveEntryRequest(512L)));
    }

    @Test
    void definitiveBrokerRejectionMustResolveClaimAsRejectedNotUnknown() throws Exception {
        executionReadiness.markReady();
        enableLiveExecution();

        when(brokerExecutionClient.findOrderByTag("AI-511")).thenReturn(Mono.empty());
        when(idempotencyRepository.find(511L)).thenReturn(Mono.empty());
        when(idempotencyRepository.tryClaim(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(true));
        when(brokerExecutionClient.executeOrder(any())).thenReturn(Mono.just(BrokerOrderResponse.builder()
                .success(false).status("REJECTED").message("Insufficient funds").build()));
        when(idempotencyRepository.updateRejected(511L, null, "Insufficient funds")).thenReturn(Mono.empty());

        var response = service.executeTrade(liveEntryRequest(511L)).block();

        assertNotNull(response);
        assertEquals("REJECTED", response.getStatus());
        verify(idempotencyRepository).updateRejected(511L, null, "Insufficient funds");
        verify(idempotencyRepository, never()).updateStatus(any(), eq("UNKNOWN"), any(), any(), any(), any());
        verify(brokerExecutionClient, times(1)).findOrderByTag("AI-511");
    }

    @Test
    void entryWithStopLossOnTheWrongSideMustBeRejected() {
        executionReadiness.markReady();
        TradeExecutionRequest request = validEntryRequest(513L);
        request.setStopLoss(new BigDecimal("101.00"));

        var response = service.executeTrade(request).block();

        assertNotNull(response);
        assertEquals("VALIDATION_FAILED", response.getStatus());
        verifyNoInteractions(brokerExecutionClient, idempotencyRepository, executedTradeRepository);
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

    private TradeExecutionRequest liveEntryRequest(Long signalId) {
        TradeExecutionRequest request = validEntryRequest(signalId);
        request.setRequiredMargin(new BigDecimal("9000"));
        return request;
    }

}
