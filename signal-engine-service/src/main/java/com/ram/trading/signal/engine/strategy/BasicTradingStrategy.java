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

                    Double price = stock.getPrice();

                    double rsi = indicator.getRsi14();
                    double ema20 = indicator.getEma20();
                    double ema50 = indicator.getEma50();
                    double macd = indicator.getMacd();
                    double signalLine = indicator.getSignalLine();

                    double previousMacd =
                            indicator.getPreviousMacd();

                    double previousSignalLine =
                            indicator.getPreviousSignalLine();

                    boolean bullishTrend =
                            ema20 > ema50;

                    boolean bearishTrend =
                            ema20 < ema50;

                    boolean bullishMomentum =
                            macd > signalLine;

                    boolean bearishMomentum =
                            macd < signalLine;

                    boolean bullishMacdCrossover =
                            previousMacd <= previousSignalLine
                                    && macd > signalLine;

                    boolean bearishMacdCrossover =
                            previousMacd >= previousSignalLine
                                    && macd < signalLine;

                    /*
                     * RSI is used as confirmation,
                     * not as an entry trigger.
                     */
                    boolean bullishRsi =
                            rsi >= 50 && rsi <= 70;

                    boolean bearishRsi =
                            rsi >= 30 && rsi <= 50;

                    String signal;

                    StringBuilder reason =
                            new StringBuilder();

                    if (bullishTrend
                            && bullishMomentum
                            && bullishMacdCrossover
                            && bullishRsi) {

                        signal = SignalType.BUY.name();

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

                        signal = SignalType.SELL.name();

                        reason.append(
                                "Bearish Trend, ");

                        reason.append(
                                "MACD Bearish Crossover, ");

                        reason.append(
                                "MACD Below Signal, ");

                        reason.append(
                                "RSI Confirmation");

                    } else {

                        signal = SignalType.HOLD.name();

                        reason.append(
                                "No Fresh Trading Setup");
                    }

                    int confidence = calculateConfidence(
                            bullishTrend,
                            bearishTrend,
                            bullishMacdCrossover,
                            bearishMacdCrossover,
                            bullishRsi,
                            bearishRsi);

                    Double targetPrice;
                    Double stopLoss;

                    if (SignalType.BUY.name().equals(signal)) {

                        targetPrice =
                                price * 1.02;

                        stopLoss =
                                price * 0.99;

                    } else if (SignalType.SELL.name().equals(signal)) {

                        targetPrice =
                                price * 0.98;

                        stopLoss =
                                price * 1.01;

                    } else {

                        targetPrice = price;
                        stopLoss = price;
                    }

                    log.info(
                            "STRATEGY V2 | Symbol={} | Price={} | RSI={} | EMA20={} | EMA50={} | MACD={} | Signal={} | PreviousMACD={} | PreviousSignal={} | Decision={}",
                            stock.getSymbol(),
                            price,
                            rsi,
                            ema20,
                            ema50,
                            macd,
                            signalLine,
                            previousMacd,
                            previousSignalLine,
                            signal);

                    return TradingSignal.builder()
                            .symbol(stock.getSymbol())
                            .signal(signal)
                            .entryPrice(round(price))
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
