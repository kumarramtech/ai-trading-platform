package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.client.interfac.OpenPositionContextClient;
import com.ram.trading.signal.engine.dto.ai.portfolio.OpenPositionContextResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenPositionContextClientImpl implements OpenPositionContextClient {

    private final WebClient.Builder webClientBuilder;

    @Override
    public Mono<OpenPositionContextResponse> getOpenPositionContext(String symbol) {

        log.info("Fetching Open Position Context for {}", symbol);

        return webClientBuilder
                .build()
                .get()
                .uri("http://localhost:8082/paper-trades/open-position/{symbol}", symbol)
                .retrieve()
                .bodyToMono(OpenPositionContextResponse.class)
                .doOnSuccess(response ->
                        log.info("Open Position Context received for {}", symbol));
    }
}