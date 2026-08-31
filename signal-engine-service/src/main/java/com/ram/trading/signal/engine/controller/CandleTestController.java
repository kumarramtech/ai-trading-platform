package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.dto.premarket.JudasSwingResult;
import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.dto.premarket.ORBResult;
import com.ram.trading.signal.engine.dto.premarket.OpeningRange;
import com.ram.trading.signal.engine.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api/test/candles")
@RequiredArgsConstructor
public class CandleTestController {

    private static final ZoneId IST =
            ZoneId.of("Asia/Kolkata");

    private final MinuteCandleAggregator minuteCandleAggregator;

    private final MinuteCandleStore minuteCandleStore;

    private final OpeningRangeService openingRangeService;

    private final JudasSwingDetector judasSwingDetector;

    private final ORBValidator orbValidator;


    /**
     * ---------------------------------------------------------
     * Add a single test tick
     * ---------------------------------------------------------
     */
    @PostMapping("/tick")
    public String addTick(
            @RequestBody Tick tick) {

        minuteCandleAggregator.process(tick);

        return "Tick processed successfully";
    }


    /**
     * ---------------------------------------------------------
     * Get today's candles
     * ---------------------------------------------------------
     */
    @GetMapping("/{symbol}")
    public List<MinuteCandle> getTodayCandles(
            @PathVariable String symbol) {

        return minuteCandleStore.getToday(symbol);
    }

    @GetMapping("/{symbol}/orb")
    public ORBResult validateORB(
            @PathVariable String symbol) {

        OpeningRange openingRange =
                openingRangeService.calculate(symbol);

        return orbValidator.validate(
                openingRange
        );
    }

    @PostMapping("/generate-orb/{symbol}")
    public String generateORB(
            @PathVariable String symbol) {

        OpeningRange openingRange =
                openingRangeService.calculate(symbol);

        if (openingRange == null
                || !openingRange.isComplete()) {

            return "Opening range is unavailable. "
                    + "Generate opening range first.";
        }

        double rangeHigh =
                openingRange.getHigh();

        LocalDate today =
                LocalDate.now(IST);

        /*
         * Average opening-range volume in our
         * current synthetic data is:
         *
         * 1000 + 1100 + ... + 2400
         *
         * Average = 1700
         *
         * ORB confirmation volume needs >= 1.2x
         * average = 2040.
         *
         * We use 3000.
         */


        /*
         * =========================================================
         * 09:31 — BREAKOUT
         * =========================================================
         *
         * Close clearly above OR High.
         */
        LocalDateTime breakoutTime =
                LocalDateTime.of(
                        today,
                        LocalTime.of(9, 31)
                );

        saveSyntheticCandle(
                symbol,
                breakoutTime,
                rangeHigh + 1.0,
                rangeHigh + 8.0,
                rangeHigh - 1.0,
                rangeHigh + 5.0,
                2200L
        );


        /*
         * =========================================================
         * 09:32 — RETEST
         * =========================================================
         *
         * Low comes back to the OR High,
         * but candle closes above it.
         */
        LocalDateTime retestTime =
                LocalDateTime.of(
                        today,
                        LocalTime.of(9, 32)
                );

        saveSyntheticCandle(
                symbol,
                retestTime,
                rangeHigh + 4.0,
                rangeHigh + 7.0,
                rangeHigh,
                rangeHigh + 3.0,
                1800L
        );


        /*
         * =========================================================
         * 09:33 — CONFIRMATION
         * =========================================================
         *
         * Closes above OR High with strong volume.
         */
        LocalDateTime confirmationTime =
                LocalDateTime.of(
                        today,
                        LocalTime.of(9, 33)
                );

        saveSyntheticCandle(
                symbol,
                confirmationTime,
                rangeHigh + 3.0,
                rangeHigh + 12.0,
                rangeHigh + 2.0,
                rangeHigh + 10.0,
                3000L
        );

        return "Bullish ORB candles generated for "
                + symbol;
    }


    /**
     * ---------------------------------------------------------
     * Get candles between two timestamps
     * ---------------------------------------------------------
     */
    @GetMapping("/{symbol}/between")
    public List<MinuteCandle> getBetween(
            @PathVariable String symbol,
            @RequestParam LocalDateTime from,
            @RequestParam LocalDateTime to) {

        return minuteCandleStore.getBetween(
                symbol,
                from,
                to
        );
    }


