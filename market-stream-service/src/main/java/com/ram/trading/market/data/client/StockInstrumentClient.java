package com.ram.trading.market.data.client;

import com.ram.trading.market.data.dto.InstrumentLookupResponse;
import com.ram.trading.market.data.provider.dto.InstrumentSubscriptionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StockInstrumentClient {

    private final WebClient.Builder builder;

    @Value("${stock.service.url}")
    private String stockUrl;

    public List<InstrumentSubscriptionResponse> loadSubscriptions() {

        return builder
                .baseUrl(stockUrl)
                .build()
                .get()
                .uri("/api/v1/instruments/subscriptions")
                .retrieve()
                .bodyToFlux(InstrumentSubscriptionResponse.class)
                .collectList()
                .block();
    }
}