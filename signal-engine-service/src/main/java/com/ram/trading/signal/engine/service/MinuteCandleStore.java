package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.entity.MinuteCandleEntity;
import com.ram.trading.signal.engine.repo.MinuteCandleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class MinuteCandleStore {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    /** Wall clock in IST; a replay sets a fixed clock on its own instance to evaluate a past day. */
    private java.time.Clock clock = java.time.Clock.system(MARKET_ZONE);

    public void setClock(java.time.Clock clock) {
        this.clock = clock;
    }

    private final MinuteCandleRepository minuteCandleRepository;

    /*
     * symbol -> minute -> candle
     */
    private final Map<String, Map<LocalDateTime, MinuteCandle>> candles =
            new ConcurrentHashMap<>();

    // O(1) watermark lookup: the aggregator calls this for every market tick.
    private final Map<String, LocalDateTime> latestMinuteBySymbol =
            new ConcurrentHashMap<>();



    /** Restore today's persisted candles so a service restart does not erase
     * the real opening-range history. Only completed candles are persisted.
     */
    @PostConstruct
    public void restoreToday() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();

        try {
            List<MinuteCandleEntity> persisted =
                    minuteCandleRepository.findByMinuteGreaterThanEqualAndMinuteLessThan(start, end);
            for (MinuteCandleEntity entity : persisted) {
                save(toCandle(entity));
            }
            log.info("MINUTE CANDLES RESTORED | Date={} | Count={}", today, persisted.size());
        } catch (Exception ex) {
            // Fail visibly: without restored candles the opening range may remain unavailable.
            log.error("MINUTE CANDLE RESTORE FAILED | Date={} | Opening range may be unavailable after restart",
                    today, ex);
        }
    }

    /** Persist a candle after its minute has completed. */
    public void saveCompleted(MinuteCandle candle) {
        if (candle == null || candle.getSymbol() == null || candle.getSymbol().isBlank()
                || candle.getMinute() == null) {
            return;
        }
        save(candle);
        try {
            MinuteCandleEntity entity = minuteCandleRepository
                    .findBySymbolAndMinute(candle.getSymbol(), candle.getMinute())
                    .orElseGet(MinuteCandleEntity::new);
            entity.setSymbol(candle.getSymbol());
            entity.setMinute(candle.getMinute());
            entity.setOpenPrice(candle.getOpen());
            entity.setHighPrice(candle.getHigh());
            entity.setLowPrice(candle.getLow());
            entity.setClosePrice(candle.getClose());
            entity.setVolume(candle.getVolume());
            minuteCandleRepository.save(entity);
        } catch (Exception ex) {
            log.error("MINUTE CANDLE PERSIST FAILED | Symbol={} | Minute={}",
                    candle.getSymbol(), candle.getMinute(), ex);
        }
    }

    private MinuteCandle toCandle(MinuteCandleEntity entity) {
        return MinuteCandle.builder()
                .symbol(entity.getSymbol())
                .minute(entity.getMinute())
                .open(entity.getOpenPrice())
                .high(entity.getHighPrice())
                .low(entity.getLowPrice())
                .close(entity.getClosePrice())
                .volume(entity.getVolume())
                .build();
    }

    /**
     * Store or replace a candle in memory.
     */
    public void save(MinuteCandle candle) {

        if (candle == null
                || candle.getSymbol() == null
                || candle.getSymbol().isBlank()
                || candle.getMinute() == null) {

            return;
        }

        candles
                .computeIfAbsent(
                        candle.getSymbol(),
                        key -> new ConcurrentHashMap<>()
                )
                .put(
                        candle.getMinute(),
                        candle
                );

        latestMinuteBySymbol.merge(
                candle.getSymbol(),
                candle.getMinute(),
                (current, candidate) -> current.isAfter(candidate) ? current : candidate);
    }

    public Set<String> getSymbols() {
        return Collections.unmodifiableSet(
                new HashSet<>(candles.keySet())
        );
    }


    /**
     * Get one candle.
     */
    public MinuteCandle get(
            String symbol,
            LocalDateTime minute) {

        if (symbol == null || minute == null) {
            return null;
        }

        Map<LocalDateTime, MinuteCandle> symbolCandles =
                candles.get(symbol);

        if (symbolCandles == null) {
            return null;
        }

        return symbolCandles.get(minute);
    }


    /**
     * Returns the latest stored candle strictly before the supplied minute.
     * This supports sparse feeds without fabricating candles for missing minutes.
     */
    public MinuteCandle getLatestBefore(String symbol, LocalDateTime minute) {
        if (symbol == null || symbol.isBlank() || minute == null) {
            return null;
        }

        Map<LocalDateTime, MinuteCandle> symbolCandles = candles.get(symbol);
        if (symbolCandles == null) {
            return null;
        }

        return symbolCandles.entrySet().stream()
                .filter(entry -> entry.getKey() != null
                        && entry.getKey().toLocalDate().equals(minute.toLocalDate())
                        && entry.getKey().isBefore(minute))
                .max(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    /**
     * Latest observed candle minute for a symbol, or null when no candle exists.
     */
    public LocalDateTime getLatestMinute(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }

        return latestMinuteBySymbol.get(symbol);
    }

    /**
     * Get all candles for a symbol for today.
     */
    public List<MinuteCandle> getToday(
            String symbol) {

        if (symbol == null || symbol.isBlank()) {
            return List.of();
        }

        Map<LocalDateTime, MinuteCandle> symbolCandles =
                candles.get(symbol);

        if (symbolCandles == null) {
            return List.of();
        }

        LocalDate today = LocalDate.now(clock);

        List<MinuteCandle> result =
                new ArrayList<>();

        for (MinuteCandle candle :
                symbolCandles.values()) {

            if (candle.getMinute() != null
                    && candle.getMinute()
                    .toLocalDate()
                    .equals(today)) {

                result.add(candle);
            }
        }

        result.sort(
                Comparator.comparing(
                        MinuteCandle::getMinute
                )
        );

        return Collections.unmodifiableList(result);
    }


    /**
     * Get candles between two times.
     */
    public List<MinuteCandle> getBetween(
            String symbol,
            LocalDateTime from,
            LocalDateTime to) {

        if (symbol == null
                || symbol.isBlank()
                || from == null
                || to == null) {

            return List.of();
        }

        Map<LocalDateTime, MinuteCandle> symbolCandles =
                candles.get(symbol);

        if (symbolCandles == null) {
            return List.of();
        }

        List<MinuteCandle> result =
                new ArrayList<>();

        for (MinuteCandle candle :
                symbolCandles.values()) {

            LocalDateTime minute =
                    candle.getMinute();

            if (minute != null
                    && !minute.isBefore(from)
                    && minute.isBefore(to)) {

                result.add(candle);
            }
        }

        result.sort(
                Comparator.comparing(
                        MinuteCandle::getMinute
                )
        );

        return Collections.unmodifiableList(result);
    }


    /**
     * Clear today's candles.
     */
    public void clearToday() {

        LocalDate today = LocalDate.now(clock);

        candles.forEach((symbol, symbolCandles) -> {
            symbolCandles.entrySet().removeIf(entry -> {
                MinuteCandle candle = entry.getValue();
                return candle.getMinute() != null
                        && candle.getMinute().toLocalDate().equals(today);
            });
            LocalDateTime latestRemaining = symbolCandles.keySet().stream()
                    .filter(Objects::nonNull)
                    .max(LocalDateTime::compareTo)
                    .orElse(null);
            if (latestRemaining == null) {
                latestMinuteBySymbol.remove(symbol);
            } else {
                latestMinuteBySymbol.put(symbol, latestRemaining);
            }
        });
    }


    /**
     * Clear everything.
     */
    public void clearAll() {
        candles.clear();
        latestMinuteBySymbol.clear();
    }


    /**
     * Number of symbols currently stored.
     */
    public int symbolCount() {

        return candles.size();
    }
}
