package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.MarginCalculationResponse;
import com.ram.trading.signal.engine.dto.ReleaseMarginRequest;
import com.ram.trading.signal.engine.dto.ReserveMarginRequest;
import com.ram.trading.signal.engine.dto.UpstoxMarginRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class BalanceMarginClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${balance.margin.service.url}")
    private String balanceMarginServiceUrl;

    public Mono<MarginCalculationResponse> calculateMargin(
            UpstoxMarginRequest request) {

        return webClientBuilder.build()
                .post()
                .uri(
                        balanceMarginServiceUrl
                                + "/api/v1/balance/calculate-margin")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(MarginCalculationResponse.class);
    }

    public Mono<Void> reserveMargin(
            Double requiredMargin) {

        return webClientBuilder.build()
                .post()
                .uri(
                        balanceMarginServiceUrl
                                + "/api/v1/balance/reserve-margin")
                .bodyValue(
                        new ReserveMarginRequest(
                                requiredMargin))
                .retrieve()
                .bodyToMono(Void.class);
    }

    public Mono<Void> releaseMargin(
            Double requiredMargin,
            Double profitLoss) {

        return webClientBuilder.build()
                .post()
                .uri(
                        balanceMarginServiceUrl
                                + "/api/v1/balance/release-margin")
                .bodyValue(
                        new ReleaseMarginRequest(
                                requiredMargin,
                                profitLoss))
                .retrieve()
                .bodyToMono(Void.class);
    }
}