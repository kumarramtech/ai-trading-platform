package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.dto.premarket.OpeningRange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpeningRangeService {

    private static final LocalTime OPENING_RANGE_START =
            LocalTime.of(9, 15);

    /*
     * 09:15 through 09:29 candles.
     *
     * 09:30 is the point at which the range is complete.
     */
    private static final LocalTime OPENING_RANGE_END =
            LocalTime.of(9, 30);

    private static final int EXPECTED_CANDLE_COUNT = 15;

    private final MinuteCandleStore minuteCandleStore;

    /**
     * Today's calculated opening ranges.
     *
     * symbol -> OpeningRange
     */
    private final Map<String, OpeningRange> openingRanges =
            new ConcurrentHashMap<>();


    /**
     * Calculate the opening range for one symbol.
     *
     * Expected candles:
     *
     * 09:15
     * 09:16
     * ...
     * 09:29
     *
     * Total = 15 one-minute candles.
     */
    public OpeningRange calculate(
            String symbol) {

        if (symbol == null
                || symbol.isBlank()) {

            return null;
        }

        LocalDate today =
                LocalDate.now();

        LocalDateTime start =
                LocalDateTime.of(
                        today,
                        OPENING_RANGE_START
                );

        LocalDateTime end =
                LocalDateTime.of(
                        today,
                        OPENING_RANGE_END
                );

        List<MinuteCandle> candles =
                new ArrayList<>(
                        minuteCandleStore.getBetween(
                                symbol,
                                start,
                                end
                        )
                );

        if (candles.isEmpty()) {

            log.debug(
                    "OPENING RANGE | No candles available | Symbol={}",
                    symbol
            );

            return null;
        }

        /*
         * We require the complete opening range.
         *
         * Never build a partial OR and pretend it is valid.
         */
        if (!hasCompleteRange(candles)) {

            log.warn(
                    "OPENING RANGE INCOMPLETE | " +
                            "Symbol={} | Expected={} | Actual={}",
                    symbol,
                    EXPECTED_CANDLE_COUNT,
                    candles.size()
            );

            return null;
        }

        /*
         * Sort defensively even though the store already
         * returns sorted candles.
         */
        candles.sort(
                Comparator.comparing(
                        MinuteCandle::getMinute
                )
        );

        MinuteCandle first =
                candles.get(0);

        MinuteCandle last =
                candles.get(candles.size() - 1);

        double high =
                candles.stream()
                        .map(MinuteCandle::getHigh)
                        .filter(value -> value != null)
                        .max(Double::compareTo)
                        .orElse(0.0);

        double low =
                candles.stream()
                        .map(MinuteCandle::getLow)
                        .filter(value -> value != null)
                        .min(Double::compareTo)
                        .orElse(0.0);

        double range =
                high - low;

        double rangePercentage =
                first.getOpen() != null
                        && first.getOpen() > 0
                        ? (range / first.getOpen()) * 100.0
                        : 0.0;

        long volume =
                candles.stream()
                        .mapToLong(
                                candle ->
                                        candle.getVolume() == null
                                                ? 0L
                                                : Math.max(
                                                        candle.getVolume(),
                                                        0L
                                                )
                        )
                        .sum();

        OpeningRange openingRange =
                OpeningRange.builder()
                        .symbol(symbol)
                        .tradingDate(today)
                        .startTime(start)
                        .endTime(end)
                        .open(first.getOpen())
                        .high(high)
                        .low(low)
                        .close(last.getClose())
                        .volume(volume)
                        .range(range)
                        .rangePercentage(rangePercentage)
                        .candleCount(candles.size())
                        .complete(true)
                        .build();

        openingRanges.put(
                symbol,
                openingRange
        );

        log.info(
                "OPENING RANGE CREATED | " +
                        "Symbol={} | " +
                        "Open={} | " +
                        "High={} | " +
                        "Low={} | " +
                        "Close={} | " +
                        "Range={} | " +
                        "Range%={} | " +
                        "Volume={} | " +
                        "Candles={}",
                symbol,
                openingRange.getOpen(),
                openingRange.getHigh(),
                openingRange.getLow(),
                openingRange.getClose(),
                openingRange.getRange(),
                openingRange.getRangePercentage(),
                openingRange.getVolume(),
                openingRange.getCandleCount()
        );

        return openingRange;
    }


    /**
     * Calculate opening ranges for all symbols currently
     * available in the candle store.
     */
    public Map<String, OpeningRange> calculateAll() {

        Map<String, OpeningRange> result =
                new ConcurrentHashMap<>();

        for (String symbol :
                minuteCandleStore.getSymbols()) {

            OpeningRange range =
                    calculate(symbol);

            if (range != null) {
                result.put(symbol, range);
            }
        }

        return result;
    }


    /**
     * Get previously calculated opening range.
     */
    public OpeningRange get(
            String symbol) {

        if (symbol == null
                || symbol.isBlank()) {

            return null;
        }

        return openingRanges.get(symbol);
    }


    /**
     * Check whether an opening range exists.
     */
    public boolean exists(
            String symbol) {

        return get(symbol) != null;
    }


    /**
     * Clear today's opening ranges.
     */
    public void clear() {

        openingRanges.clear();
    }


    private boolean hasCompleteRange(
            List<MinuteCandle> candles) {

        if (candles.size()
                != EXPECTED_CANDLE_COUNT) {

            return false;
        }

        LocalDateTime expected =
                LocalDateTime.of(
                        LocalDate.now(),
                        OPENING_RANGE_START
                );

        for (MinuteCandle candle : candles) {

            if (candle == null
                    || candle.getMinute() == null
                    || !candle.getMinute()
                    .equals(expected)) {

                return false;
            }

            if (candle.getOpen() == null
                    || candle.getHigh() == null
                    || candle.getLow() == null
                    || candle.getClose() == null) {

                return false;
            }

            expected =
                    expected.plusMinutes(1);
        }

        return true;
    }
}