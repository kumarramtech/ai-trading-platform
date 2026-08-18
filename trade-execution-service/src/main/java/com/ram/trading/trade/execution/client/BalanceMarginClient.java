package com.ram.trading.trade.execution.client;

import com.ram.trading.trade.execution.dto.ReserveMarginRequest;
import com.ram.trading.trade.execution.dto.ReserveMarginResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class BalanceMarginClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${balance.margin.service.url}")
    private String balanceMarginServiceUrl;

    public Mono<ReserveMarginResponse> reserveMargin(
            ReserveMarginRequest request) {

        return webClientBuilder.build()
                .post()
                .uri(balanceMarginServiceUrl + "/api/v1/balance/reserve-margin")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ReserveMarginResponse.class);
    }
}