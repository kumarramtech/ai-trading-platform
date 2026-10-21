package com.ram.trading.signal.engine.risk;

import com.ram.trading.signal.engine.client.BalanceMarginClient;
import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.dto.TradingSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
}
