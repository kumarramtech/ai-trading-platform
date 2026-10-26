package com.ram.trading.signal.engine.service.performance;

import com.ram.trading.signal.engine.service.performance.StrategyPerformanceService.GroupStats;
import com.ram.trading.signal.engine.service.performance.StrategyPerformanceService.TimeBucket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trades only setups that have earned it. Each strategy is judged per entry
 * time of day on its own closed trades, net of charges: once a strategy and
 * time bucket has enough trades and still loses money on average, new setups
 * there are skipped before the AI call. Buckets without enough history stay
 * open, so every setup keeps being sampled until its numbers mean something.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SetupEdgeGate {

    private static final List<String> STRATEGIES = List.of("JUDAS", "ORB", "MIDDAY");

    private static final long CACHE_MINUTES = 5;

    private final StrategyPerformanceService performanceService;

    @Value("${trading.edge-gate.enabled:true}")
    private boolean enabled = true;

    @Value("${trading.edge-gate.lookback-days:30}")
    private int lookbackDays = 30;

    /** A bucket must earn more than this per trade, net of charges (Rs), to stay open. */
    @Value("${trading.edge-gate.min-expectancy:0}")
    private double minExpectancy = 0.0;

    private final Map<String, CachedDecision> cache = new ConcurrentHashMap<>();

    public EdgeDecision evaluate(String strategy, LocalTime entryTime) {

        TimeBucket bucket = TimeBucket.of(entryTime);
        String name = strategy == null || strategy.isBlank()
                ? StrategyPerformanceService.UNKNOWN_STRATEGY
                : strategy.trim().toUpperCase();

        if (!enabled) {
            return new EdgeDecision(name, bucket.label(), true, 0, 0.0, null,
                    StrategyPerformanceService.Verdict.INSUFFICIENT_DATA, "Edge gate disabled");
        }

        String key = name + "|" + bucket;
        CachedDecision cached = cache.get(key);
        if (cached != null && cached.computedAt().isAfter(LocalDateTime.now().minusMinutes(CACHE_MINUTES))) {
            return cached.decision();
        }

        EdgeDecision decision = decide(name, bucket);
        cache.put(key, new CachedDecision(decision, LocalDateTime.now()));
        return decision;
    }

    public List<EdgeDecision> currentDecisions() {
        List<EdgeDecision> decisions = new ArrayList<>();
        for (String strategy : STRATEGIES) {
            for (TimeBucket bucket : TimeBucket.values()) {
                if (bucket != TimeBucket.OTHER) {
                    decisions.add(decide(strategy, bucket));
                }
            }
        }
        return decisions;
    }

    private EdgeDecision decide(String strategy, TimeBucket bucket) {

        GroupStats stats = performanceService.setupStats(strategy, bucket, lookbackDays);
        int minTrades = performanceService.minTrades();

        if (stats.trades() < minTrades) {
            return new EdgeDecision(strategy, bucket.label(), true, stats.trades(),
                    stats.expectancyPerTrade(), stats.averageR(), stats.verdict(),
                    "Sampling: " + stats.trades() + "/" + minTrades + " trades in " + lookbackDays + " days");
        }

        boolean allowed = stats.expectancyPerTrade() > minExpectancy;

        return new EdgeDecision(strategy, bucket.label(), allowed, stats.trades(),
                stats.expectancyPerTrade(), stats.averageR(), stats.verdict(),
                (allowed ? "Proven edge: " : "No edge: ")
                        + "net Rs " + stats.expectancyPerTrade() + " per trade over "
                        + stats.trades() + " trades, win rate " + stats.winRatePct() + "%");
    }

    private record CachedDecision(EdgeDecision decision, LocalDateTime computedAt) {
    }

    public record EdgeDecision(
            String strategy,
            String entryTime,
            boolean allowed,
            int trades,
            double expectancyPerTrade,
            Double averageR,
            StrategyPerformanceService.Verdict verdict,
            String reason) {
    }
}
