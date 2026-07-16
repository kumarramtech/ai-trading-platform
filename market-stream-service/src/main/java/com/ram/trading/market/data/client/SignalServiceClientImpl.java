package com.ram.trading.market.data.client;


import com.ram.trading.market.data.dto.Tick;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@RequiredArgsConstructor
@Slf4j
public class SignalServiceClientImpl implements SignalServiceClient {

    private final WebClient webClient;

    @Value("${signal.service.base-url}")
    private String signalServiceUrl;

    @Override
    public void publishTick(Tick tick) {

        webClient.post()
                .uri(signalServiceUrl + "/api/v1/signals/live")
                .bodyValue(tick)
                .retrieve()
                .bodyToMono(Void.class)
                .doOnSuccess(response ->
                        log.info("Tick sent to Signal Service : {}",
                                tick.getSymbol()))
                .doOnError(error ->
                        log.error("Unable to send Tick", error))
                .subscribe();
    }
}