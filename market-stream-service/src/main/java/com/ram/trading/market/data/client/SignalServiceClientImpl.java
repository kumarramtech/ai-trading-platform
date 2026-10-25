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
    private final AtomicLong lastEmitFailureLogTime = new AtomicLong(0L);
    private static final long ERROR_LOG_COOLDOWN_MS = 30_000L;

    private final Object emissionLock = new Object();

    private volatile Sinks.Many<Tick> tickSink;
    private volatile Disposable dispatcher;

    @PostConstruct
    void startDispatcher() {
        int capacity = Math.max(1000, queueCapacity);
        int concurrency = Math.max(1, dispatchConcurrency);

        Sinks.Many<Tick> sink = Sinks.many()
                .unicast()
                .onBackpressureBuffer(new ArrayBlockingQueue<>(capacity));

        this.tickSink = sink;

        /*
         * Fixed lanes avoid creating an unbounded number of per-symbol groups.
         * A symbol always maps to the same lane, and concatMap serializes that
         * lane. Different lanes can dispatch concurrently. Ordering is by
         * successful publishTick emission order, not exchange timestamp.
         */
        this.dispatcher = sink.asFlux()
                .groupBy(tick -> dispatchLane(tick, concurrency))
                .flatMap(lane -> lane.concatMap(this::sendTick), concurrency)
                .doOnError(error ->
                        log.error("Signal tick dispatcher stopped unexpectedly", error))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(10)))
                .subscribe();

        log.info(
                "Signal tick dispatcher started | queueCapacity={} | concurrency={} | ordering=per-lane",
                capacity,
                concurrency);
    }

    private int dispatchLane(Tick tick, int laneCount) {
        String symbol = tick != null ? tick.getSymbol() : null;
        if (symbol == null || symbol.isBlank()) {
            return 0;
        }
        return Math.floorMod(symbol.trim().toUpperCase().hashCode(), laneCount);
    }

    @Override
    public void publishTick(Tick tick) {
        if (tick == null) {
            return;
        }

        if (tick.getSymbol() == null || tick.getSymbol().isBlank()) {
            logEmitFailure(tick, "INVALID_SYMBOL", "Tick symbol is null or blank");
            return;
        }

        Sinks.Many<Tick> sink = this.tickSink;
        if (sink == null) {
            logEmitFailure(
                    tick, "DISPATCHER_NOT_READY",
                    "Signal tick dispatcher is not initialized");
            return;
        }

        Sinks.EmitResult result;
        synchronized (emissionLock) {
            result = sink.tryEmitNext(tick);
        }

        if (result.isFailure()) {
            logEmitFailure(tick, result.name(), describeEmitFailure(result));
        }
    }

    private Mono<Void> sendTick(Tick tick) {
        return webClient
                .post()
                .uri(signalServiceUrl + "/api/v1/signals/live")
                .bodyValue(tick)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(Math.max(1L, httpTimeoutSeconds)))
                .doOnSuccess(ignored ->
                        log.debug("Tick sent to Signal Service | Symbol={}", tick.getSymbol()))
                .doOnError(error -> logSignalPublishError(tick, error))
                /*
                 * Do not automatically retry an individual request. A timeout
                 * can occur after the receiver has already processed the tick.
                 */
                .onErrorResume(error -> Mono.empty());
    }

    private String describeEmitFailure(Sinks.EmitResult result) {
        return switch (result) {
            case FAIL_NON_SERIALIZED ->
                    "Concurrent emission rejected; check producer serialization";
            case FAIL_OVERFLOW -> "Bounded tick buffer is full";
            case FAIL_ZERO_SUBSCRIBER -> "Tick dispatcher has no active subscriber";
            case FAIL_CANCELLED -> "Tick dispatcher subscription was cancelled";
            case FAIL_TERMINATED -> "Tick sink has already terminated";
            default -> "Tick emission failed";
        };
    }

    private void logEmitFailure(Tick tick, String result, String reason) {
        long now = System.currentTimeMillis();
        long last = lastEmitFailureLogTime.get();

        if (now - last >= ERROR_LOG_COOLDOWN_MS
                && lastEmitFailureLogTime.compareAndSet(last, now)) {
            log.error(
                    "SIGNAL TICK EMISSION FAILED | Symbol={} | Result={} | Reason={}",
                    tick != null ? tick.getSymbol() : "UNKNOWN",
                    result,
                    reason);
        }
    }

    private void logSignalPublishError(Tick tick, Throwable error) {
        long now = System.currentTimeMillis();
        long last = lastErrorLogTime.get();

        if (now - last >= ERROR_LOG_COOLDOWN_MS
                && lastErrorLogTime.compareAndSet(last, now)) {
            log.warn(
                    "Signal Service tick delivery failed | Symbol={} | Error={}",
                    tick != null ? tick.getSymbol() : "UNKNOWN",
                    error.getMessage(),
                    error);
        }
    }

    @PreDestroy
    void stopDispatcher() {
        Disposable activeDispatcher = this.dispatcher;
        Sinks.Many<Tick> sink = this.tickSink;

        if (sink != null) {
            synchronized (emissionLock) {
                sink.tryEmitComplete();
            }
        }

        /*
         * Immediate disposal can cancel in-flight work. This method does not
         * claim that all queued ticks were delivered before shutdown.
         */
        if (activeDispatcher != null) {
            activeDispatcher.dispose();
        }

        log.info("Signal tick dispatcher stopped");
    }
}
