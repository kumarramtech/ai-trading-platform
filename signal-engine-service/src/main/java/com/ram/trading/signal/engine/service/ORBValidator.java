package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.dto.premarket.ORBResult;
import com.ram.trading.signal.engine.dto.premarket.OpeningRange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ORBValidator {

    private static final ZoneId IST =
            ZoneId.of("Asia/Kolkata");

    /**
     * ORB analysis starts after the opening range is complete.
     */
    private static final LocalTime ANALYSIS_START =
            LocalTime.of(9, 30);

    /**
     * We don't allow an ORB setup to appear indefinitely
     * throughout the entire trading day.
     */
    private static final LocalTime ANALYSIS_END =
            LocalTime.of(11, 30);

    /**
     * Confirmation volume must be at least 1.2x the average
     * volume of the opening-range candles.
     */
    private static final double MIN_VOLUME_RATIO = 1.20;

    private final MinuteCandleStore minuteCandleStore;


    /**
     * Validate an Opening Range Breakout.
     *
     * This method DOES NOT create a BUY/SELL signal.
     *
     * It only determines whether a structurally valid ORB
     * setup exists.
     */
    public ORBResult validate(
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
                LocalDate.now(IST);

        LocalDateTime analysisStart =
                LocalDateTime.of(
                        today,
                        ANALYSIS_START
                );

        LocalDateTime analysisEnd =
                LocalDateTime.of(
                        today,
                        ANALYSIS_END
                );

        List<MinuteCandle> candles =
                new ArrayList<>(
                        minuteCandleStore.getBetween(
                                symbol,
                                analysisStart,
                                analysisEnd
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
         * Calculate the average volume from the
         * 09:15-09:29 opening range.
         */
        double averageOpeningRangeVolume =
                calculateOpeningRangeAverageVolume(
                        symbol
                );

        if (averageOpeningRangeVolume <= 0) {

            return rejected(
                    symbol,
                    "OPENING_RANGE_VOLUME_UNAVAILABLE"
            );
        }

        /*
         * ========================================================
         * BULLISH ORB
         * ========================================================
         *
         * 1. Price breaks above OR High.
         * 2. A later candle retests OR High.
         * 3. Retest holds.
         * 4. Confirmation closes above OR High.
         * 5. Confirmation volume is strong enough.
         */
        ORBResult bullish =
                detectBullishORB(
                        openingRange,
                        candles,
                        averageOpeningRangeVolume
                );

        if (bullish.isValid()) {

            return bullish;
        }

        /*
         * ========================================================
         * BEARISH ORB
         * ========================================================
         */
        ORBResult bearish =
                detectBearishORB(
                        openingRange,
                        candles,
                        averageOpeningRangeVolume
                );

        if (bearish.isValid()) {

            return bearish;
        }

        return rejected(
                symbol,
                "NO_VALID_ORB"
        );
    }


    /**
     * ------------------------------------------------------------
     * Bullish ORB
     * ------------------------------------------------------------
     */
    private ORBResult detectBullishORB(
            OpeningRange openingRange,
            List<MinuteCandle> candles,
            double averageVolume) {

        double rangeHigh =
                openingRange.getHigh();

        for (int i = 0; i < candles.size() - 2; i++) {

            MinuteCandle breakout =
                    candles.get(i);

            MinuteCandle retest =
                    candles.get(i + 1);

            MinuteCandle confirmation =
                    candles.get(i + 2);

            if (!validCandle(breakout)
                    || !validCandle(retest)
                    || !validCandle(confirmation)) {

                continue;
            }

            /*
             * STEP 1
             *
             * Breakout must CLOSE above OR High.
             *
             * Merely touching the level is not a breakout.
             */
            boolean breakoutConfirmed =
                    breakout.getClose() > rangeHigh;

            if (!breakoutConfirmed) {
                continue;
            }

            /*
             * STEP 2
             *
             * Retest must come back to the broken level.
             *
             * Its LOW must touch/cross the OR High.
             */
            boolean retested =
                    retest.getLow() <= rangeHigh;

            if (!retested) {
                continue;
            }

            /*
             * STEP 3
             *
             * Retest must hold the broken level.
             *
             * Close must remain above OR High.
             */
            boolean retestHeld =
                    retest.getClose() > rangeHigh;

            if (!retestHeld) {
                continue;
            }

            /*
             * STEP 4
             *
             * Confirmation must close above the broken level.
             */
            boolean confirmationValid =
                    confirmation.getClose() > rangeHigh;

            if (!confirmationValid) {
                continue;
            }

            /*
             * STEP 5
             *
             * Volume confirmation.
             */
            double volumeRatio =
                    calculateVolumeRatio(
                            confirmation,
                            averageVolume
                    );

            if (volumeRatio < MIN_VOLUME_RATIO) {

                continue;
            }

            return ORBResult.builder()
                    .symbol(openingRange.getSymbol())
                    .valid(true)
                    .direction("BULLISH")
                    .breakoutSide("HIGH_BREAKOUT")
                    .openingRangeHigh(rangeHigh)
                    .openingRangeLow(
                            openingRange.getLow()
                    )
                    .breakoutPrice(
                            breakout.getClose()
                    )
                    .retestPrice(
                            retest.getClose()
                    )
                    .confirmationPrice(
                            confirmation.getClose()
                    )
                    .confirmationVolume(
                            confirmation.getVolume()
                    )
                    .averageOpeningRangeVolume(
                            averageVolume
                    )
                    .volumeRatio(volumeRatio)
                    .breakoutTime(
                            breakout.getMinute()
                    )
                    .retestTime(
                            retest.getMinute()
                    )
                    .confirmationTime(
                            confirmation.getMinute()
                    )
                    .reason(
                            "BULLISH_BREAKOUT_RETEST_CONFIRMATION"
                    )
                    .build();
        }

        return rejected(
                openingRange.getSymbol(),
                "NO_VALID_BULLISH_ORB"
        );
    }


    /**
     * ------------------------------------------------------------
     * Bearish ORB
     * ------------------------------------------------------------
     */
    private ORBResult detectBearishORB(
            OpeningRange openingRange,
            List<MinuteCandle> candles,
            double averageVolume) {

        double rangeLow =
                openingRange.getLow();

        for (int i = 0; i < candles.size() - 2; i++) {

            MinuteCandle breakout =
                    candles.get(i);

            MinuteCandle retest =
                    candles.get(i + 1);

            MinuteCandle confirmation =
                    candles.get(i + 2);

            if (!validCandle(breakout)
                    || !validCandle(retest)
                    || !validCandle(confirmation)) {

                continue;
            }

            /*
             * STEP 1
             *
             * Breakout must CLOSE below OR Low.
             */
            boolean breakoutConfirmed =
                    breakout.getClose() < rangeLow;

            if (!breakoutConfirmed) {
                continue;
            }

            /*
             * STEP 2
             *
             * Retest must come back to the broken level.
             *
             * Its HIGH must touch/cross OR Low.
             */
            boolean retested =
                    retest.getHigh() >= rangeLow;

            if (!retested) {
                continue;
            }

            /*
             * STEP 3
             *
             * Retest must hold below the broken level.
             */
            boolean retestHeld =
                    retest.getClose() < rangeLow;

            if (!retestHeld) {
                continue;
            }

            /*
             * STEP 4
             *
             * Confirmation closes below OR Low.
             */
            boolean confirmationValid =
                    confirmation.getClose() < rangeLow;

            if (!confirmationValid) {
                continue;
            }

            /*
             * STEP 5
             *
             * Volume confirmation.
             */
            double volumeRatio =
                    calculateVolumeRatio(
                            confirmation,
                            averageVolume
                    );

            if (volumeRatio < MIN_VOLUME_RATIO) {

                continue;
            }

            return ORBResult.builder()
                    .symbol(openingRange.getSymbol())
                    .valid(true)
                    .direction("BEARISH")
                    .breakoutSide("LOW_BREAKOUT")
                    .openingRangeHigh(
                            openingRange.getHigh()
                    )
                    .openingRangeLow(rangeLow)
                    .breakoutPrice(
                            breakout.getClose()
                    )
                    .retestPrice(
                            retest.getClose()
                    )
                    .confirmationPrice(
                            confirmation.getClose()
                    )
                    .confirmationVolume(
                            confirmation.getVolume()
                    )
                    .averageOpeningRangeVolume(
                            averageVolume
                    )
                    .volumeRatio(volumeRatio)
                    .breakoutTime(
                            breakout.getMinute()
                    )
                    .retestTime(
                            retest.getMinute()
                    )
                    .confirmationTime(
                            confirmation.getMinute()
                    )
                    .reason(
                            "BEARISH_BREAKOUT_RETEST_CONFIRMATION"
                    )
                    .build();
        }

        return rejected(
                openingRange.getSymbol(),
                "NO_VALID_BEARISH_ORB"
        );
    }


    /**
     * Average volume of the 15 opening-range candles.
     */
    private double calculateOpeningRangeAverageVolume(
            String symbol) {

        LocalDate today =
                LocalDate.now(IST);

        LocalDateTime start =
                LocalDateTime.of(
                        today,
                        LocalTime.of(9, 15)
                );

        LocalDateTime end =
                LocalDateTime.of(
                        today,
                        LocalTime.of(9, 30)
                );

        List<MinuteCandle> candles =
                minuteCandleStore.getBetween(
                        symbol,
                        start,
                        end
                );

        if (candles.isEmpty()) {
            return 0.0;
        }

        return candles.stream()
                .filter(this::validCandle)
                .map(MinuteCandle::getVolume)
                .filter(volume -> volume != null)
                .mapToDouble(Long::doubleValue)
                .average()
                .orElse(0.0);
    }


    private double calculateVolumeRatio(
            MinuteCandle candle,
            double averageVolume) {

        if (candle == null
                || candle.getVolume() == null
                || averageVolume <= 0) {

            return 0.0;
        }

        return candle.getVolume()
                / averageVolume;
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


    private ORBResult rejected(
            String symbol,
            String reason) {

        return ORBResult.builder()
                .symbol(symbol)
                .valid(false)
                .direction("NONE")
                .breakoutSide("NONE")
                .reason(reason)
                .build();
    }
}