package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.client.interfac.PortfolioClient;
import com.ram.trading.signal.engine.dto.portfolio.ClosePositionRequest;
import com.ram.trading.signal.engine.dto.portfolio.OpenPositionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioClientImpl implements PortfolioClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${portfolio.service.url}")
    private String portfolioUrl;

    @Override
    public Mono<Void> openPosition(
            String symbol,
            Integer quantity,
            Double entryPrice) {

        OpenPositionRequest request = OpenPositionRequest.builder()
                        .symbol(symbol)
                        .quantity(quantity)
                        .entryPrice(entryPrice)
                        .build();

        return webClientBuilder
                .baseUrl(portfolioUrl)
                .build()
                .post()
                .uri("/portfolio/open-position")
                .bodyValue(request)
                .retrieve()
                .toBodilessEntity()
                .then()
                .doOnSuccess(v ->
                        log.info(
                                "Portfolio updated for {}",
                                symbol))
                .doOnError(ex ->
                        log.error(
                                "Portfolio update failed",
                                ex));
    }

    @Override
    public Mono<Void> closePosition(
            String symbol,
            Integer quantity) {

        ClosePositionRequest request =
                ClosePositionRequest.builder()
                        .symbol(symbol)
                        .quantity(quantity)
                        .build();

        return webClientBuilder
                .baseUrl(portfolioUrl)
                .build()
                .post()
                .uri("/portfolio/close-position")
                .bodyValue(request)
                .retrieve()
                .toBodilessEntity()
                .then()
                .doOnSuccess(v ->
                        log.info(
                                "Portfolio closed for {}",
                                symbol))
                .doOnError(ex ->
                        log.error(
                                "Portfolio close failed",
                                ex));
    }
}