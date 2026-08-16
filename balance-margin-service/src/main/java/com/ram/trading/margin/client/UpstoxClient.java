package com.ram.trading.margin.client;

import com.ram.trading.margin.dto.UpstoxFundsResponse;
import com.ram.trading.margin.dto.UpstoxMarginRequest;
import com.ram.trading.margin.dto.UpstoxMarginResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class UpstoxClient {

    private final WebClient webClient;
    private final BrokerClient brokerClient;

    @Value("${upstox.base-url}")
    private String upstoxBaseUrl;

    public Mono<UpstoxFundsResponse> getFundsAndMargin() {

        return brokerClient.getAccessToken()
                .flatMap(accessToken ->
                        webClient.get()
                                .uri(upstoxBaseUrl + "/v3/user/get-funds-and-margin")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + accessToken)
                                .header(
                                        HttpHeaders.ACCEPT,
                                        "application/json")
                                .header(
                                        "Api-Version",
                                        "3.0")
                                .retrieve()
                                .bodyToMono(UpstoxFundsResponse.class)
                );
    }

    public Mono<UpstoxMarginResponse> calculateMargin(
            UpstoxMarginRequest request) {

        return brokerClient.getAccessToken()
                .flatMap(accessToken ->
                        webClient.post()
                                .uri(upstoxBaseUrl + "/v2/charges/margin")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + accessToken)
                                .header(
                                        HttpHeaders.ACCEPT,
                                        "application/json")
                                .header(
                                        HttpHeaders.CONTENT_TYPE,
                                        "application/json")
                                .bodyValue(request)
                                .retrieve()
                                .bodyToMono(UpstoxMarginResponse.class)
                );
    }
}