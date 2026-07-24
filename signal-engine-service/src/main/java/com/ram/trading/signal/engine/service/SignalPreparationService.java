/*
package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.rules.MarketContext;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.indicator.service.TechnicalIndicatorService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

// This is dead code
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalPreparationService {

    private final TechnicalIndicatorService technicalIndicatorService;

    @PostConstruct
    public void init() {
        log.error(">>>>>>>> SignalPreparationService LOADED <<<<<<<<");
    }

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

        log.info(
                "Preparation -> Symbol={}, MACD={}, SignalLine={}",
                stock.getSymbol(),
                indicator.getMacd(),
                indicator.getSignalLine());

        return SignalGenerationRequest.builder()

                .symbol(stock.getSymbol())

                .currentPrice(stock.getPrice())

                .rsi(indicator.getRsi14())

                .sma20(indicator.getSma20())

                .sma50(indicator.getSma50())

                .ema20(indicator.getEma20())

                .ema50(indicator.getEma50())

                .macd(indicator.getMacd())

                // NEW
                .signalLine(indicator.getSignalLine())

                .marketContext(context)

                .build();
    }
}
*/
