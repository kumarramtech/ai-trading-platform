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
 * Maintains a small, feed-derived market context from index ticks.
 * It is deliberately observational in V1: it enriches ticks and does not
 * reject trades. If an index is not subscribed, the regime remains UNKNOWN.
 */
@Service
@Slf4j
public class MarketRegimeTracker {

    private static final Set<String> NIFTY_SYMBOLS = Set.of(
            "NIFTY", "NIFTY50", "NIFTY 50", "NIFTY50 50",
            "NSE_INDEX|NIFTY 50", "NSE_INDEX|NIFTY50");
    private static final Set<String> BANK_NIFTY_SYMBOLS = Set.of(
            "BANKNIFTY", "BANK NIFTY", "NIFTYBANK", "NIFTY BANK",
            "NSE_INDEX|NIFTY BANK", "NSE_INDEX|BANKNIFTY");

    private final AtomicReference<Double> niftyChange = new AtomicReference<>();
    private final AtomicReference<Double> bankNiftyChange = new AtomicReference<>();
    private final AtomicReference<String> regime = new AtomicReference<>("UNKNOWN");
    private final AtomicReference<LocalDateTime> updatedAt = new AtomicReference<>();

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

        if (NIFTY_SYMBOLS.contains(symbol) || NIFTY_SYMBOLS.contains(instrumentKey)) {
            niftyChange.set(change);
        } else if (BANK_NIFTY_SYMBOLS.contains(symbol) || BANK_NIFTY_SYMBOLS.contains(instrumentKey)) {
            bankNiftyChange.set(change);
        } else {
            return;
        }

        String oldRegime = regime.get();
        String newRegime = calculateRegime(niftyChange.get(), bankNiftyChange.get());
        regime.set(newRegime);
        updatedAt.set(LocalDateTime.now());

        if (!newRegime.equals(oldRegime)) {
            log.info("MARKET REGIME CHANGED | Regime={} | NIFTY={}% | BANKNIFTY={}%",
                    newRegime, niftyChange.get(), bankNiftyChange.get());
        } else {
            log.debug("MARKET REGIME UPDATED | Regime={} | NIFTY={}% | BANKNIFTY={}%",
                    newRegime, niftyChange.get(), bankNiftyChange.get());
        }
    }

    public MarketRegimeSnapshot snapshot() {
        return MarketRegimeSnapshot.builder()
                .niftyChange(niftyChange.get())
                .bankNiftyChange(bankNiftyChange.get())
                .regime(regime.get())
                .updatedAt(updatedAt.get())
                .build();
    }

    public void enrich(Tick tick) {
        if (tick == null) return;

        MarketRegimeSnapshot snapshot = snapshot();
        boolean fresh = isFresh(snapshot.getUpdatedAt());

        tick.setNiftyChange(fresh ? snapshot.getNiftyChange() : null);
        tick.setBankNiftyChange(fresh ? snapshot.getBankNiftyChange() : null);
        tick.setMarketRegime(fresh ? snapshot.getRegime() : "UNKNOWN");
    }

    private boolean isFresh(LocalDateTime updatedAt) {
        if (updatedAt == null) {
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
                && Math.abs(bankNifty) < directionThresholdPercent) return "SIDEWAYS";

        // Mixed index direction is intentionally treated as SIDEWAYS in V1.
        return "SIDEWAYS";
    }

    private Double effectiveChange(Tick tick) {
        if (tick.getChangePercentage() != null) return tick.getChangePercentage();
        if (tick.getLastTradedPrice() == null || tick.getPreviousClose() == null
                || tick.getPreviousClose() == 0) return null;
        return ((tick.getLastTradedPrice() - tick.getPreviousClose())
                / tick.getPreviousClose()) * 100.0;
    }

    private String normalize(String symbol) {
        if (symbol == null) {
            return "";
        }
        return symbol.trim().toUpperCase(Locale.ROOT)
                .replace("-", " ")
                .replace("_", " ")
                .replaceAll("\\s+", " ");
    }
}
