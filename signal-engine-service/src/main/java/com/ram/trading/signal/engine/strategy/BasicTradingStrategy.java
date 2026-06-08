package com.ram.trading.signal.engine.strategy;

import com.ram.trading.signal.engine.client.IndicatorClient;
import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class BasicTradingStrategy
        implements TradingStrategy {

    private final IndicatorClient indicatorClient;

    @Override
    public Mono<TradingSignal> generateSignal(
            StockResponse stock) {

        return indicatorClient
                .getLatest(stock.getSymbol())
                .map(indicator -> {

                    Double price =
                            stock.getPrice();

                    int score = 0;

                    StringBuilder reason =
                            new StringBuilder();

                    if (indicator.getRsi14() < 35) {

                        score += 25;

                        reason.append(
                                "RSI Oversold, ");
                    }

                    if (indicator.getEma20()
                            > indicator.getEma50()) {

                        score += 25;

                        reason.append(
                                "EMA Bullish, ");
                    }

                    if (indicator.getMacd() > 0) {

                        score += 25;

                        reason.append(
                                "MACD Positive, ");
                    }

                    if (price >
                            indicator.getEma20()) {

                        score += 25;

                        reason.append(
                                "Price Above EMA20, ");
                    }

                    String signal;

                    if (score >= 75) {

                        signal = "BUY";

                    } else if (score <= 0) {

                        signal = "SELL";

                    } else {

                        signal = "HOLD";
                    }

                    Double targetPrice;
                    Double stopLoss;

                    if ("BUY".equals(signal)) {

                        targetPrice =
                                price * 1.02;

                        stopLoss =
                                price * 0.99;

                    } else if ("SELL".equals(signal)) {

                        targetPrice =
                                price * 0.98;

                        stopLoss =
                                price * 1.01;

                    } else {

                        targetPrice = price;
                        stopLoss = price;
                    }

                    String finalReason =
                            reason.toString();
                    if (finalReason.endsWith(", ")) {
                        finalReason = finalReason.substring( 0,finalReason.length() - 2);
                    }
                    return new TradingSignal(
                            stock.getSymbol(),
                            signal,
                            price,
                            targetPrice,
                            stopLoss,
                            finalReason,
                            score
                    );
                });
    }
}
