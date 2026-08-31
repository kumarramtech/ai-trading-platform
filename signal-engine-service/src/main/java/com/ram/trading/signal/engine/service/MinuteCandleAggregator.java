package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class MinuteCandleAggregator {

    private final MinuteCandleStore minuteCandleStore;


    /**
     * Process one live tick.
     *
     * Every tick belongs to exactly one minute candle.
     */
    public MinuteCandle process(Tick tick) {

        if (!isValidTick(tick)) {
            return null;
        }

        String symbol =
                tick.getSymbol();

        Double price =
                tick.getLastTradedPrice();

        LocalDateTime tradeTime =
                tick.getTradeTime();

        /*
         * Normalize timestamp to the beginning
         * of its minute.
         *
         * Example:
         *
         * 09:15:37
         *     ↓
         * 09:15:00
         */
        LocalDateTime minute =
                tradeTime.truncatedTo(
                        ChronoUnit.MINUTES
                );

        MinuteCandle existing =
                minuteCandleStore.get(
                        symbol,
                        minute
                );

        /*
         * ============================================================
         * FIRST TICK FOR THIS MINUTE
         * ============================================================
         *
         * There is no existing candle for this minute.
         *
         * Before creating the new candle, check whether the
         * immediately previous minute has a completed candle.
         */
        if (existing == null) {

            LocalDateTime previousMinute =
                    minute.minusMinutes(1);

            MinuteCandle completedCandle =
                    minuteCandleStore.get(
                            symbol,
                            previousMinute
                    );

            MinuteCandle candle =
                    MinuteCandle.builder()
                            .symbol(symbol)
                            .minute(minute)
                            .open(price)
                            .high(price)
                            .low(price)
                            .close(price)
                            .volume(
                                    safeVolume(
                                            tick.getVolume()
                                    )
                            )
                            .build();

            minuteCandleStore.save(candle);

            log.debug(
                    "1-MIN CANDLE CREATED | " +
                            "Symbol={} | Minute={} | " +
                            "O={} | H={} | L={} | C={} | V={}",
                    symbol,
                    minute,
                    price,
                    price,
                    price,
                    price,
                    candle.getVolume()
            );

            /*
             * Return the previous candle because it is now complete.
             *
             * The newly-created candle must NOT be returned because
             * it is still being formed.
             */
            return completedCandle;
        }

        /*
         * ============================================================
         * UPDATE CURRENT CANDLE
         * ============================================================
         */

        existing.setHigh(
                max(
                        existing.getHigh(),
                        price
                )
        );

        existing.setLow(
                min(
                        existing.getLow(),
                        price
                )
        );

        /*
         * Latest tick becomes the candle close.
         */
        existing.setClose(price);

        /*
         * Add tick volume when available.
         */
        existing.setVolume(
                safeVolume(existing.getVolume())
                        + safeVolume(tick.getVolume())
        );

        minuteCandleStore.save(existing);

        /*
         * Current candle is still being formed.
         */
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