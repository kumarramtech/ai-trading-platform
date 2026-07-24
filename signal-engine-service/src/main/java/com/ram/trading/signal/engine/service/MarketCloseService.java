package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.dto.market.ExitReason;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.exit.ExitDecision;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketCloseService {

    private final PaperTradeRepository repository;
    private final StockServiceClient stockServiceClient;
    private final PaperTradingService paperTradingService;

    public Mono<Void> closeOpenPositions() {

        LocalTime now = LocalTime.now();

        if (now.isBefore(LocalTime.of(15,20))) {
            return Mono.empty();
        }

        log.info("========================================");
        log.info("MARKET CLOSE AUTO SQUARE OFF STARTED");
        log.info("========================================");

        return Flux.fromIterable(repository.findByStatus(SignalStatus.OPEN))

                .flatMap(this::closeTrade)

                .then()

                .doOnSuccess(v ->
                        log.info("Market Close Auto Square Off Completed"));
    }

    private Mono<Void> closeTrade(PaperTrade trade) {

        return stockServiceClient

                .getStockPrice(trade.getSymbol())

                .flatMap(price -> {

                    Tick tick = Tick.builder()
                            .symbol(trade.getSymbol())
                            .lastTradedPrice(price.getPrice())
                            .build();

                    ExitDecision decision = ExitDecision.builder()
                            .exit(true)
                            .reason(ExitReason.MARKET_CLOSE)
                            .exitPrice(price.getPrice())
                            .build();

                    return paperTradingService.closeTrade(
                            trade,
                            decision,
                            tick);

                })

                .onErrorResume(ex -> {

                    log.error("Failed to close {}", trade.getSymbol(), ex);

                    return Mono.empty();

                });

    }

}