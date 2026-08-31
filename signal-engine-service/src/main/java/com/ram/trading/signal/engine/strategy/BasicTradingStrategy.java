package com.ram.trading.signal.engine.strategy;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.contant.SignalWeights;
import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.indicator.service.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
@Slf4j
public class BasicTradingStrategy
        implements TradingStrategy {

    private final TechnicalIndicatorService technicalIndicatorService;

    @Override
    public Mono<TradingSignal> generateSignal(
            StockResponse stock) {

        return technicalIndicatorService
                .calculate(stock.getSymbol())
                .map(indicator -> {

                    /*
                     * ============================================================
                     * 1. CURRENT LIVE PRICE
                     * ============================================================
                     */

                    Double livePrice = stock.getPrice();

                    if (livePrice == null || livePrice <= 0) {

                        log.warn(
                                "STRATEGY V2.2 | Invalid live price | " +
                                        "Symbol={} | LivePrice={}",
                                stock.getSymbol(),
                                livePrice);

                        return TradingSignal.builder()
                                .symbol(stock.getSymbol())
                                .signal(SignalType.HOLD.name())
                                .entryPrice(null)
                                .targetPrice(null)
                                .stopLoss(null)
                                .reason("Invalid live market price")
                                .confidence(0)
                                .rsi(null)
                                .ema20(null)
                                .ema50(null)
                                .macd(null)
                                .newsScore(null)
                                .newsSentiment(null)
                                .newsSummary(null)
                                .aiRecommendation(null)
                                .aiReasoning(null)
                                .riskLevel(null)
                                .exitStrategy(null)
                                .positionSize(null)
                                .build();
                    }


                    /*
                     * ============================================================
                     * 2. TECHNICAL INDICATORS
                     * ============================================================
                     */

                    double rsi =
                            indicator.getRsi14();

                    double ema20 =
                            indicator.getEma20();

                    double ema50 =
                            indicator.getEma50();

                    double macd =
                            indicator.getMacd();

                    double signalLine =
                            indicator.getSignalLine();

                    double previousMacd =
                            indicator.getPreviousMacd();

                    double previousSignalLine =
                            indicator.getPreviousSignalLine();


                    /*
                     * ============================================================
                     * 3. HISTORICAL CANDLE CLOSE
                     *
                     * This is extremely important for our diagnosis.
                     *
                     * We want to know how far the LIVE price has moved away
                     * from the latest historical candle used for indicators.
                     * ============================================================
                     */

                    Double historicalClose =
                            indicator.getClosePrice();

                    Double liveVsHistoricalClosePct = null;

                    if (historicalClose != null
                            && historicalClose > 0) {

                        liveVsHistoricalClosePct =
                                ((livePrice - historicalClose)
                                        / historicalClose)
                                        * 100.0;
                    }


                    /*
                     * ============================================================
                     * 4. TREND
                     * ============================================================
                     */

                    boolean bullishTrend =
                            ema20 > ema50;

                    boolean bearishTrend =
                            ema20 < ema50;


                    /*
                     * ============================================================
                     * 5. MACD MOMENTUM
                     * ============================================================
                     */

                    boolean bullishMomentum =
                            macd > signalLine;

                    boolean bearishMomentum =
                            macd < signalLine;


                    /*
                     * ============================================================
                     * 6. FRESH MACD CROSSOVER
                     * ============================================================
                     */

                    boolean bullishMacdCrossover =
                            previousMacd <= previousSignalLine
                                    && macd > signalLine;

                    boolean bearishMacdCrossover =
                            previousMacd >= previousSignalLine
                                    && macd < signalLine;


                    /*
                     * ============================================================
                     * 7. RSI CONFIRMATION
                     * ============================================================
                     */

                    boolean bullishRsi =
                            rsi >= 50
                                    && rsi <= 70;

                    boolean bearishRsi =
                            rsi >= 30
                                    && rsi <= 50;


                    /*
                     * ============================================================
                     * 8. ORIGINAL V2 TECHNICAL DECISION
                     *
                     * IMPORTANT:
                     * No new price filter is being used here.
                     *
                     * We keep the existing strategy logic intact so that
                     * Monday's results remain comparable with previous runs.
                     * ============================================================
                     */

                    String signal;

                    StringBuilder reason =
                            new StringBuilder();


                    if (bullishTrend
                            && bullishMomentum
                            && bullishMacdCrossover
                            && bullishRsi) {

                        signal =
                                SignalType.BUY.name();

                        reason.append(
                                "Bullish Trend, ");

                        reason.append(
                                "MACD Bullish Crossover, ");

                        reason.append(
                                "MACD Above Signal, ");

                        reason.append(
                                "RSI Confirmation");

                    } else if (bearishTrend
                            && bearishMomentum
                            && bearishMacdCrossover
                            && bearishRsi) {

                        signal =
                                SignalType.SELL.name();

                        reason.append(
                                "Bearish Trend, ");

                        reason.append(
                                "MACD Bearish Crossover, ");

                        reason.append(
                                "MACD Below Signal, ");

                        reason.append(
                                "RSI Confirmation");

                    } else {

                        signal =
                                SignalType.HOLD.name();

                        reason.append(
                                "No Fresh Trading Setup");
                    }


                    /*
                     * ============================================================
                     * 9. DIAGNOSTIC PRICE RELATIONSHIPS
                     *
                     * These are measurements only.
                     * They DO NOT reject a trade.
                     *
                     * We want Monday's logs to tell us whether losing entries
                     * are happening after an unusually large move.
                     * ============================================================
                     */

                    Double priceVsEma20Pct =
                            null;

                    Double priceVsEma50Pct =
                            null;

                    if (ema20 > 0) {

                        priceVsEma20Pct =
                                ((livePrice - ema20)
                                        / ema20)
                                        * 100.0;
                    }

                    if (ema50 > 0) {

                        priceVsEma50Pct =
                                ((livePrice - ema50)
                                        / ema50)
                                        * 100.0;
                    }


                    /*
                     * ============================================================
                     * 10. TECHNICAL CONFIDENCE
                     *
                     * Keep the existing confidence model.
                     * It remains a rule score, NOT win probability.
                     * ============================================================
                     */

                    int confidence =
                            calculateConfidence(
                                    bullishTrend,
                                    bearishTrend,
                                    bullishMacdCrossover,
                                    bearishMacdCrossover,
                                    bullishRsi,
                                    bearishRsi);


                    /*
                     * ============================================================
                     * 11. INITIAL TARGET / STOP LOSS
                     *
                     * Keep existing execution structure.
                     *
                     * Trailing SL remains outside this strategy.
                     * ============================================================
                     */

                    Double targetPrice;

                    Double stopLoss;


                    if (SignalType.BUY.name().equals(signal)) {

                        targetPrice =
                                livePrice * 1.02;

                        stopLoss =
                                livePrice * 0.99;

                    } else if (SignalType.SELL.name().equals(signal)) {

                        targetPrice =
                                livePrice * 0.98;

                        stopLoss =
                                livePrice * 1.01;

                    } else {

                        targetPrice =
                                livePrice;

                        stopLoss =
                                livePrice;
                    }


                    /*
                     * ============================================================
                     * 12. MAIN STRATEGY DIAGNOSTIC LOG
                     * ============================================================
                     */

                    log.info(
                            "STRATEGY V2.2 DIAGNOSTIC | " +
                                    "Symbol={} | " +
                                    "Decision={} | " +
                                    "Confidence={} | " +
                                    "LivePrice={} | " +
                                    "HistoricalClose={} | " +
                                    "LiveVsHistoricalClose={}%" +
                                    " | RSI={} | " +
                                    "EMA20={} | " +
                                    "EMA50={} | " +
                                    "PriceVsEMA20={}%" +
                                    " | PriceVsEMA50={}%" +
                                    " | MACD={} | " +
                                    "SignalLine={} | " +
                                    "PreviousMACD={} | " +
                                    "PreviousSignal={} | " +
                                    "BullTrend={} | " +
                                    "BearTrend={} | " +
                                    "BullMomentum={} | " +
                                    "BearMomentum={} | " +
                                    "BullCrossover={} | " +
                                    "BearCrossover={} | " +
                                    "BullRSI={} | " +
                                    "BearRSI={}",
                            stock.getSymbol(),
                            signal,
                            confidence,
                            round(livePrice),
                            round(historicalClose),
                            round(liveVsHistoricalClosePct),
                            round(rsi),
                            round(ema20),
                            round(ema50),
                            round(priceVsEma20Pct),
                            round(priceVsEma50Pct),
                            round(macd),
                            round(signalLine),
                            round(previousMacd),
                            round(previousSignalLine),
                            bullishTrend,
                            bearishTrend,
                            bullishMomentum,
                            bearishMomentum,
                            bullishMacdCrossover,
                            bearishMacdCrossover,
                            bullishRsi,
                            bearishRsi);


                    /*
                     * ============================================================
                     * 13. EXPLICIT BUY / SELL DIAGNOSTIC
                     *
                     * This makes the logs easier to analyze after market close.
                     * ============================================================
                     */

                    if (SignalType.BUY.name().equals(signal)) {

                        log.info(
                                "STRATEGY BUY DIAGNOSTIC | " +
                                        "Symbol={} | " +
                                        "LivePrice={} | " +
                                        "HistoricalClose={} | " +
                                        "LiveVsHistoricalClose={}%" +
                                        " | PriceVsEMA20={}%" +
                                        " | RSI={} | " +
                                        "MACD={} | SignalLine={} | " +
                                        "Reason={}",
                                stock.getSymbol(),
                                round(livePrice),
                                round(historicalClose),
                                round(liveVsHistoricalClosePct),
                                round(priceVsEma20Pct),
                                round(rsi),
                                round(macd),
                                round(signalLine),
                                reason);

                    } else if (SignalType.SELL.name().equals(signal)) {

                        log.info(
                                "STRATEGY SELL DIAGNOSTIC | " +
                                        "Symbol={} | " +
                                        "LivePrice={} | " +
                                        "HistoricalClose={} | " +
                                        "LiveVsHistoricalClose={}%" +
                                        " | PriceVsEMA20={}%" +
                                        " | RSI={} | " +
                                        "MACD={} | SignalLine={} | " +
                                        "Reason={}",
                                stock.getSymbol(),
                                round(livePrice),
                                round(historicalClose),
                                round(liveVsHistoricalClosePct),
                                round(priceVsEma20Pct),
                                round(rsi),
                                round(macd),
                                round(signalLine),
                                reason);
                    }


                    /*
                     * ============================================================
                     * 14. BUILD SIGNAL
                     * ============================================================
                     */

                    return TradingSignal.builder()
                            .symbol(stock.getSymbol())
                            .signal(signal)
                            .entryPrice(round(livePrice))
                            .targetPrice(round(targetPrice))
                            .stopLoss(round(stopLoss))
                            .reason(reason.toString())
                            .confidence(confidence)
                            .rsi(round(rsi))
                            .ema20(round(ema20))
                            .ema50(round(ema50))
                            .macd(round(macd))
                            .newsScore(null)
                            .newsSentiment(null)
                            .newsSummary(null)
                            .aiRecommendation(null)
                            .aiReasoning(null)
                            .riskLevel(null)
                            .exitStrategy(null)
                            .positionSize(null)
                            .build();
                });
    }

    private int calculateConfidence(
            boolean bullishTrend,
            boolean bearishTrend,
            boolean bullishCrossover,
            boolean bearishCrossover,
            boolean bullishRsi,
            boolean bearishRsi) {

        if (bullishTrend
                && bullishCrossover
                && bullishRsi) {

            return 90;
        }

        if (bearishTrend
                && bearishCrossover
                && bearishRsi) {

            return 90;
        }

        if (bullishTrend
                && bullishCrossover) {

            return 80;
        }

        if (bearishTrend
                && bearishCrossover) {

            return 80;
        }

        return 40;
    }

    private Double round(Double value) {

        if (value == null) {
            return null;
        }

        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
