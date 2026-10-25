package com.ram.trading.signal.engine.risk;

import com.ram.trading.signal.engine.client.BalanceMarginClient;
import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.dto.TradingSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PositionSizingServiceTest {

    private BalanceMarginClient balanceMarginClient;
    private StockServiceClient stockServiceClient;
    private PositionSizingService service;

    @BeforeEach
    void setUp() {
        balanceMarginClient = mock(BalanceMarginClient.class);
        stockServiceClient = mock(StockServiceClient.class);
        service = new PositionSizingService(balanceMarginClient, stockServiceClient);
    }

    @Test
    void rejectsNonPositiveEntryPriceWithoutCallingExternalServices() {
        TradingSignal signal = TradingSignal.builder()
                .symbol("TCS").signal("BUY").entryPrice(0.0).stopLoss(99.0).build();

        PositionSizingResult result = service.calculate(signal).block();

        assertNotNull(result);
        assertFalse(result.isValid());
        assertEquals("Invalid entry price or stop loss", result.getRejectionReason());
        verifyNoInteractions(stockServiceClient, balanceMarginClient);
    }

    @Test
    void rejectsNonPositiveStopLossWithoutCallingExternalServices() {
        TradingSignal signal = TradingSignal.builder()
                .symbol("TCS").signal("BUY").entryPrice(100.0).stopLoss(0.0).build();

        PositionSizingResult result = service.calculate(signal).block();

        assertNotNull(result);
        assertFalse(result.isValid());
        assertEquals("Invalid entry price or stop loss", result.getRejectionReason());
        verifyNoInteractions(stockServiceClient, balanceMarginClient);
    }

    @Test
    void rejectsZeroRiskPerShareWithoutCallingExternalServices() {
        TradingSignal signal = TradingSignal.builder()
                .symbol("TCS").signal("BUY").entryPrice(100.0).stopLoss(100.0).build();

        PositionSizingResult result = service.calculate(signal).block();

        assertNotNull(result);
        assertFalse(result.isValid());
        assertEquals("Risk per share must be greater than zero", result.getRejectionReason());
        verifyNoInteractions(stockServiceClient, balanceMarginClient);
    }

    @Test
    void rejectsWhenInstrumentKeyIsMissingWithoutCallingMarginService() {
        TradingSignal signal = TradingSignal.builder()
                .symbol("TCS").signal("BUY").entryPrice(100.0).stopLoss(99.0).build();
        when(stockServiceClient.getInstrument("TCS")).thenReturn(Mono.empty());

        PositionSizingResult result = service.calculate(signal).block();

        assertNotNull(result);
        assertFalse(result.isValid());
        assertEquals("Instrument key not found", result.getRejectionReason());
        verify(stockServiceClient).getInstrument("TCS");
        verifyNoInteractions(balanceMarginClient);
    }

    @Test
    void quantityIsCappedSoTheLossAtTheStopStaysWithinRiskPerTrade() {
        // 5x leverage: Rs 200 margin per share at Rs 1000 -> margin sizing alone gives 50 shares.
        // A Rs 10 stop on 50 shares risks Rs 500; with a Rs 200 budget the cap is 20 shares.
        stubMarginAt(200.0);
        TradingSignal signal = TradingSignal.builder()
                .symbol("TCS").signal("BUY").entryPrice(1000.0).stopLoss(990.0).build();

        PositionSizingResult result = service.calculate(signal).block();

        assertNotNull(result);
        assertTrue(result.isValid(), result.getRejectionReason());
        assertEquals(20, result.getMarginBasedQuantity());
        assertEquals(200.0, result.getExpectedRisk(), 0.001);
        assertEquals(4000.0, result.getRequiredMargin(), 0.001,
                "A risk-capped trade uses less than the margin allocation and is still accepted");
    }

    @Test
    void tightStopKeepsTheFullMarginAllocation() {
        stubMarginAt(200.0);
        TradingSignal signal = TradingSignal.builder()
                .symbol("TCS").signal("BUY").entryPrice(1000.0).stopLoss(996.0).build();

        PositionSizingResult result = service.calculate(signal).block();

        assertNotNull(result);
        assertTrue(result.isValid(), result.getRejectionReason());
        assertEquals(50, result.getMarginBasedQuantity());
        assertEquals(200.0, result.getExpectedRisk(), 0.001);
    }

    @Test
    void stopWiderThanTheWholeRiskBudgetIsRejected() {
        stubMarginAt(200.0);
        TradingSignal signal = TradingSignal.builder()
                .symbol("TCS").signal("BUY").entryPrice(1000.0).stopLoss(700.0).build();

        PositionSizingResult result = service.calculate(signal).block();

        assertNotNull(result);
        assertFalse(result.isValid());
        assertTrue(result.getRejectionReason().contains("risk budget"), result.getRejectionReason());
    }

    private void stubMarginAt(double marginPerShare) {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "targetMarginPerTrade", 10000.0);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "riskPerTrade", 200.0);
        when(stockServiceClient.getInstrument("TCS")).thenReturn(Mono.just(
                com.ram.trading.signal.engine.dto.InstrumentResponse.builder()
                        .tradingSymbol("TCS").instrumentKey("NSE_EQ|TCS").build()));
        when(balanceMarginClient.calculateMargin(any())).thenAnswer(invocation -> {
            com.ram.trading.signal.engine.dto.UpstoxMarginRequest request = invocation.getArgument(0);
            int quantity = request.getInstruments().get(0).getQuantity();
            return Mono.just(com.ram.trading.signal.engine.dto.MarginCalculationResponse.builder()
                    .quantity(quantity)
                    .requiredMargin(quantity * marginPerShare)
                    .tradeValue(quantity * 1000.0)
                    .leverage(5.0)
                    .sufficientBalance(true)
                    .maximumQuantity(1000)
                    .availableBalance(20000.0)
                    .build());
        });
    }
}