    /**
     * ---------------------------------------------------------
     * Generate synthetic 09:15 - 09:29 candles
     * ---------------------------------------------------------
     *
     * Creates exactly 15 consecutive one-minute candles.
     *
     * This is ONLY for testing.
     */
    @PostMapping("/generate-opening-range/{symbol}")
    public String generateOpeningRange(
            @PathVariable String symbol) {

        LocalDate today =
                LocalDate.now(IST);

        LocalDateTime start =
                LocalDateTime.of(
                        today,
                        LocalTime.of(9, 15)
                );

        double basePrice = 4000.0;

        for (int i = 0; i < 15; i++) {

            LocalDateTime minute =
                    start.plusMinutes(i);

            double open =
                    basePrice + (i * 1.0);

            double high =
                    open + 5.0;

            double low =
                    open - 5.0;

            double close =
                    open + 2.0;

            long volume =
                    1000L + (i * 100L);

            saveSyntheticCandle(
                    symbol,
                    minute,
                    open,
                    high,
                    low,
                    close,
                    volume
            );
        }

        return "15 synthetic opening candles generated for "
                + symbol;
    }


    /**
     * ---------------------------------------------------------
     * Generate bullish Judas Swing
     * ---------------------------------------------------------
     *
     * Assumes the opening range has already been generated.
     *
     * Example:
     *
     * OR Low = 3995
     *
     * 09:31
     * Low   < OR Low
     * Close > OR Low
     *
     * 09:32
     * Close > OR Low
     */
    @PostMapping("/generate-judas/{symbol}")
    public String generateJudas(
            @PathVariable String symbol) {

        OpeningRange openingRange =
                openingRangeService.calculate(symbol);

        if (openingRange == null
                || !openingRange.isComplete()) {

            return "Opening range is unavailable. "
                    + "Generate opening range first.";
        }

        double rangeLow =
                openingRange.getLow();

        LocalDate today =
                LocalDate.now(IST);

        /*
         * -----------------------------------------------------
         * Sweep candle - 09:31
         * -----------------------------------------------------
         */
        LocalDateTime sweepTime =
                LocalDateTime.of(
                        today,
                        LocalTime.of(9, 31)
                );

        double sweepOpen =
                rangeLow + 2.0;

        double sweepLow =
                rangeLow - 5.0;

        double sweepHigh =
                rangeLow + 4.0;

        double sweepClose =
                rangeLow + 3.0;

        saveSyntheticCandle(
                symbol,
                sweepTime,
                sweepOpen,
                sweepHigh,
                sweepLow,
                sweepClose,
                2500L
        );


        /*
         * -----------------------------------------------------
         * Confirmation candle - 09:32
         * -----------------------------------------------------
         */
        LocalDateTime confirmationTime =
                LocalDateTime.of(
                        today,
                        LocalTime.of(9, 32)
                );

        double confirmationOpen =
                rangeLow + 3.0;

        double confirmationHigh =
                rangeLow + 10.0;

        double confirmationLow =
                rangeLow + 1.0;

        double confirmationClose =
                rangeLow + 8.0;

        saveSyntheticCandle(
                symbol,
                confirmationTime,
                confirmationOpen,
                confirmationHigh,
                confirmationLow,
                confirmationClose,
                3000L
        );

        return "Bullish Judas Swing candles generated for "
                + symbol;
    }


    /**
     * ---------------------------------------------------------
     * Calculate opening range
     * ---------------------------------------------------------
     */
    @GetMapping("/{symbol}/opening-range")
    public OpeningRange getOpeningRange(
            @PathVariable String symbol) {

        return openingRangeService.calculate(symbol);
    }


    /**
     * ---------------------------------------------------------
     * Detect Judas Swing
     * ---------------------------------------------------------
     */
    @GetMapping("/{symbol}/judas")
    public JudasSwingResult detectJudas(
            @PathVariable String symbol) {

        OpeningRange openingRange =
                openingRangeService.calculate(symbol);

        return judasSwingDetector.detect(
                openingRange
        );
    }


    /**
     * ---------------------------------------------------------
     * Clear test data
     * ---------------------------------------------------------
     */
    @DeleteMapping
    public String clearCandles() {

        minuteCandleStore.clearAll();

        openingRangeService.clear();

        return "Candle and opening-range stores cleared";
    }


    /**
     * ---------------------------------------------------------
     * Save synthetic candle directly into the store
     * ---------------------------------------------------------
     */
    private void saveSyntheticCandle(
            String symbol,
            LocalDateTime minute,
            double open,
            double high,
            double low,
            double close,
            long volume) {

        MinuteCandle candle =
                MinuteCandle.builder()
                        .symbol(symbol)
                        .minute(minute)
                        .open(open)
                        .high(high)
                        .low(low)
                        .close(close)
                        .volume(volume)
                        .build();

        minuteCandleStore.save(candle);
    }
}