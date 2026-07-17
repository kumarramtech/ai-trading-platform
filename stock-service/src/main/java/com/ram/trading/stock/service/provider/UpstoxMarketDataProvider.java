package com.ram.trading.stock.service.provider;

import com.ram.trading.stock.client.MarketStreamClient;
import com.ram.trading.stock.dto.LivePrice;
import com.ram.trading.stock.dto.StockResponse;
import com.ram.trading.stock.service.MarketDataProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class UpstoxMarketDataProvider implements MarketDataProvider {

    private final MarketStreamClient marketStreamClient;

    @Override
    public Mono<StockResponse> getPrice(String symbol) {

        return marketStreamClient
                .getLivePrice(symbol)
                .map(this::toStockResponse);

    }

    private StockResponse toStockResponse(LivePrice livePrice) {

        return StockResponse.builder()
                .symbol(livePrice.getSymbol())
                .price(livePrice.getPrice())
                .build();

    }

    @Override
    public List<StockResponse> getAllStocks() {

        return List.of();

    }

}