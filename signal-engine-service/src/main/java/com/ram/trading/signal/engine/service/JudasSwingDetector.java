package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.premarket.JudasSwingResult;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class JudasSwingDetector {

    private static final LocalTime ANALYSIS_START =
            LocalTime.of(9, 30);

    /*
     * We analyse the first post-opening candles.
     *
     * This is deliberately limited rather than allowing
     * a Judas setup to appear at any random time during
     * the trading session.
     */
    private static final LocalTime ANALYSIS_END =
            LocalTime.of(10, 30);

    /*
     * Confirmation must close back inside the opening range.
     */
    private static final double MIN_REENTRY_PERCENT =
            0.0;

    private final MinuteCandleStore minuteCandleStore;


    /**
     * Detect a possible Judas Swing after the opening range.
     *
     * IMPORTANT:
     *
     * This method does NOT generate a BUY/SELL signal.
     *
     * It only identifies a confirmed opening-range reversal.
     */
    public JudasSwingResult detect(
            OpeningRange openingRange) {

        if (openingRange == null
                || openingRange.getSymbol() == null
                || openingRange.getSymbol().isBlank()) {

            return rejected(
                    null,
                    "OPENING_RANGE_UNAVAILABLE"
            );
        }

        if (!openingRange.isComplete()) {

            return rejected(
                    openingRange.getSymbol(),
                    "OPENING_RANGE_INCOMPLETE"
            );
        }

        String symbol =
                openingRange.getSymbol();

        LocalDate today =
                LocalDate.now();

        LocalDateTime start =
                LocalDateTime.of(
                        today,
                        ANALYSIS_START
                );

        LocalDateTime end =
                LocalDateTime.of(
                        today,
                        ANALYSIS_END
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

            return rejected(
                    symbol,
                    "NO_POST_OPENING_CANDLES"
            );
        }

        candles.sort(
                Comparator.comparing(
                        MinuteCandle::getMinute
                )
        );

        /*
         * --------------------------------------------------------
         * First look for a HIGH sweep.
         * --------------------------------------------------------
         *
         * Price trades above the opening-range high and then
         * closes back below it.
         */
        JudasSwingResult bearish =
                detectHighSweep(
                        openingRange,
                        candles
                );

        if (bearish.isDetected()) {

            return bearish;
        }

        /*
         * --------------------------------------------------------
         * Then look for a LOW sweep.
         * --------------------------------------------------------
         *
         * Price trades below the opening-range low and then
         * closes back above it.
         */
        JudasSwingResult bullish =
                detectLowSweep(
                        openingRange,
                        candles
                );

        if (bullish.isDetected()) {

            return bullish;
        }

        return rejected(
                symbol,
                "NO_CONFIRMED_JUDAS_SWING"
        );
    }


    /**
     * Detect bearish Judas Swing:
     *
     * Opening Range High
     *          ↓
     * Price sweeps above it
     *          ↓
     * Candle closes back below it
     *          ↓
     * Confirmation candle also remains below level
     */
    private JudasSwingResult detectHighSweep(
            OpeningRange openingRange,
            List<MinuteCandle> candles) {

        double rangeHigh =
                openingRange.getHigh();

        for (int i = 0; i < candles.size() - 1; i++) {

            MinuteCandle sweep =
                    candles.get(i);

            MinuteCandle confirmation =
                    candles.get(i + 1);

            if (!validCandle(sweep)
                    || !validCandle(confirmation)) {

                continue;
            }

            /*
             * Initial liquidity sweep above OR high.
             */
            boolean swept =
                    sweep.getHigh() > rangeHigh;

            if (!swept) {
                continue;
            }

            /*
             * Sweep candle must reject the breakout
             * and close back below the OR high.
             */
            boolean rejected =
                    sweep.getClose() < rangeHigh;

            if (!rejected) {
                continue;
            }

            /*
             * Confirmation candle must continue to close
             * below the opening-range high.
             */
            boolean confirmed =
                    confirmation.getClose() < rangeHigh
                            - MIN_REENTRY_PERCENT;

            if (!confirmed) {
                continue;
            }

            return buildResult(
                    openingRange,
                    "BEARISH_REVERSAL",
                    "HIGH_SWEEP",
                    sweep,
                    confirmation,
                    "PRICE_SWEPT_OPENING_RANGE_HIGH_AND_CONFIRMED_REVERSAL"
            );
        }

        return rejected(
                openingRange.getSymbol(),
                "NO_CONFIRMED_HIGH_SWEEP"
        );
    }


    /**
     * Detect bullish Judas Swing:
     *
     * Opening Range Low
     *          ↓
     * Price sweeps below it
     *          ↓
     * Candle closes back above it
     *          ↓
     * Confirmation candle remains above level
     */
    private JudasSwingResult detectLowSweep(
            OpeningRange openingRange,
            List<MinuteCandle> candles) {

        double rangeLow =
                openingRange.getLow();

        for (int i = 0; i < candles.size() - 1; i++) {

            MinuteCandle sweep =
                    candles.get(i);

            MinuteCandle confirmation =
                    candles.get(i + 1);

            if (!validCandle(sweep)
                    || !validCandle(confirmation)) {

                continue;
            }

            /*
             * Initial liquidity sweep below OR low.
             */
            boolean swept =
                    sweep.getLow() < rangeLow;

            if (!swept) {
                continue;
            }

            /*
             * Sweep candle must reject the breakdown
             * and close back above the OR low.
             */
            boolean rejected =
                    sweep.getClose() > rangeLow;

            if (!rejected) {
                continue;
            }

            /*
             * Confirmation candle must continue to close
             * above the opening-range low.
             */
            boolean confirmed =
                    confirmation.getClose() > rangeLow
                            + MIN_REENTRY_PERCENT;

            if (!confirmed) {
                continue;
            }

            return buildResult(
                    openingRange,
                    "BULLISH_REVERSAL",
                    "LOW_SWEEP",
                    sweep,
                    confirmation,
                    "PRICE_SWEPT_OPENING_RANGE_LOW_AND_CONFIRMED_REVERSAL"
            );
        }

        return rejected(
                openingRange.getSymbol(),
                "NO_CONFIRMED_LOW_SWEEP"
        );
    }


    private JudasSwingResult buildResult(
            OpeningRange openingRange,
            String direction,
            String sweepSide,
            MinuteCandle sweep,
            MinuteCandle confirmation,
            String reason) {

        return JudasSwingResult.builder()
                .symbol(openingRange.getSymbol())
                .detected(true)
                .direction(direction)
                .sweepSide(sweepSide)
                .openingRangeHigh(
                        openingRange.getHigh()
                )
                .openingRangeLow(
                        openingRange.getLow()
                )
                .sweepPrice(
                        sweep.getClose()
                )
                .confirmationPrice(
                        confirmation.getClose()
                )
                .confirmationVolume(
                        toDouble(
                                confirmation.getVolume()
                        )
                )
                .averageVolume(
                        calculateAverageVolume(
                                openingRange.getSymbol()
                        )
                )
                .sweepTime(
                        sweep.getMinute()
                )
                .confirmationTime(
                        confirmation.getMinute()
                )
                .reason(reason)
                .build();
    }


    private Double calculateAverageVolume(
            String symbol) {

        List<MinuteCandle> candles =
                minuteCandleStore.getToday(symbol);

        if (candles.isEmpty()) {
            return null;
        }

        return candles.stream()
                .filter(this::validCandle)
                .map(MinuteCandle::getVolume)
                .filter(volume -> volume != null)
                .mapToDouble(Long::doubleValue)
                .average()
                .orElse(0.0);
    }


    private boolean validCandle(
            MinuteCandle candle) {

        return candle != null
                && candle.getMinute() != null
                && candle.getOpen() != null
                && candle.getHigh() != null
                && candle.getLow() != null
                && candle.getClose() != null;
    }


    private Double toDouble(
            Long value) {

        return value == null
                ? null
                : value.doubleValue();
    }


    private JudasSwingResult rejected(
            String symbol,
            String reason) {

        return JudasSwingResult.builder()
                .symbol(symbol)
                .detected(false)
                .direction("NONE")
                .sweepSide("NONE")
                .reason(reason)
                .build();
    }
}