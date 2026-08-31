package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.EntryQualityResult;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class EntryQualityService {

    /*
     * ============================================================
     * IMPORTANT
     *
     * These are NOT trade rejection thresholds.
     *
     * They are classification thresholds only.
     *
     * We will validate them from paper-trading data before using
     * them as actual trade guards.
     * ============================================================
     */

    private static final double DIRECTIONAL_MOVE_CAUTION_PCT = 0.50;

    private static final double DIRECTIONAL_MOVE_EXTENDED_PCT = 1.00;

    private static final double EMA_DISTANCE_OBSERVATION_PCT = 3.00;


    public EntryQualityResult evaluate(
            String symbol,
            SignalType direction,
            Double signalPrice,
            Double freshMarketPrice,
            TechnicalIndicatorResponse indicator) {

        /*
         * ============================================================
         * VALIDATION
         * ============================================================
         */

        if (symbol == null || symbol.isBlank()) {

            return buildUnavailableResult(
                    symbol,
                    direction,
                    signalPrice,
                    freshMarketPrice,
                    "Symbol unavailable");
        }

        if (direction == null
                || SignalType.HOLD.equals(direction)) {

            return buildUnavailableResult(
                    symbol,
                    direction,
                    signalPrice,
                    freshMarketPrice,
                    "Direction is HOLD");
        }

        if (signalPrice == null
                || signalPrice <= 0
                || freshMarketPrice == null
                || freshMarketPrice <= 0) {

            return buildUnavailableResult(
                    symbol,
                    direction,
                    signalPrice,
                    freshMarketPrice,
                    "Invalid signal/fresh market price");
        }


        /*
         * ============================================================
         * 1. DIRECTIONAL MOVEMENT
         *
         * BUY:
         * fresh price higher than signal price = move already happened
         *
         * SELL:
         * fresh price lower than signal price = move already happened
         * ============================================================
         */

        double directionalMovementPct;

        if (SignalType.BUY.equals(direction)) {

            directionalMovementPct =
                    ((freshMarketPrice - signalPrice)
                            / signalPrice)
                            * 100.0;

        } else if (SignalType.SELL.equals(direction)) {

            directionalMovementPct =
                    ((signalPrice - freshMarketPrice)
                            / signalPrice)
                            * 100.0;

        } else {

            directionalMovementPct = 0.0;
        }


        /*
         * ============================================================
         * 2. PRICE VS EMA20
         * ============================================================
         */

        Double priceVsEma20Pct =
                calculatePercentageDifference(
                        freshMarketPrice,
                        indicator != null
                                ? indicator.getEma20()
                                : null);


        /*
         * ============================================================
         * 3. PRICE VS EMA50
         * ============================================================
         */

        Double priceVsEma50Pct =
                calculatePercentageDifference(
                        freshMarketPrice,
                        indicator != null
                                ? indicator.getEma50()
                                : null);


        /*
         * ============================================================
         * 4. PRICE VS LATEST HISTORICAL CLOSE
         *
         * This is especially useful because our current indicator
         * engine is based on the historical-price dataset.
         * ============================================================
         */

        Double priceVsHistoricalClosePct =
                calculatePercentageDifference(
                        freshMarketPrice,
                        indicator != null
                                ? indicator.getClosePrice()
                                : null);


        /*
         * ============================================================
         * 5. CLASSIFY ENTRY
         *
         * MEASUREMENT ONLY.
         * ============================================================
         */

        String status;

        String reason;


        if (Math.abs(directionalMovementPct)
                >= DIRECTIONAL_MOVE_EXTENDED_PCT) {

            status = "EXTENDED";

            reason =
                    "Price has already moved materially in "
                            + "the intended direction since signal.";

        } else if (Math.abs(directionalMovementPct)
                >= DIRECTIONAL_MOVE_CAUTION_PCT) {

            status = "CAUTION";

            reason =
                    "Price has moved in the intended direction "
                            + "since signal; entry should be monitored.";

        } else {

            status = "HEALTHY";

            reason =
                    "Fresh price is close to the original signal price.";
        }


        /*
         * ============================================================
         * 6. ADD EMA CONTEXT TO THE DIAGNOSTIC
         * ============================================================
         */

        if (priceVsEma20Pct != null
                && Math.abs(priceVsEma20Pct)
                >= EMA_DISTANCE_OBSERVATION_PCT) {

            reason +=
                    " Price is significantly displaced from EMA20.";
        }


        /*
         * ============================================================
         * 7. LOG COMPLETE ENTRY DIAGNOSTIC
         * ============================================================
         */

        log.info(
                "ENTRY QUALITY | " +
                "Symbol={} | " +
                "Direction={} | " +
                "SignalPrice={} | " +
                "FreshPrice={} | " +
                "DirectionalMove={}%" +
                " | PriceVsEMA20={}%" +
                " | PriceVsEMA50={}%" +
                " | PriceVsHistoricalClose={}%" +
                " | Status={} | " +
                "Reason={}",
                symbol,
                direction,
                signalPrice,
                freshMarketPrice,
                round(directionalMovementPct),
                round(priceVsEma20Pct),
                round(priceVsEma50Pct),
                round(priceVsHistoricalClosePct),
                status,
                reason);


        return EntryQualityResult.builder()
                .symbol(symbol)
                .direction(direction.name())
                .signalPrice(round(signalPrice))
                .freshMarketPrice(round(freshMarketPrice))
                .directionalMovementPct(
                        round(directionalMovementPct))
                .priceVsEma20Pct(
                        round(priceVsEma20Pct))
                .priceVsEma50Pct(
                        round(priceVsEma50Pct))
                .priceVsHistoricalClosePct(
                        round(priceVsHistoricalClosePct))
                .status(status)
                .reason(reason)
                .build();
    }


    private Double calculatePercentageDifference(
            Double currentPrice,
            Double referencePrice) {

        if (currentPrice == null
                || currentPrice <= 0
                || referencePrice == null
                || referencePrice <= 0) {

            return null;
        }

        return ((currentPrice - referencePrice)
                / referencePrice)
                * 100.0;
    }


    private EntryQualityResult buildUnavailableResult(
            String symbol,
            SignalType direction,
            Double signalPrice,
            Double freshMarketPrice,
            String reason) {

        log.warn(
                "ENTRY QUALITY | " +
                "Unavailable | Symbol={} | " +
                "Direction={} | SignalPrice={} | " +
                "FreshPrice={} | Reason={}",
                symbol,
                direction,
                signalPrice,
                freshMarketPrice,
                reason);

        return EntryQualityResult.builder()
                .symbol(symbol)
                .direction(
                        direction != null
                                ? direction.name()
                                : null)
                .signalPrice(round(signalPrice))
                .freshMarketPrice(round(freshMarketPrice))
                .status("UNAVAILABLE")
                .reason(reason)
                .build();
    }


    private Double round(Double value) {

        if (value == null) {
            return null;
        }

        return Math.round(value * 100.0) / 100.0;
    }
}