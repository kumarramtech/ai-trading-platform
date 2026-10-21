package com.ram.trading.trade.execution.client;

import com.ram.trading.trade.execution.dto.BrokerOrderRequest;
import com.ram.trading.trade.execution.dto.BrokerOrderResponse;
import com.ram.trading.trade.execution.dto.UpstoxOrderHistoryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

import static org.mockito.Mockito.*;

class BrokerExecutionClientImplTest {

    private WebClient.Builder webClientBuilder;
    private BrokerClient brokerClient;
    private BrokerExecutionClientImpl client;

    @BeforeEach
    void setUp() {
        webClientBuilder = mock(WebClient.Builder.class);
        brokerClient = mock(BrokerClient.class);
        client = new BrokerExecutionClientImpl(webClientBuilder, brokerClient);
    }

    @Test
    void nullOrderRequestIsRejectedWithoutTokenOrHttpCall() {
        BrokerOrderResponse response = client.executeOrder(null).block();

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals("INVALID_REQUEST", response.getStatus());
        verifyNoInteractions(webClientBuilder, brokerClient);
    }

    @Test
    void matchingHistorySnapshotsSelectNewestTimestampRatherThanArrayOrder() {
        UpstoxOrderHistoryResponse.OrderData older = historyOrder(
                "AI-100", "order-100", "open", "2026-10-19T09:15:00+05:30", 0);
        UpstoxOrderHistoryResponse.OrderData newer = historyOrder(
                "AI-100", "order-100", "complete", "2026-10-19T09:15:02+05:30", 2);

        UpstoxOrderHistoryResponse.OrderData selected =
                BrokerExecutionClientImpl.selectMatchingOrder(List.of(newer, older), "AI-100");

        assertNotNull(selected);
        assertEquals("complete", selected.getStatus());
        assertEquals(2, selected.getFilledQuantity());
    }

    @Test
    void matchingTagAcrossDifferentOrderIdsMustFailClosed() {
        UpstoxOrderHistoryResponse.OrderData first = historyOrder(
                "AI-101", "order-101-a", "open", "2026-10-19T09:15:00+05:30", 0);
        UpstoxOrderHistoryResponse.OrderData second = historyOrder(
                "AI-101", "order-101-b", "complete", "2026-10-19T09:15:02+05:30", 2);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> BrokerExecutionClientImpl.selectMatchingOrder(
                        List.of(first, second), "AI-101"));
        assertTrue(error.getMessage().contains("Multiple broker orders"));
    }

    @Test
    void conflictingHistorySnapshotsWithoutTimestampsMustFailClosed() {
        UpstoxOrderHistoryResponse.OrderData first = historyOrder(
                "AI-102", "order-102", "open", null, 0);
        UpstoxOrderHistoryResponse.OrderData second = historyOrder(
                "AI-102", "order-102", "complete", null, 2);

        assertThrows(IllegalStateException.class,
                () -> BrokerExecutionClientImpl.selectMatchingOrder(
                        List.of(first, second), "AI-102"));
    }

    @Test
    void absentMatchingHistoryReturnsNull() {
        assertNull(BrokerExecutionClientImpl.selectMatchingOrder(List.of(), "AI-103"));
    }

    private UpstoxOrderHistoryResponse.OrderData historyOrder(
            String tag, String orderId, String status, String exchangeTimestamp, int filledQuantity) {
        UpstoxOrderHistoryResponse.OrderData order = new UpstoxOrderHistoryResponse.OrderData();
        order.setTag(tag);
        order.setOrderId(orderId);
        order.setStatus(status);
        order.setExchangeTimestamp(exchangeTimestamp);
        order.setFilledQuantity(filledQuantity);
        order.setPendingQuantity(Math.max(0, 2 - filledQuantity));
        order.setAveragePrice(filledQuantity > 0 ? new java.math.BigDecimal("100.00") : null);
        return order;
    }

    @Test
    void invalidQuantityIsRejectedBeforeTokenOrHttpCall() {
        BrokerOrderRequest request = BrokerOrderRequest.builder()
                .symbol("TCS")
                .instrumentKey("NSE_EQ|TEST")
                .signal("BUY")
                .transactionType("BUY")
                .quantity(0)
                .price(new java.math.BigDecimal("100.00"))
                .orderType("MARKET")
                .tag("AI-999")
                .build();

        BrokerOrderResponse response = client.executeOrder(request).block();

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals("INVALID_REQUEST", response.getStatus());
        verifyNoInteractions(webClientBuilder, brokerClient);
    }
}
