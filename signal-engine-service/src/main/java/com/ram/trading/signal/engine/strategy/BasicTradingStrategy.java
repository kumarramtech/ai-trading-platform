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

                    Double price =
                            stock.getPrice();

                    int bullishScore = 0;

                    int bearishScore = 0;

                    StringBuilder reason =
                            new StringBuilder();

                    if (indicator.getRsi14() < 35) {

                        bullishScore += SignalWeights.RSI;

                        reason.append("RSI Oversold, ");

                    } else if (indicator.getRsi14() > 70) {

                        bearishScore += SignalWeights.RSI;

                        reason.append("RSI Overbought, ");
                    }

                    if (indicator.getEma20() > indicator.getEma50()) {

                        bullishScore += SignalWeights.EMA;

                        reason.append("EMA Bullish, ");

                    } else {

                        bearishScore += SignalWeights.EMA;

                        reason.append("EMA Bearish, ");
                    }

                    if (indicator.getMacd() > 0) {

                        bullishScore += SignalWeights.MACD;

                        reason.append("MACD Positive, ");

                    } else {

                        bearishScore += SignalWeights.MACD;

                        reason.append("MACD Negative, ");
                    }

                    if (price > indicator.getEma20()) {

                        bullishScore += SignalWeights.PRICE_ACTION;

                        reason.append("Price Above EMA20, ");

                    } else {

                        bearishScore += SignalWeights.PRICE_ACTION;

                        reason.append("Price Below EMA20, ");
                    }

                    String signal;

                    int confidence =
                            Math.max(
                                    bullishScore,
                                    bearishScore);

                    if (bullishScore >= 70) {

                        signal = SignalType.BUY.name();

                    } else if (bearishScore >= 70) {

                        signal = SignalType.SELL.name();

                    } else {

                        signal = SignalType.HOLD.name();
                    }

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

                    log.info("RSI = " + indicator.getRsi14());
                    log.info("EMA20 = " + indicator.getEma20());
                    log.info("EMA50 = " + indicator.getEma50());
                    log.info("MACD = " + indicator.getMacd());

                    log.info("Final Score={}", bullishScore);
                    String finalReason =
                            reason.toString();

                    if (finalReason.endsWith(", ")) {

                        finalReason =
                                finalReason.substring(
                                        0,
                                        finalReason.length() - 2);
                    }

                    return new TradingSignal(
                            stock.getSymbol(),
                            signal,
                            round(price),
                            round(targetPrice),
                            round(stopLoss),
                            finalReason,
                            confidence,
                            indicator.getRsi14(),
                            indicator.getEma20(),
                            indicator.getEma50(),
                            indicator.getMacd(),null,null,null
                    );
                });
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
