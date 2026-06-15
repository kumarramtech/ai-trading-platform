package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.AIServiceClient;
import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.dto.OpportunityResponse;
import com.ram.trading.signal.engine.dto.RiskAnalysisRequest;
import com.ram.trading.signal.engine.dto.RiskAnalysisResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.strategy.BasicTradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class SignalService {

    private final BasicTradingStrategy basicStrategy;
    private final StockServiceClient stockService;
    private final AIServiceClient aiServiceClient;

    public Mono<RiskAnalysisResponse> analyzeRisk(
            String symbol) {

        return stockService
                .getStockPrice(symbol)
                .flatMap(basicStrategy::generateSignal)
                .flatMap(signal -> {
                    RiskAnalysisRequest request =
                            RiskAnalysisRequest.builder()
                                    .symbol(signal.getSymbol())
                                    .signal(signal.getSignal())
                                    .confidence(signal.getConfidence())
                                    .rsi(signal.getRsi())
                                    .ema20(signal.getEma20())
                                    .ema50(signal.getEma50())
                                    .macd(signal.getMacd())
                                    .build();


                    if (!hasValidRiskData(request)) {

                        return Mono.just(
                                RiskAnalysisResponse.builder()
                                        .symbol(symbol)
                                        .riskLevel("UNKNOWN")
                                        .analysis("Insufficient indicator data available for risk analysis.")
                                        .build());
                    }

                    return aiServiceClient.analyzeRisk(request);
                });
    }

    private boolean hasValidRiskData(
            RiskAnalysisRequest request) {

        return request.getSymbol() != null
                && request.getSignal() != null
                && request.getConfidence() != null
                && request.getRsi() != null
                && request.getEma20() != null
                && request.getEma50() != null
                && request.getMacd() != null;
    }

    public Flux<OpportunityResponse> getTopOpportunities() {

        return stockService
                .getAllStocks()
                .flatMap(basicStrategy::generateSignal)

                .doOnNext(signal ->
                        log.info(
                                "Signal={} Symbol={} EntryPrice={}",
                                signal.getSignal(),
                                signal.getSymbol(),
                                signal.getEntryPrice()))

                .onErrorResume(ex -> {
                    log.warn(
                            "Skipping stock due to error: {}",
                            ex.getMessage());
                    return Mono.empty();
                })

                .filter(signal ->
                        !"HOLD".equals(
                                signal.getSignal()))

                .map(signal ->
                        OpportunityResponse.builder()
                                .symbol(signal.getSymbol())
                                .signal(signal.getSignal())
                                .confidence(signal.getConfidence())
                                .targetPrice(signal.getTargetPrice())
                                .stopLoss(signal.getStopLoss())
                                .score(calculateScore(signal))
                                .entryPrice(
                                        signal.getEntryPrice())
                                .build())

                .sort((a, b) ->
                        Integer.compare(
                                b.getConfidence(),
                                a.getConfidence()))

                .collectList()

                .flatMapMany(list -> {
                    for (int i = 0; i < list.size(); i++) {
                        list.get(i).setRank(i + 1);
                    }
                    return Flux.fromIterable(list);
                })

                .take(5);
    }

    private int calculateScore(TradingSignal signal) {
        int score =signal.getConfidence();
        if ("BUY".equals(signal.getSignal())) {
            score += 10;
        }
        return score;
    }
}