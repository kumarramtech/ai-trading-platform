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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class JudasSwingDetector {

    private static final ZoneId IST =
            ZoneId.of("Asia/Kolkata");

    private static final LocalTime ANALYSIS_START =
            LocalTime.of(9, 30);

    private static final LocalTime ANALYSIS_END =
            LocalTime.of(10, 30);

    /*
     * Minimum distance price must penetrate the OR level.
     *
     * Example:
     *
     * OR High = 100
     * Minimum sweep depth = 0.10%
     * Required high >= 100.10
     */
    private static final double MIN_SWEEP_DEPTH_PERCENT = 0.10;

    /*
     * Rejection wick must be meaningful relative to candle body.
     *
     * Bearish:
     * upper wick >= body
     *
     * Bullish:
     * lower wick >= body
     */
    private static final double MIN_REJECTION_WICK_BODY_RATIO = 1.0;

    /*
     * Confirmation candle volume must be at least 1.2x
     * today's opening-range average volume.
     */
    private static final double MIN_CONFIRMATION_VOLUME_RATIO = 1.20;

    private final MinuteCandleStore minuteCandleStore;


    /**
     * Detect a confirmed Judas Swing after the
     * 09:15-09:30 opening range; selection runs from 09:30 to 10:30 IST.
     *
     * This method does NOT generate the final trading signal.
     */
    public JudasSwingResult detect(
            OpeningRange openingRange) {
        return detect(openingRange, ANALYSIS_START);
    }

    /**
     * Detect only setups whose confirmation candle is at or after the
     * current completed candle. This prevents a historical setup from
     * being emitted as a fresh entry several minutes later.
     */
    public JudasSwingResult detect(
            OpeningRange openingRange,
            LocalTime earliestConfirmationTime) {

        LocalTime eligibleConfirmationTime =
                earliestConfirmationTime != null
                        ? earliestConfirmationTime
                        : ANALYSIS_START;

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
         * HIGH SWEEP
         * --------------------------------------------------------
         *
         * Expected structure:
         *
         * Price trades above OR High
         *       ↓
         * Meaningful penetration
         *       ↓
         * Strong rejection
         *       ↓
         * Close below OR High
         *       ↓
         * Confirmation candle continues lower
         */
        JudasSwingResult bearish =
                detectHighSweep(
                        openingRange,
                        candles,
                        eligibleConfirmationTime
                );

        if (bearish.isDetected()) {

            return bearish;
        }

        /*
         * --------------------------------------------------------
         * LOW SWEEP
         * --------------------------------------------------------
         *
         * Expected structure:
         *
         * Price trades below OR Low
         *       ↓
         * Meaningful penetration
         *       ↓
         * Strong rejection
         *       ↓
         * Close above OR Low
         *       ↓
         * Confirmation candle continues higher
         */
        JudasSwingResult bullish =
                detectLowSweep(
                        openingRange,
                        candles,
                        eligibleConfirmationTime
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
     * Bearish Judas:
     *
     * Sweep OR High
     * + rejection
     * + close below OR High
     * + next candle closes lower
     * + confirmation volume
     */
    private JudasSwingResult detectHighSweep(
            OpeningRange openingRange,
            List<MinuteCandle> candles,
            LocalTime earliestConfirmationTime) {

        double rangeHigh =
                openingRange.getHigh();

        double minimumSweepDepth =
                rangeHigh
                        * MIN_SWEEP_DEPTH_PERCENT
                        / 100.0;

        double averageVolume =
                calculateOpeningRangeAverageVolume(
                        openingRange.getSymbol()
                );

        for (int i = 0; i < candles.size() - 1; i++) {

            MinuteCandle sweep =
                    candles.get(i);

            MinuteCandle confirmation =
                    candles.get(i + 1);

            if (!validCandle(sweep)
                    || !validCandle(confirmation)) {

                continue;
            }
 
            LocalTime confirmationTime =
                    confirmation.getMinute().toLocalTime();

            if (confirmationTime.isBefore(earliestConfirmationTime)
                    || !confirmationTime.isBefore(ANALYSIS_END)) {
                continue;
            }

            /*
             * STEP 1
             *
             * Price must meaningfully trade above OR High.
             */
            double sweepDepth =
                    sweep.getHigh() - rangeHigh;

            if (sweepDepth < minimumSweepDepth) {

                continue;
            }

            /*
             * STEP 2
             *
             * Sweep candle must close back below OR High.
             */
            if (sweep.getClose() >= rangeHigh) {

                continue;
            }

            /*
             * STEP 3
             *
             * Strong upper rejection wick.
             */
            double body =
                    Math.abs(
                            sweep.getClose()
                                    - sweep.getOpen()
                    );

            double upperWick =
                    sweep.getHigh()
                            - Math.max(
                            sweep.getOpen(),
                            sweep.getClose()
                    );

            if (!hasStrongRejection(
                    upperWick,
                    body)) {

                continue;
            }

            /*
             * STEP 4
             *
             * Confirmation must continue in bearish direction.
             */
            if (confirmation.getClose()
                    >= sweep.getClose()) {

                continue;
            }

            /*
             * STEP 5
             *
             * Confirmation must remain below OR High.
             */
            if (confirmation.getClose()
                    >= rangeHigh) {

                continue;
            }

            /*
             * STEP 6
             *
             * Confirmation volume.
             */
            double volumeRatio =
                    calculateVolumeRatio(
                            confirmation,
                            averageVolume
                    );

            if (volumeRatio
                    < MIN_CONFIRMATION_VOLUME_RATIO) {

                continue;
            }

            log.info(
                    "JUDAS HIGH SWEEP CONFIRMED | " +
                            "Symbol={} | Sweep={} | ORHigh={} | " +
                            "SweepDepth={} | UpperWick={} | Body={} | " +
                            "Confirmation={} | VolumeRatio={}",
                    openingRange.getSymbol(),
                    sweep.getHigh(),
                    rangeHigh,
                    round(
                            sweepDepth
                    ),
                    round(
                            upperWick
                    ),
                    round(
                            body
                    ),
                    confirmation.getClose(),
                    round(
                            volumeRatio
                    )
            );

            return buildResult(
                    openingRange,
                    "BEARISH_REVERSAL",
                    "HIGH_SWEEP",
                    sweep,
                    confirmation,
                    "HIGH_SWEEP_REJECTION_AND_BEARISH_FOLLOW_THROUGH"
            );
        }

        return rejected(
                openingRange.getSymbol(),
                "NO_CONFIRMED_HIGH_SWEEP"
        );
    }


    /**
     * Bullish Judas:
     *
     * Sweep OR Low
     * + rejection
     * + close above OR Low
     * + next candle closes higher
     * + confirmation volume
     */
    private JudasSwingResult detectLowSweep(
            OpeningRange openingRange,
            List<MinuteCandle> candles,
            LocalTime earliestConfirmationTime) {

        double rangeLow =
                openingRange.getLow();

        double minimumSweepDepth =
                rangeLow
                        * MIN_SWEEP_DEPTH_PERCENT
                        / 100.0;

        double averageVolume =
                calculateOpeningRangeAverageVolume(
                        openingRange.getSymbol()
                );

        for (int i = 0; i < candles.size() - 1; i++) {

            MinuteCandle sweep =
                    candles.get(i);

            MinuteCandle confirmation =
                    candles.get(i + 1);

            if (!validCandle(sweep)
                    || !validCandle(confirmation)) {

                continue;
            }
 
            LocalTime confirmationTime =
                    confirmation.getMinute().toLocalTime();

            if (confirmationTime.isBefore(earliestConfirmationTime)
                    || !confirmationTime.isBefore(ANALYSIS_END)) {
                continue;
            }

            /*
             * STEP 1
             *
             * Price must meaningfully trade below OR Low.
             */
            double sweepDepth =
                    rangeLow - sweep.getLow();

            if (sweepDepth < minimumSweepDepth) {

                continue;
            }

            /*
             * STEP 2
             *
             * Sweep candle must close back above OR Low.
             */
            if (sweep.getClose() <= rangeLow) {

                continue;
            }

            /*
             * STEP 3
             *
             * Strong lower rejection wick.
             */
            double body =
                    Math.abs(
                            sweep.getClose()
                                    - sweep.getOpen()
                    );

            double lowerWick =
                    Math.min(
                            sweep.getOpen(),
                            sweep.getClose()
                    )
                            - sweep.getLow();

            if (!hasStrongRejection(
                    lowerWick,
                    body)) {

                continue;
            }

            /*
             * STEP 4
             *
             * Confirmation must continue bullish.
             */
            if (confirmation.getClose()
                    <= sweep.getClose()) {

                continue;
            }

            /*
             * STEP 5
             *
             * Confirmation remains above OR Low.
             */
            if (confirmation.getClose()
                    <= rangeLow) {

                continue;
            }

            /*
             * STEP 6
             *
             * Confirmation volume.
             */
            double volumeRatio =
                    calculateVolumeRatio(
                            confirmation,
                            averageVolume
                    );

            if (volumeRatio
                    < MIN_CONFIRMATION_VOLUME_RATIO) {

                continue;
            }

            log.info(
                    "JUDAS LOW SWEEP CONFIRMED | " +
                            "Symbol={} | Sweep={} | ORLow={} | " +
                            "SweepDepth={} | LowerWick={} | Body={} | " +
                            "Confirmation={} | VolumeRatio={}",
                    openingRange.getSymbol(),
                    sweep.getLow(),
                    rangeLow,
                    round(
                            sweepDepth
                    ),
                    round(
                            lowerWick
                    ),
                    round(
                            body
                    ),
                    confirmation.getClose(),
                    round(
                            volumeRatio
                    )
            );

            return buildResult(
                    openingRange,
                    "BULLISH_REVERSAL",
                    "LOW_SWEEP",
                    sweep,
                    confirmation,
                    "LOW_SWEEP_REJECTION_AND_BULLISH_FOLLOW_THROUGH"
            );
        }

        return rejected(
                openingRange.getSymbol(),
                "NO_CONFIRMED_LOW_SWEEP"
        );
    }


    private boolean hasStrongRejection(
            double wick,
            double body) {

        /*
         * Do not reject a valid rejection candle
         * just because its body is extremely small.
         */
        if (wick <= 0) {
            return false;
        }

        if (body <= 0) {
            return true;
        }

        return wick / body
                >= MIN_REJECTION_WICK_BODY_RATIO;
    }


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
                .mapToDouble(
                        Long::doubleValue
                )
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


    private JudasSwingResult buildResult(
            OpeningRange openingRange,
            String direction,
            String sweepSide,
            MinuteCandle sweep,
            MinuteCandle confirmation,
            String reason) {

        double averageVolume =
                calculateOpeningRangeAverageVolume(
                        openingRange.getSymbol()
                );

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
                        averageVolume
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


    private boolean validCandle(
            MinuteCandle candle) {

        return candle != null
                && candle.getMinute() != null
                && candle.getOpen() != null
                && candle.getHigh() != null
                && candle.getLow() != null
                && candle.getClose() != null;
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


    private Double toDouble(
            Long value) {

        return value != null
                ? value.doubleValue()
                : null;
    }


    private double round(
            double value) {

        return Math.round(
                value * 10000.0
        ) / 10000.0;
    }
}