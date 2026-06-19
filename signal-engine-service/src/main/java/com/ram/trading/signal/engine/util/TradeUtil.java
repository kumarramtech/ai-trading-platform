package com.ram.trading.signal.engine.util;

import com.ram.trading.signal.engine.contant.Recommendation;
import com.ram.trading.signal.engine.entity.PaperTrade;

import java.util.List;

public class TradeUtil {

    public static Recommendation getRecommendation(String signal, Integer score) {

        if ("SELL".equalsIgnoreCase(signal)) {

            if (score >= 150) {
                return Recommendation.STRONG_SELL;
            }

            return Recommendation.SELL;
        }

        if ("BUY".equalsIgnoreCase(signal)) {

            if (score >= 150) {
                return Recommendation.STRONG_BUY;
            }

            if (score >= 120) {
                return Recommendation.BUY;
            }

            if (score >= 80) {
                return Recommendation.WATCH;
            }
        }

        return Recommendation.AVOID;
    }

    public static long calculateConsecutiveLosses(
            List<PaperTrade> trades) {

        long current = 0;

        long max = 0;

        for (PaperTrade trade : trades) {

            if (trade.getProfitLoss() < 0) {

                current++;

                max = Math.max(max, current);

            } else {

                current = 0;
            }
        }

        return max;
    }

    public static long calculateConsecutiveWins(
            List<PaperTrade> trades) {

        long current = 0;

        long max = 0;

        for (PaperTrade trade : trades) {

            if (trade.getProfitLoss() > 0) {

                current++;

                max = Math.max(max, current);

            } else {

                current = 0;
            }
        }

        return max;
    }
    public static double calculateMaxDrawdown(
            List<PaperTrade> trades) {

        double equity = 0;

        double peak = 0;

        double maxDrawdown = 0;

        for (PaperTrade trade : trades) {

            equity += trade.getProfitLoss();

            if (equity > peak) {
                peak = equity;
            }

            double drawdown =
                    peak - equity;

            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown;
            }
        }

        return maxDrawdown;
    }

    public static double getAllocationPercentage(Integer confidence) {
        if (confidence >= 80) {
            return 0.20;
        }
        if (confidence >= 70) {
            return 0.15;
        }
        if (confidence >= 50) {
            return 0.10;
        }
        return 0.05;
    }
    public static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

}
