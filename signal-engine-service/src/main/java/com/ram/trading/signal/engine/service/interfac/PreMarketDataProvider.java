package com.ram.trading.signal.engine.service.interfac;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Mono;

import java.util.List;

public interface PreMarketDataProvider {

    /**
     * Returns the universe of stocks for pre-market analysis.
     *
     * The implementation must provide actual market data.
     *
     * No synthetic/fabricated prices should be returned.
     */
    Mono<List<PreMarketQuote>> getCandidates();


    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class PreMarketQuote {

        private String tradingSymbol;

        private Double previousClose;

        private Double preMarketPrice;

        private Long preMarketVolume;

        private Long previousVolume;

        private boolean reliable;
    }
}