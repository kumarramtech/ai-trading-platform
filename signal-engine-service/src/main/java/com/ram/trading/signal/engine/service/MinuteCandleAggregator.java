package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class MinuteCandleAggregator {

    private final MinuteCandleStore minuteCandleStore;

    // Per-symbol watermark prevents duplicate completion and late ticks from
    // rewriting candles that have already been consumed by strategy evaluation.
    private final Map<String, LocalDateTime> lastEmittedMinute = new ConcurrentHashMap<>();

    /** Avoid re-processing a candle that was already completed before a restart. */
    @PostConstruct
    public void restoreEmissionWatermarks() {
        for (String symbol : minuteCandleStore.getSymbols()) {
            LocalDateTime latestMinute = minuteCandleStore.getLatestMinute(symbol);
            if (latestMinute != null) {
                lastEmittedMinute.put(symbol, latestMinute);
            }
        }
        log.info("CANDLE EMISSION WATERMARKS RESTORED | Symbols={}", lastEmittedMinute.size());
    }


    /**
     * Process one live tick.
     *
     * Every tick belongs to exactly one minute candle.
     */

    public MinuteCandle process(Tick tick) {

        if (!isValidTick(tick)) {
            return null;
        }

        String symbol = tick.getSymbol();
        Double price = tick.getLastTradedPrice();

        LocalDateTime tradeTime = tick.getTradeTime();

        if (tradeTime == null) {
            log.warn(
                    "Skipping tick with missing trade time | Symbol={} | Timestamp={}",
                    symbol,
                    tick.getTimestamp()
            );
            return null;
        }

        LocalDateTime minute = tradeTime.truncatedTo(ChronoUnit.MINUTES);

        LocalDateTime latestStoredMinute = minuteCandleStore.getLatestMinute(symbol);
        if (latestStoredMinute != null && minute.isBefore(latestStoredMinute)) {
            log.debug(
                    "LATE TICK IGNORED | Symbol={} | TickMinute={} | LatestStoredMinute={}",
                    symbol, minute, latestStoredMinute);
            return null;
        }

        MinuteCandle existing = minuteCandleStore.get(symbol, minute);

        if (existing == null) {

            MinuteCandle completedCandidate = minuteCandleStore.getLatestBefore(symbol, minute);
            MinuteCandle completedCandle = null;
            if (completedCandidate != null) {
                LocalDateTime lastEmitted = lastEmittedMinute.get(symbol);
                if (lastEmitted == null || completedCandidate.getMinute().isAfter(lastEmitted)) {
                    completedCandle = completedCandidate;
                    lastEmittedMinute.put(symbol, completedCandidate.getMinute());
                }
                // Persist only after the next minute starts; avoids a database write per tick.
                minuteCandleStore.saveCompleted(completedCandidate);
            }

            MinuteCandle candle = MinuteCandle.builder()
                    .symbol(symbol)
                    .minute(minute)
                    .open(price)
                    .high(price)
                    .low(price)
                    .close(price)
                    .volume(safeVolume(tick.getVolume()))
                    .build();

            minuteCandleStore.save(candle);

            log.debug(
                    "1-MIN CANDLE CREATED | Symbol={} | Minute={} | O={} | H={} | L={} | C={} | V={}",
                    symbol,
                    minute,
                    price,
                    price,
                    price,
                    price,
                    candle.getVolume()
            );

            return completedCandle;
        }

        existing.setHigh(max(existing.getHigh(), price));
        existing.setLow(min(existing.getLow(), price));
        existing.setClose(price);

        // Tick volume is cumulative; replace rather than sum repeated snapshots.
        if (tick.getVolume() != null) {
            existing.setVolume(safeVolume(tick.getVolume()));
        }

        minuteCandleStore.save(existing);

        return null;
    }



    private boolean isValidTick(Tick tick) {

        if (tick == null) {
            return false;
        }

        if (tick.getSymbol() == null
                || tick.getSymbol().isBlank()) {

            return false;
        }

        if (tick.getLastTradedPrice() == null
                || tick.getLastTradedPrice() <= 0) {

            return false;
        }

        if (tick.getTimestamp() == null) {
            return false;
        }

        return true;
    }


    private Double max(
            Double first,
            Double second) {

        if (first == null) {
            return second;
        }

        if (second == null) {
            return first;
        }

        return Math.max(first, second);
    }


    private Double min(
            Double first,
            Double second) {

        if (first == null) {
            return second;
        }

        if (second == null) {
            return first;
        }

        return Math.min(first, second);
    }


    private long safeVolume(Long volume) {

        return volume == null
                ? 0L
                : Math.max(volume, 0L);
    }
}