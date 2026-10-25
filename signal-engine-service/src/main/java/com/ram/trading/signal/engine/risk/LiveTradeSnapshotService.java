package com.ram.trading.signal.engine.risk;

import com.ram.trading.signal.engine.client.TradeExecutionClient;
import com.ram.trading.signal.engine.client.TradeExecutionClient.TradeExecutionPosition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps a recent copy of today's live positions owned by Trade Execution Service,
 * so the synchronous risk check can enforce daily limits in live mode without
 * blocking a reactive thread on an HTTP call. Live positions are never stored as
 * PaperTrade rows, so without this the risk limits would see no live trades.
 *
 * A snapshot older than the configured maximum age is treated as unavailable and
 * callers must fail closed (block new entries).
 */
@Component
@Slf4j
public class LiveTradeSnapshotService {

    public record Snapshot(List<TradeExecutionPosition> trades, Instant fetchedAt) {
    }

    private final TradeExecutionClient tradeExecutionClient;
    private final String executionTradeMode;
    private final Duration maxAge;
    private final Duration requestTimeout;
    private final Clock clock;

    private final AtomicReference<Snapshot> latest = new AtomicReference<>();
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    @Autowired
    public LiveTradeSnapshotService(
            TradeExecutionClient tradeExecutionClient,
            @Value("${execution.trade:paper}") String executionTradeMode,
            @Value("${trading.risk.live-snapshot.max-age-ms:30000}") long maxAgeMs,
            @Value("${trading.risk.live-snapshot.timeout-ms:5000}") long timeoutMs) {
        this(tradeExecutionClient, executionTradeMode, Duration.ofMillis(maxAgeMs),
                Duration.ofMillis(timeoutMs), Clock.systemUTC());
    }

    public LiveTradeSnapshotService(
            TradeExecutionClient tradeExecutionClient,
            String executionTradeMode,
            Duration maxAge,
            Duration requestTimeout,
            Clock clock) {
        this.tradeExecutionClient = tradeExecutionClient;
        this.executionTradeMode = executionTradeMode;
        this.maxAge = maxAge;
        this.requestTimeout = requestTimeout;
        this.clock = clock;
    }

    public boolean isLiveMode() {
        return executionTradeMode != null && "live".equalsIgnoreCase(executionTradeMode.trim());
    }

    @Scheduled(
            fixedDelayString = "${trading.risk.live-snapshot.refresh-ms:5000}",
            initialDelayString = "${trading.risk.live-snapshot.refresh-ms:5000}")
    public void refresh() {
        if (!isLiveMode() || !refreshing.compareAndSet(false, true)) {
            return;
        }
        tradeExecutionClient.getSessionTrades()
                .timeout(requestTimeout)
                .doOnNext(trades -> latest.set(new Snapshot(List.copyOf(trades), clock.instant())))
                .doOnError(error -> log.warn(
                        "LIVE TRADE SNAPSHOT REFRESH FAILED | Entries stay blocked once the last snapshot is older than {}s | Error={}",
                        maxAge.toSeconds(), error.toString()))
                .doFinally(signal -> refreshing.set(false))
                .subscribe(ignored -> { }, error -> { });
    }

    /** Today's live positions, or empty when no snapshot is recent enough to trust. */
    public Optional<List<TradeExecutionPosition>> freshTrades() {
        Snapshot snapshot = latest.get();
        if (snapshot == null
                || Duration.between(snapshot.fetchedAt(), clock.instant()).compareTo(maxAge) > 0) {
            return Optional.empty();
        }
        return Optional.of(snapshot.trades());
    }
}
