package com.ram.trading.stock.service.provider;

import com.ram.trading.stock.dto.StockResponse;
import com.ram.trading.stock.service.MarketDataProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
public class MockMarketDataProvider
        implements MarketDataProvider {

    private static final Map<String, Double> STOCK_PRICES =
            Map.of(
                    "TCS", 4120.5,
                    "HDFC", 1750.0,
                    "INFY", 1600.0
            );

    @Override
    public Mono<StockResponse> getPrice(String symbol) {

        double price = STOCK_PRICES.getOrDefault(symbol.toUpperCase(), 1000.0);

        return Mono.just(
                StockResponse.builder()
                        .symbol(symbol)
                        .price(price)
                        .build());
    }

    @Override
    public List<StockResponse> getAllStocks() {

        return List.of(
                StockResponse.builder()
                        .symbol("TCS")
                        .price(4120.50)
                        .build(),

                StockResponse.builder()
                        .symbol("INFY")
                        .price(1625.30)
                        .build(),

                StockResponse.builder()
                        .symbol("WIPRO")
                        .price(275.10)
                        .build());
    }
}