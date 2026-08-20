package com.ram.trading.watchlist.service.impl;

import com.ram.trading.watchlist.dto.TechnicalCandidate;
import com.ram.trading.watchlist.dto.TechnicalIndicatorResponse;
import com.ram.trading.watchlist.service.TechnicalScoringService;
import org.springframework.stereotype.Service;

@Service
public class TechnicalScoringServiceImpl
        implements TechnicalScoringService {

    @Override
    public TechnicalCandidate score(
            TechnicalIndicatorResponse indicator,
            String companyName) {

        double score = 0.0;

        /*
         * ==========================================
         * 1. EMA TREND - 25 POINTS
         * ==========================================
         */

        if (isGreater(
                indicator.getEma20(),
                indicator.getEma50())) {

            score += 15;
        }

        if (isGreater(
                indicator.getClosePrice(),
                indicator.getEma20())) {

            score += 10;
        }

        /*
         * ==========================================
         * 2. MACD MOMENTUM - 25 POINTS
         * ==========================================
         */

        if (isGreater(
                indicator.getMacd(),
                indicator.getSignalLine())) {

            score += 15;
        }

        if (isMacdImproving(indicator)) {

            score += 10;
        }

        /*
         * ==========================================
         * 3. RSI QUALITY - 20 POINTS
         * ==========================================
         */

        Double rsi = indicator.getRsi14();

        if (rsi != null) {

            if (rsi >= 45 && rsi <= 70) {

                score += 20;

            } else if ((rsi >= 40 && rsi < 45)
                    || (rsi > 70 && rsi <= 75)) {

                score += 10;
            }
        }

        /*
         * ==========================================
         * 4. SMA STRUCTURE - 15 POINTS
         * ==========================================
         */

        if (isGreater(
                indicator.getSma20(),
                indicator.getSma50())) {

            score += 15;
        }

        /*
         * ==========================================
         * 5. PRICE POSITION - 15 POINTS
         * ==========================================
         */

        if (isGreater(
                indicator.getClosePrice(),
                indicator.getSma20())) {

            score += 15;
        }

        return TechnicalCandidate.builder()
                .symbol(indicator.getSymbol())
                .companyName(companyName)
                .technicalScore(score)
                .closePrice(indicator.getClosePrice())
                .rsi14(indicator.getRsi14())
                .ema20(indicator.getEma20())
                .ema50(indicator.getEma50())
                .sma20(indicator.getSma20())
                .sma50(indicator.getSma50())
                .macd(indicator.getMacd())
                .signalLine(indicator.getSignalLine())
                .previousMacd(indicator.getPreviousMacd())
                .previousSignalLine(
                        indicator.getPreviousSignalLine())
                .build();
    }

    private boolean isGreater(
            Double value1,
            Double value2) {

        return value1 != null
                && value2 != null
                && value1 > value2;
    }

    private boolean isMacdImproving(
            TechnicalIndicatorResponse indicator) {

        if (indicator.getMacd() == null
                || indicator.getSignalLine() == null
                || indicator.getPreviousMacd() == null
                || indicator.getPreviousSignalLine() == null) {

            return false;
        }

        double currentDifference =
                indicator.getMacd()
                        - indicator.getSignalLine();

        double previousDifference =
                indicator.getPreviousMacd()
                        - indicator.getPreviousSignalLine();

        return currentDifference > previousDifference;
    }
}