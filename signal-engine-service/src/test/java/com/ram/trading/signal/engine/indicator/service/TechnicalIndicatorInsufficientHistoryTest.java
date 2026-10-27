package com.ram.trading.signal.engine.indicator.service;

import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.dto.history.HistoricalPriceResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TechnicalIndicatorInsufficientHistoryTest {

    private final StockServiceClient stockServiceClient = mock(StockServiceClient.class);
    private final TechnicalIndicatorServiceImpl service =
            new TechnicalIndicatorServiceImpl(stockServiceClient, mock(IndicatorService.class));

    @Test
    void symbolWithTooFewCandlesIsNotFetchedAgain() {
        when(stockServiceClient.getHistoricalPrices("NEWLIST")).thenReturn(Flux.fromIterable(
                Collections.nCopies(15, HistoricalPriceResponse.builder().build())));

        assertNull(service.calculate("NEWLIST").block());
        assertTrue(service.hasInsufficientHistory("NEWLIST"));

        assertNull(service.calculate("NEWLIST").block());
        verify(stockServiceClient, times(1)).getHistoricalPrices("NEWLIST");
    }

    @Test
    void symbolWithNoCandlesIsMarkedToo() {
        when(stockServiceClient.getHistoricalPrices("SUDARSHAN")).thenReturn(Flux.empty());

        assertNull(service.calculate("SUDARSHAN").block());
        assertTrue(service.hasInsufficientHistory("SUDARSHAN"));
        assertFalse(service.hasInsufficientHistory("TCS"));
    }
}
