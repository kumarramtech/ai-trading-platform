package com.ram.trading.stock.service.provider;

import com.ram.trading.stock.client.UpstoxMarketClient;
import com.ram.trading.stock.dto.QuoteData;
import com.ram.trading.stock.dto.StockResponse;
import com.ram.trading.stock.service.instument.InstrumentMappingService;
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

    private final UpstoxMarketClient client;

    private final InstrumentMappingService instrumentMappingService;
    @Override
    public Mono<StockResponse> getPrice(String symbol) {

        String instrument = instrumentMappingService.getInstrumentKey(symbol);

        return client.getQuote(instrument)

                .map(response -> {


                    QuoteData quote =  response.getData()
                            .values()
                            .stream()
                            .findFirst()
                            .orElseThrow(() ->
                                    new RuntimeException("No quote returned"));

                    if (quote == null) {
                        throw new RuntimeException(
                                "Quote not found. Available Keys : "
                                        + response.getData().keySet());
                    }

                    return StockResponse.builder()
                            .symbol(symbol)
                            .price(quote.getLastPrice())
                            .build();
                });
    }

    @Override
    public List<StockResponse> getAllStocks() {
        return List.of();
    }
}