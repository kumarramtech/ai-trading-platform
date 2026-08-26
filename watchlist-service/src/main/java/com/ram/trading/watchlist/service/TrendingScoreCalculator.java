package com.ram.trading.watchlist.service;

import com.ram.trading.watchlist.dto.TrendingMarketSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TrendingScoreCalculator {

    /**
     * Calculates the overall trending score from 0 to 100.
     *
     * Components:
     *
     * Price Momentum    : 25%
     * Volume Activity   : 25%
     * Price Change      : 20%
     * Trend Strength    : 15%
     * Intraday Range    : 10%
     * Market Activity   :  5%
     */
    public double calculateScore(
            TrendingMarketSnapshot current,
            TrendingMarketSnapshot previous) {

        if (current == null) {
            return 0.0;
        }

        double momentumScore =
                calculateMomentumScore(current, previous);

        double volumeScore =
                calculateVolumeScore(current, previous);

        double priceChangeScore =
                calculatePriceChangeScore(current);

        double trendStrengthScore =
                calculateTrendStrengthScore(current);

        double rangeScore =
                calculateRangeScore(current);

        double activityScore =
                calculateActivityScore(current);

        double score =
                (momentumScore * 0.25)
                        + (volumeScore * 0.25)
                        + (priceChangeScore * 0.20)
                        + (trendStrengthScore * 0.15)
                        + (rangeScore * 0.10)
                        + (activityScore * 0.05);

        score = Math.max(0.0, Math.min(100.0, score));

        log.debug(
                "Trending Score | Symbol={} | Momentum={} | Volume={} | " +
                "PriceChange={} | Trend={} | Range={} | Activity={} | Final={}",
                current.getTradingSymbol(),
                momentumScore,
                volumeScore,
                priceChangeScore,
                trendStrengthScore,
                rangeScore,
                activityScore,
                score);

        return Math.round(score * 100.0) / 100.0;
    }

    /**
     * Measures price movement between the previous and current snapshot.
     */
    private double calculateMomentumScore(
            TrendingMarketSnapshot current,
            TrendingMarketSnapshot previous) {

        if (previous == null
                || previous.getLastPrice() == null
                || current.getLastPrice() == null
                || previous.getLastPrice() <= 0) {

            return 0.0;
        }

        double priceChange =
                ((current.getLastPrice()
                        - previous.getLastPrice())
                        / previous.getLastPrice())
                        * 100.0;

        double absoluteChange = Math.abs(priceChange);

        /*
         * 1% movement between snapshots = 50 score
         * 2% or more = 100 score
         */
        return normalize(
                absoluteChange,
                0.0,
                2.0);
    }

    /**
     * Measures increase in traded volume between snapshots.
     */
    private double calculateVolumeScore(
            TrendingMarketSnapshot current,
            TrendingMarketSnapshot previous) {

        if (current.getVolume() == null
                || current.getVolume() <= 0) {

            return 0.0;
        }

        /*
         * Without a previous snapshot we cannot determine
         * volume expansion reliably.
         */
        if (previous == null
                || previous.getVolume() == null
                || previous.getVolume() <= 0) {

            return 25.0;
        }

        double volumeRatio =
                (double) current.getVolume()
                        / previous.getVolume();

        /*
         * 1x  -> 0
         * 2x  -> 50
         * 3x+ -> 100
         */
        return normalize(
                volumeRatio,
                1.0,
                3.0);
    }

    /**
     * Scores today's percentage price movement.
     */
    private double calculatePriceChangeScore(
            TrendingMarketSnapshot current) {

        if (current.getChangePercentage() == null) {
            return 0.0;
        }

        double absoluteChange =
                Math.abs(current.getChangePercentage());

        /*
         * 0%  -> 0
         * 2%  -> 50
         * 4%+ -> 100
         */
        return normalize(
                absoluteChange,
                0.0,
                4.0);
    }

    /**
     * Measures whether price is moving consistently inside
     * the current trading range.
     */
    private double calculateTrendStrengthScore(
            TrendingMarketSnapshot current) {

        if (current.getLastPrice() == null
                || current.getAveragePrice() == null
                || current.getAveragePrice() <= 0) {

            return 0.0;
        }

        double deviation =
                Math.abs(
                        (current.getLastPrice()
                                - current.getAveragePrice())
                                / current.getAveragePrice())
                        * 100.0;

        /*
         * 0% deviation  -> 0
         * 2% deviation  -> 100
         */
        return normalize(
                deviation,
                0.0,
                2.0);
    }

    /**
     * Measures how much of the day's range is currently active.
     */
    private double calculateRangeScore(
            TrendingMarketSnapshot current) {

        if (current.getHigh() == null
                || current.getLow() == null
                || current.getLastPrice() == null
                || current.getHigh() <= current.getLow()) {

            return 0.0;
        }

        double range =
                current.getHigh() - current.getLow();

        if (range <= 0) {
            return 0.0;
        }

        double position =
                (current.getLastPrice()
                        - current.getLow())
                        / range;

        /*
         * We care about strong movement toward either side
         * of the intraday range.
         */
        double distanceFromMiddle =
                Math.abs(position - 0.5) * 2.0;

        return Math.max(
                0.0,
                Math.min(100.0,
                        distanceFromMiddle * 100.0));
    }

    /**
     * Measures general market activity using volume.
     */
    private double calculateActivityScore(
            TrendingMarketSnapshot current) {

        if (current.getVolume() == null
                || current.getVolume() <= 0) {

            return 0.0;
        }

        /*
         * Logarithmic scaling prevents very high-volume stocks
         * from completely dominating the score.
         */
        double score =
                Math.log10(
                        current.getVolume() + 1)
                        * 12.0;

        return Math.max(
                0.0,
                Math.min(100.0, score));
    }

    /**
     * Converts a value into a 0-100 score.
     */
    private double normalize(
            double value,
            double minimum,
            double maximum) {

        if (value <= minimum) {
            return 0.0;
        }

        if (value >= maximum) {
            return 100.0;
        }

        return ((value - minimum)
                / (maximum - minimum))
                * 100.0;
    }
}