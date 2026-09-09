package com.ram.trading.market.data.client;


import com.ram.trading.market.data.dto.Tick;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class SignalServiceClientImpl implements SignalServiceClient {

    private final WebClient webClient;

    @Value("${signal.service.base-url}")
    private String signalServiceUrl;

    private final AtomicLong lastErrorLogTime = new AtomicLong(0L);
    private static final long ERROR_LOG_COOLDOWN_MS = 30_000L;

    @Override
    public void publishTick(Tick tick) {

        webClient.post()
                .uri(signalServiceUrl + "/api/v1/signals/live")
                .bodyValue(tick)
                .retrieve()
                .bodyToMono(Void.class)
                .doOnSuccess(response ->
                        log.debug("Tick sent to Signal Service : {}",
                                tick.getSymbol()))
                .doOnError(error -> logSignalPublishError(tick, error))
                .subscribe();
    }

    private void logSignalPublishError(Tick tick, Throwable error) {

        long now = System.currentTimeMillis();
        long last = lastErrorLogTime.get();

        if (now - last >= ERROR_LOG_COOLDOWN_MS
                && lastErrorLogTime.compareAndSet(last, now)) {
            log.warn(
                    "Signal Service unavailable; tick delivery is temporarily failing | Symbol={} | Error={}",
                    tick != null ? tick.getSymbol() : "UNKNOWN",
                    error.getMessage());
        }
    }
}