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

        NewsAnalysisRequest request = NewsAnalysisRequest.builder()
                .symbol(symbol)
                .build();

        Mono<NewsAnalysisResponse> newsMono =newsAnalysisClient.analyze(request);

        Mono<PortfolioContextResponse> portfolioMono =portfolioContextClient.getPortfolioContext();

        Mono<OpenPositionContextResponse> openPositionMono = openPositionContextClient.getOpenPositionContext(symbol);

        return Mono.zip(
                newsMono,
                portfolioMono,
                openPositionMono
        ).map(tuple -> {

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

        }).onErrorResume(ex -> {

            log.error("Unable to build Trading Context", ex);

            return Mono.just(
                    TradingContext.builder()
                            .newsSummary("No market news available.")
                            .newsSentiment("UNKNOWN")
                            .newsScore(50)
                            .portfolioContext(null)
                            .openPositionContext(null)
                            .sectorSummary("Sector context not integrated.")
                            .riskSummary("Risk context not integrated.")
                            .build());

        });
    }

}