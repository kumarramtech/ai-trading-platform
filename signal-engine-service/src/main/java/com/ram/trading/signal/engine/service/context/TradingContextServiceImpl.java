package com.ram.trading.signal.engine.service.context;

import com.ram.trading.signal.engine.client.NewsAnalysisClient;
import com.ram.trading.signal.engine.client.PortfolioContextClient;
import com.ram.trading.signal.engine.client.interfac.OpenPositionContextClient;
import com.ram.trading.signal.engine.dto.ai.portfolio.OpenPositionContextResponse;
import com.ram.trading.signal.engine.dto.portfolio.PortfolioContextResponse;
import com.ram.trading.signal.engine.dto.request.NewsAnalysisRequest;
import com.ram.trading.signal.engine.dto.response.NewsAnalysisResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@Slf4j
@RequiredArgsConstructor
public class TradingContextServiceImpl implements TradingContextService {

    private final NewsAnalysisClient newsAnalysisClient;

    private final PortfolioContextClient portfolioContextClient;

    private final OpenPositionContextClient openPositionContextClient;

    @Override
    public Mono<TradingContext> buildTradingContext(String symbol) {

        final long start = System.currentTimeMillis();

        log.info("====================================================");
        log.info("Trading Context Started : {}", symbol);
        log.info("====================================================");

        NewsAnalysisRequest request = NewsAnalysisRequest.builder()
                .symbol(symbol)
                .build();

        final long newsStart = System.currentTimeMillis();

        Mono<NewsAnalysisResponse> newsMono =
                newsAnalysisClient.analyze(request)
                        .doOnSuccess(response ->
                                log.info("News Context Time [{}] : {} ms",
                                        symbol,
                                        System.currentTimeMillis() - newsStart))
                        .onErrorResume(ex -> {
                            log.warn("Unable to fetch News Context. Continuing with default values.", ex);

                            return Mono.just(
                                    NewsAnalysisResponse.builder()
                                            .summary("No market news available.")
                                            .sentiment("UNKNOWN")
                                            .score(50)
                                            .build());
                        });

        final long portfolioStart = System.currentTimeMillis();

        Mono<PortfolioContextResponse> portfolioMono =
                portfolioContextClient.getPortfolioContext()
                        .doOnSuccess(response ->
                                log.info("Portfolio Context Time [{}] : {} ms",
                                        symbol,
                                        System.currentTimeMillis() - portfolioStart))
                        .onErrorResume(ex -> {
                            log.warn("Unable to fetch Portfolio Context. Continuing with default values.", ex);

                            return Mono.just(
                                    PortfolioContextResponse.builder()
                                            .build());
                        });

        final long openPositionStart = System.currentTimeMillis();

        Mono<OpenPositionContextResponse> openPositionMono =
                openPositionContextClient.getOpenPositionContext(symbol)
                        .doOnSuccess(response ->
                                log.info("Open Position Context Time [{}] : {} ms",
                                        symbol,
                                        System.currentTimeMillis() - openPositionStart))
                        .onErrorResume(ex -> {
                            log.warn("Unable to fetch Open Position Context. Continuing with default values.", ex);

                            return Mono.just(
                                    OpenPositionContextResponse.builder()
                                            .positionExists(false)
                                            .build());
                        });

        return Mono.zip(
                        newsMono,
                        portfolioMono,
                        openPositionMono)
                .map(tuple -> {

                    NewsAnalysisResponse news = tuple.getT1();
                    PortfolioContextResponse portfolio = tuple.getT2();
                    OpenPositionContextResponse openPosition = tuple.getT3();

                    return TradingContext.builder()
                            .newsSummary(news.getSummary())
                            .newsSentiment(news.getSentiment())
                            .newsScore(news.getScore())
                            .portfolioContext(portfolio)
                            .openPositionContext(openPosition)
                            .sectorSummary("Sector context not integrated.")
                            .riskSummary("Risk context not integrated.")
                            .build();
                })
                .doFinally(signalType ->
                        log.info("TOTAL Trading Context Time [{}] : {} ms",
                                symbol,
                                System.currentTimeMillis() - start));
    }

}