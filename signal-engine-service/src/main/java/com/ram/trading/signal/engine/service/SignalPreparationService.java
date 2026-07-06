package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.rules.MarketContext;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.indicator.service.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class SignalPreparationService {

    private final TechnicalIndicatorService technicalIndicatorService;

    public Mono<SignalGenerationRequest> prepare(
            StockResponse stock) {

        return technicalIndicatorService
                .calculate(stock.getSymbol())
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