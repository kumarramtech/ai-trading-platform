package com.ram.trading.market.data.client;

import com.ram.trading.market.data.dto.Tick;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Delivers market ticks to Signal Service with explicit backpressure.
 *
 * IMPORTANT:
 * We must not create one unbounded WebClient subscription per market tick.
 * Upstox can produce a much higher tick rate than Signal Service can safely
 * process. The bounded sink protects the Market Stream process from an
 * unbounded Reactor connection-acquire queue.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalServiceClientImpl implements SignalServiceClient {

    private final WebClient webClient;

    @Value("${signal.service.base-url}")
    private String signalServiceUrl;

    @Value("${signal.tick.queue.capacity:10000}")
    private int queueCapacity;

    @Value("${signal.tick.dispatch.concurrency:25}")
    private int dispatchConcurrency;

    @Value("${signal.tick.http.timeout-seconds:5}")
    private long httpTimeoutSeconds;

    private final AtomicLong lastErrorLogTime = new AtomicLong(0L);
    private final AtomicLong lastOverflowLogTime = new AtomicLong(0L);

    private static final long ERROR_LOG_COOLDOWN_MS = 30_000L;

    private Sinks.Many<Tick> tickSink;
    private Disposable dispatcher;

    @PostConstruct
    void startDispatcher() {

        int capacity = Math.max(1000, queueCapacity);
        int concurrency = Math.max(1, dispatchConcurrency);

        tickSink = Sinks.many()
                .unicast()
                .onBackpressureBuffer(
                        new ArrayBlockingQueue<>(capacity));

        dispatcher = tickSink
                .asFlux()
                .flatMap(
                        this::sendTick,
                        concurrency)
                .doOnError(error ->
                        log.error(
                                "Signal tick dispatcher stopped unexpectedly",
                                error))
                .retryWhen(
                        Retry.backoff(
                                        Long.MAX_VALUE,
                                        Duration.ofSeconds(1))
                                .maxBackoff(Duration.ofSeconds(10)))
                .subscribe();

        log.info(
                "Signal tick dispatcher started | queueCapacity={} | concurrency={}",
                capacity,
                concurrency);
    }

    @Override
    public void publishTick(Tick tick) {

        if (tick == null) {
            return;
        }

        if (tickSink == null) {
            log.warn(
                    "Signal tick dispatcher not ready; dropping tick | Symbol={}",
                    tick.getSymbol());
            return;
        }

        Sinks.EmitResult result = tickSink.tryEmitNext(tick);

        if (result.isFailure()) {
            logQueueOverflow(tick, result);
        }
    }

    private Mono<Void> sendTick(Tick tick) {

        return webClient
                .post()
                .uri(signalServiceUrl + "/api/v1/signals/live")
                .bodyValue(tick)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(httpTimeoutSeconds))
                .doOnSuccess(response ->
                        log.debug(
                                "Tick sent to Signal Service : {}",
                                tick.getSymbol()))
                .doOnError(error ->
                        logSignalPublishError(tick, error))
                .onErrorResume(error -> Mono.empty());
    }

    private void logSignalPublishError(
            Tick tick,
            Throwable error) {

        long now = System.currentTimeMillis();
        long last = lastErrorLogTime.get();

        if (now - last >= ERROR_LOG_COOLDOWN_MS
                && lastErrorLogTime.compareAndSet(last, now)) {

            log.warn(
                    "Signal Service tick delivery failed | Symbol={} | Error={}",
                    tick != null ? tick.getSymbol() : "UNKNOWN",
                    error.getMessage());
        }
    }

    private void logQueueOverflow(
            Tick tick,
            Sinks.EmitResult result) {

        long now = System.currentTimeMillis();
        long last = lastOverflowLogTime.get();

        if (now - last >= ERROR_LOG_COOLDOWN_MS
                && lastOverflowLogTime.compareAndSet(last, now)) {

            log.error(
                    "SIGNAL TICK QUEUE SATURATED | " +
                            "Symbol={} | EmitResult={} | " +
                            "Ticks are being rejected because Signal Service is not consuming fast enough",
                    tick != null ? tick.getSymbol() : "UNKNOWN",
                    result);
        }
    }

    @PreDestroy
    void stopDispatcher() {

        if (dispatcher != null) {
            dispatcher.dispose();
        }

        if (tickSink != null) {
            tickSink.tryEmitComplete();
        }

        log.info("Signal tick dispatcher stopped");
    }
}
