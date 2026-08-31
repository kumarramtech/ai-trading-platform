package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MinuteCandleStore {

    /*
     * symbol -> minute -> candle
     */
    private final Map<String, Map<LocalDateTime, MinuteCandle>> candles =
            new ConcurrentHashMap<>();


    /**
     * Store or replace a candle.
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

        LocalDate today = LocalDate.now();

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
                java.util.Comparator.comparing(
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
                java.util.Comparator.comparing(
                        MinuteCandle::getMinute
                )
        );

        return Collections.unmodifiableList(result);
    }


    /**
     * Clear today's candles.
     */
    public void clearToday() {

        LocalDate today = LocalDate.now();

        candles.forEach(
                (symbol, symbolCandles) ->
                        symbolCandles
                                .entrySet()
                                .removeIf(entry -> {

                                    MinuteCandle candle =
                                            entry.getValue();

                                    return candle.getMinute() != null
                                            && candle.getMinute()
                                            .toLocalDate()
                                            .equals(today);
                                })
        );
    }


    /**
     * Clear everything.
     */
    public void clearAll() {

        candles.clear();
    }


    /**
     * Number of symbols currently stored.
     */
    public int symbolCount() {

        return candles.size();
    }
}