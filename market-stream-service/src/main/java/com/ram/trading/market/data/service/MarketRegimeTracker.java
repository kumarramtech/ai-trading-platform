package com.ram.trading.market.data.service;

import com.ram.trading.market.data.dto.MarketRegimeSnapshot;
import com.ram.trading.market.data.dto.Tick;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Maintains a feed-derived market context from NIFTY and BANK NIFTY ticks.
 * A directional regime is valid only while BOTH index values are present
 * and fresh. This service enriches ticks; it does not reject trades.
 */
@Service
@Slf4j
public class MarketRegimeTracker {

    private static final Set<String> NIFTY_SYMBOLS = Set.of(
            "NIFTY", "NIFTY50", "NIFTY 50",
            "NSE_INDEX|NIFTY 50", "NSE_INDEX|NIFTY50");

    private static final Set<String> BANK_NIFTY_SYMBOLS = Set.of(
            "BANKNIFTY", "BANK NIFTY", "NIFTYBANK", "NIFTY BANK",
            "NSE_INDEX|NIFTY BANK", "NSE_INDEX|BANKNIFTY");

    private final AtomicReference<Double> niftyChange = new AtomicReference<>();
    private final AtomicReference<Double> bankNiftyChange = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> niftyUpdatedAt = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> bankNiftyUpdatedAt = new AtomicReference<>();

    @Value("${market.regime.direction-threshold-percent:0.15}")
    private double directionThresholdPercent;

    @Value("${market.regime.max-age-seconds:60}")
    private long maxAgeSeconds;

    public void update(Tick tick) {
        if (tick == null || tick.getSymbol() == null) {
            return;
        }

        String symbol = normalize(tick.getSymbol());
        String instrumentKey = normalize(tick.getInstrumentKey());
        Double change = effectiveChange(tick);

        if (change == null) {
            return;
        }

        String oldRegime = currentRegime();
        LocalDateTime now = LocalDateTime.now();

        if (NIFTY_SYMBOLS.contains(symbol) || NIFTY_SYMBOLS.contains(instrumentKey)) {
            niftyChange.set(change);
            niftyUpdatedAt.set(now);
        } else if (BANK_NIFTY_SYMBOLS.contains(symbol)
                || BANK_NIFTY_SYMBOLS.contains(instrumentKey)) {
            bankNiftyChange.set(change);
            bankNiftyUpdatedAt.set(now);
        } else {
            return;
        }

        String newRegime = currentRegime();

        if (!newRegime.equals(oldRegime)) {
            log.info(
                    "MARKET REGIME CHANGED | Regime={} | NIFTY={}% | BANKNIFTY={}%",
                    newRegime,
                    freshChange(niftyChange, niftyUpdatedAt),
                    freshChange(bankNiftyChange, bankNiftyUpdatedAt));
        } else {
            log.debug(
                    "MARKET REGIME UPDATED | Regime={} | NIFTY={}% | BANKNIFTY={}%",
                    newRegime,
                    freshChange(niftyChange, niftyUpdatedAt),
                    freshChange(bankNiftyChange, bankNiftyUpdatedAt));
        }
    }

    public MarketRegimeSnapshot snapshot() {
        LocalDateTime niftyTime = niftyUpdatedAt.get();
        LocalDateTime bankNiftyTime = bankNiftyUpdatedAt.get();

        return MarketRegimeSnapshot.builder()
                .niftyChange(freshChange(niftyChange, niftyUpdatedAt))
                .bankNiftyChange(freshChange(bankNiftyChange, bankNiftyUpdatedAt))
                .regime(currentRegime())
                // Backward-compatible DTO field: latest update from either index,
                // not proof that both indices are fresh.
                .updatedAt(latest(niftyTime, bankNiftyTime))
                .build();
    }

    public void enrich(Tick tick) {
        if (tick == null) {
            return;
        }

        Double freshNifty = freshChange(niftyChange, niftyUpdatedAt);
        Double freshBankNifty = freshChange(bankNiftyChange, bankNiftyUpdatedAt);

        tick.setNiftyChange(freshNifty);
        tick.setBankNiftyChange(freshBankNifty);

        // A directional regime requires both index feeds to be fresh.
        if (freshNifty == null || freshBankNifty == null) {
            tick.setMarketRegime("UNKNOWN");
        } else {
            tick.setMarketRegime(calculateRegime(freshNifty, freshBankNifty));
        }
    }

    private String currentRegime() {
        Double freshNifty = freshChange(niftyChange, niftyUpdatedAt);
        Double freshBankNifty = freshChange(bankNiftyChange, bankNiftyUpdatedAt);

        if (freshNifty == null || freshBankNifty == null) {
            return "UNKNOWN";
        }

        return calculateRegime(freshNifty, freshBankNifty);
    }

    private Double freshChange(
            AtomicReference<Double> changeRef,
            AtomicReference<LocalDateTime> updatedAtRef) {

        Double change = changeRef.get();
        LocalDateTime updatedAt = updatedAtRef.get();

        if (change == null || !isFresh(updatedAt)) {
            return null;
        }

        return change;
    }

    private boolean isFresh(LocalDateTime updatedAt) {
        if (updatedAt == null || maxAgeSeconds < 0) {
            return false;
        }

        return !updatedAt.plusSeconds(maxAgeSeconds).isBefore(LocalDateTime.now());
    }

    private String calculateRegime(Double nifty, Double bankNifty) {
        if (nifty == null || bankNifty == null) {
            return "UNKNOWN";
        }

        boolean niftyBull = nifty >= directionThresholdPercent;
        boolean bankBull = bankNifty >= directionThresholdPercent;
        boolean niftyBear = nifty <= -directionThresholdPercent;
        boolean bankBear = bankNifty <= -directionThresholdPercent;

        if (niftyBull && bankBull) return "BULLISH";
        if (niftyBear && bankBear) return "BEARISH";
        if (Math.abs(nifty) < directionThresholdPercent
                && Math.abs(bankNifty) < directionThresholdPercent) {
            return "SIDEWAYS";
        }

        // Mixed index direction is intentionally treated as SIDEWAYS in V1.
        return "SIDEWAYS";
    }

    private Double effectiveChange(Tick tick) {
        if (tick.getChangePercentage() != null) {
            return tick.getChangePercentage();
        }

        if (tick.getLastTradedPrice() == null
                || tick.getPreviousClose() == null
                || tick.getPreviousClose() == 0) {
            return null;
        }

        return ((tick.getLastTradedPrice() - tick.getPreviousClose())
                / tick.getPreviousClose()) * 100.0;
    }

    private LocalDateTime latest(LocalDateTime first, LocalDateTime second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isAfter(second) ? first : second;
    }

    private String normalize(String symbol) {
        if (symbol == null) {
            return "";
        }

        // Preserve '_' so Upstox keys such as NSE_INDEX|NIFTY 50 still match.
        return symbol.trim().toUpperCase(Locale.ROOT)
                .replace("-", " ")
                .replaceAll("\\s+", " ");
    }
}
