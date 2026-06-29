package com.ram.trading.stock.service;

import com.ram.trading.stock.dto.StockResponse;
import com.ram.trading.stock.service.factory.MarketDataProviderFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StockService {

    private final MarketDataProviderFactory factory;

    public Mono<StockResponse> getPrice(String symbol) {
        return factory.getProvider().getPrice(symbol);
    }

    public List<StockResponse> getAllStocks() {
        return factory.getProvider().getAllStocks();
    }
}
