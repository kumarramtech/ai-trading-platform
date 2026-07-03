package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.IndicatorClient;
import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.rules.MarketContext;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class SignalPreparationService {

    private final IndicatorClient indicatorClient;

    public Mono<SignalGenerationRequest> prepare(
            StockResponse stock) {

        return indicatorClient
                .getLatest(stock.getSymbol())
                .map(indicator -> buildRequest(stock, indicator));
    }

    private SignalGenerationRequest buildRequest(
            StockResponse stock,
            TechnicalIndicatorResponse indicator) {

        MarketContext context =
                MarketContext.builder()
                        .build();

        return SignalGenerationRequest.builder()

                .symbol(stock.getSymbol())

                .currentPrice(stock.getPrice())

                .rsi(indicator.getRsi14())

                .sma20(indicator.getSma20())

                .sma50(indicator.getSma50())

                .ema20(indicator.getEma20())

                .ema50(indicator.getEma50())

                .macd(indicator.getMacd())

                .marketContext(context)

                .build();
    }
}