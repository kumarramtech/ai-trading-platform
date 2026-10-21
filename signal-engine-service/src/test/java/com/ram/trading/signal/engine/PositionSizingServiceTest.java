package com.ram.trading.signal.engine;

import com.ram.trading.signal.engine.client.BalanceMarginClient;
import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.InstrumentResponse;
import com.ram.trading.signal.engine.dto.MarginCalculationResponse;
import com.ram.trading.signal.engine.risk.PositionSizingResult;
import com.ram.trading.signal.engine.risk.PositionSizingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
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
        // The service is instantiated directly in unit tests, so Spring @Value fields are not injected.
        ReflectionTestUtils.setField(service, "targetMarginPerTrade", 10_000.0);
        ReflectionTestUtils.setField(service, "maxLossPerTrade", 250.0);
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
    void rejectsWhenBrokerReportsInsufficientAvailableMargin() {
        TradingSignal signal = TradingSignal.builder()
                .symbol("TCS").signal("BUY").entryPrice(100.0).stopLoss(99.0).build();
        when(stockServiceClient.getInstrument("TCS"))
                .thenReturn(Mono.just(InstrumentResponse.builder()
                        .instrumentKey("NSE_EQ|TEST").tradingSymbol("TCS").build()));
        when(balanceMarginClient.calculateMargin(any()))
                .thenReturn(Mono.just(MarginCalculationResponse.builder()
                        .requiredMargin(10_000.0)
                        .sufficientBalance(false)
                        .maximumQuantity(100)
                        .tradeValue(20_000.0)
                        .leverage(2.0)
                        .build()));

        PositionSizingResult result = service.calculate(signal).block();

        assertNotNull(result);
        assertFalse(result.isValid());
        assertEquals("Insufficient available margin", result.getRejectionReason());
        verify(balanceMarginClient, atLeastOnce()).calculateMargin(any());
    }

    @Test
    void rejectsWhenBrokerMarginResponseIsEmpty() {
        TradingSignal signal = TradingSignal.builder()
                .symbol("TCS").signal("BUY").entryPrice(100.0).stopLoss(99.0).build();
        when(stockServiceClient.getInstrument("TCS"))
                .thenReturn(Mono.just(InstrumentResponse.builder()
                        .instrumentKey("NSE_EQ|TEST").tradingSymbol("TCS").build()));
        when(balanceMarginClient.calculateMargin(any())).thenReturn(Mono.empty());

        PositionSizingResult result = service.calculate(signal).block();

        assertNotNull(result);
        assertFalse(result.isValid());
        assertEquals("Broker margin unavailable", result.getRejectionReason());
    }
}
