package com.ram.trading.watchlist.client;

import com.ram.trading.watchlist.dto.TechnicalIndicatorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SignalEngineClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${signal.engine.base-url}")
    private String signalEngineBaseUrl;

    public Flux<TechnicalIndicatorResponse> getTechnicalIndicators(
            List<String> symbols) {

        return webClientBuilder
                .baseUrl(signalEngineBaseUrl)
                .build()
                .post()
                .uri("/api/v1/indicator/bulk")
                .bodyValue(symbols)
                .retrieve()
                .bodyToFlux(TechnicalIndicatorResponse.class);
    }
}