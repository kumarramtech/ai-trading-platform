package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.client.TradeExecutionClient;
import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.dto.market.ExitReason;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.exit.ExitDecision;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


@Service
@RequiredArgsConstructor
@Slf4j
public class MarketCloseService {

    private final PaperTradeRepository repository;
    private final StockServiceClient stockServiceClient;
    private final PaperTradingService paperTradingService;
    private final TradeExecutionClient tradeExecutionClient;

    private final com.ram.trading.signal.engine.util.TradingSessionService tradingSessionService;

    @Value("${execution.trade:paper}")
    private String executionTradeMode;

    public Mono<Void> closeOpenPositions() {

        if (!tradingSessionService.shouldForceCloseTrades()) {
            return Mono.empty();
        }

        log.info("========================================");
        log.info("MARKET CLOSE AUTO SQUARE OFF STARTED");
        log.info("========================================");

        Mono<Void> livePositions = isLiveMode() ? closeLivePositions() : Mono.empty();

        return Flux.fromIterable(repository.findByStatus(SignalStatus.OPEN))

                .flatMap(this::closeTrade)

                .then(livePositions)

                .doOnSuccess(v ->
                        log.info("Market Close Auto Square Off Completed"));
    }

    private boolean isLiveMode() {
        return executionTradeMode != null && "live".equalsIgnoreCase(executionTradeMode.trim());
    }

    /**
     * Live positions are owned by Trade Execution Service, which cancels the broker
     * stop before submitting the market exit. A position not closed in this pass
     * (e.g. stop cancellation not yet confirmed) is retried by the next scheduled
     * pass; there is deliberately no immediate retry.
     */
    private Mono<Void> closeLivePositions() {

        return tradeExecutionClient.getOpenTrades()

                .flatMapMany(Flux::fromIterable)

                .filter(position -> position != null
                        && position.signalId() != null
                        && "OPEN".equalsIgnoreCase(position.status())
                        && position.remainingQuantity() != null
                        && position.remainingQuantity() > 0)

                .concatMap(position -> {

                    log.warn("LIVE MARKET CLOSE SQUARE OFF | signalId={} | symbol={} | qty={}",
                            position.signalId(), position.symbol(), position.remainingQuantity());

                    return tradeExecutionClient.exitTrade(
                                    new TradeExecutionClient.TradeExitRequest(
                                            position.signalId(), position.remainingQuantity(), "MARKET"))
                            .doOnNext(response -> log.warn(
                                    "LIVE MARKET CLOSE SQUARE OFF RESULT | signalId={} | status={} | orderId={} | message={}",
                                    position.signalId(), response.status(), response.orderId(), response.message()))
                            .onErrorResume(ex -> {
                                log.error("LIVE MARKET CLOSE SQUARE OFF FAILED | signalId={} | symbol={}",
                                        position.signalId(), position.symbol(), ex);
                                return Mono.empty();
                            });
                })

                .then()

                .onErrorResume(ex -> {
                    log.error("LIVE MARKET CLOSE SQUARE OFF: could not load live positions", ex);
                    return Mono.empty();
                });
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