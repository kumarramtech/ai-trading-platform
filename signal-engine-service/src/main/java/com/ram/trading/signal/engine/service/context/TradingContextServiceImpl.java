package com.ram.trading.signal.engine.service.context;

import com.ram.trading.signal.engine.client.NewsAnalysisClient;
import com.ram.trading.signal.engine.client.PortfolioContextClient;
import com.ram.trading.signal.engine.dto.request.NewsAnalysisRequest;
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

    @Override
    public Mono<TradingContext> buildTradingContext(String symbol) {

        NewsAnalysisRequest request =
                NewsAnalysisRequest.builder()
                        .symbol(symbol)
                        .build();
        return Mono.zip(
                newsAnalysisClient.analyze(request),
                portfolioContextClient.getPortfolioContext()
        ).map(tuple -> {
            var news = tuple.getT1();
            var portfolio = tuple.getT2();
            return TradingContext.builder()
                    .newsSummary(news.getSummary())
                    .newsSentiment(news.getSentiment())
                    .newsScore(news.getScore())
                    .portfolioContext(portfolio)
                    .sectorSummary("Sector context not integrated.")
                    .riskSummary("Risk context not integrated.")
                    .openPositionsSummary("Open positions not integrated.")
                    .build();
        }).onErrorResume(ex -> {
            log.error("Unable to build Trading Context", ex);
            return Mono.just(
                    TradingContext.builder()
                            .newsSummary("No market news available.")
                            .newsSentiment("UNKNOWN")
                            .newsScore(50)
                            .portfolioContext(null)
                            .sectorSummary("Sector context not integrated.")
                            .riskSummary("Risk context not integrated.")
                            .openPositionsSummary("Open positions not integrated.")
                            .build()

            );
        });
    }

}