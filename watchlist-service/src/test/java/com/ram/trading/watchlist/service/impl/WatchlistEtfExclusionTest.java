package com.ram.trading.watchlist.service.impl;

import com.ram.trading.watchlist.client.NewsAnalysisClient;
import com.ram.trading.watchlist.client.SignalEngineClient;
import com.ram.trading.watchlist.client.StockServiceClient;
import com.ram.trading.watchlist.dto.TradableInstrumentResponse;
import com.ram.trading.watchlist.repo.WatchlistStockRepository;
import com.ram.trading.watchlist.service.TechnicalScoringService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class WatchlistEtfExclusionTest {

    private final WatchlistServiceImpl service = new WatchlistServiceImpl(
            mock(StockServiceClient.class),
            mock(SignalEngineClient.class),
            mock(TechnicalScoringService.class),
            mock(WatchlistStockRepository.class),
            mock(NewsAnalysisClient.class));

    @ParameterizedTest
    @CsvSource({
            // ETFs / liquid funds seen in the 2026-10-26 watchlist
            "LIQUIDCASE, ZERODHAMF - ZERODHA NIFTY 1D RATE LIQUID ETF, true",
            "LIQUIDBEES, NIP IND ETF LIQUID BEES, true",
            "ITBEES, NIPPON INDIA ETF NIFTY IT, true",
            "MON100, MOTILAL OSWAL NASDAQ 100 ETF, true",
            "HDFCLIQUID, HDFC MUTUAL FUND - HDFC NIFTY 1D RATE LIQUID ETF, true",
            // Stocks that must stay tradeable
            "NBCC, NBCC (INDIA) LIMITED, false",
            "GOLDIAM, GOLDIAM INTERNATIONAL LIMITED, false",
            "MFSL, MAX FINANCIAL SERVICES LIMITED, false",
            "TATAPOWER, TATA POWER CO LTD, false"
    })
    void excludesEtfsAndLiquidFundsOnly(String symbol, String name, boolean excluded) {
        TradableInstrumentResponse instrument = new TradableInstrumentResponse();
        instrument.setTradingSymbol(symbol);
        instrument.setCompanyName(name);

        assertEquals(excluded, service.isExcludedInstrument(instrument), symbol);
    }
}
