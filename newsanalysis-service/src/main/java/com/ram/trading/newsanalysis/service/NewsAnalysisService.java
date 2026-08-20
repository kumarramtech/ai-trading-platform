package com.ram.trading.newsanalysis.service;

import com.ram.trading.newsanalysis.client.AIClient;
import com.ram.trading.newsanalysis.dto.NewsAnalysisResponse;
import com.ram.trading.newsanalysis.dto.NewsArticle;
import com.ram.trading.newsanalysis.parser.NewsAnalysisResponseParser;
import com.ram.trading.newsanalysis.prompt.NewsAnalysisPromptBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import com.ram.trading.newsanalysis.dto.MarketTrendAnalysisResponse;
import com.ram.trading.newsanalysis.parser.MarketTrendResponseParser;
import com.ram.trading.newsanalysis.prompt.MarketTrendPromptBuilder;
import com.ram.trading.newsanalysis.dto.SectorStockAnalysisResponse;
import com.ram.trading.newsanalysis.parser.SectorStockResponseParser;
import com.ram.trading.newsanalysis.prompt.SectorStockPromptBuilder;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class NewsAnalysisService {

    private final AIClient aiClient;

    private final NewsCollectionService newsCollectionService;

    private final NewsAnalysisPromptBuilder promptBuilder;

    private final NewsAnalysisResponseParser parser;

    private final MarketTrendPromptBuilder marketTrendPromptBuilder;

    private final MarketTrendResponseParser marketTrendResponseParser;

    private final SectorStockPromptBuilder sectorStockPromptBuilder;

    private final SectorStockResponseParser sectorStockResponseParser;

    public Mono<NewsAnalysisResponse> analyze(String symbol) {
        log.info("Starting News Analysis for {}", symbol);
        return newsCollectionService
                .collectNews(symbol)
                .flatMap(headlines -> {
                    log.info("Collected {} headlines for {}",
                            headlines.size(),
                            symbol);
                    return aiClient
                            .analyze(
                                    promptBuilder.build(
                                            symbol,
                                            headlines))
                            .map(aiResponse ->
                                    parser.parse(
                                            symbol,
                                            aiResponse));
                });

    }

    public Mono<List<NewsArticle>> getLatestNews(String symbol) {

        log.info("Fetching latest news for {}", symbol);

        return newsCollectionService
                .collectNews(symbol)
                .doOnSuccess(headlines ->
                        log.info("Collected {} headlines for {}",
                                headlines != null ? headlines.size() : 0,
                                symbol))
                .onErrorResume(ex -> {

                    log.warn(
                            "Unable to fetch latest news for {}. Returning empty news list.",
                            symbol,
                            ex);

                    return Mono.just(List.of());
                });
    }

    public Mono<MarketTrendAnalysisResponse> analyzeMarketTrend() {

        log.info("Starting Market Trend Analysis");

        return newsCollectionService
                .collectMarketNews()
                .flatMap(articles -> {

                    log.info(
                            "Collected {} market news articles for trend analysis",
                            articles.size()
                    );

                    if (articles.isEmpty()) {

                        log.warn(
                                "No market news articles found. " +
                                        "Returning neutral market trend."
                        );

                        return Mono.just(
                                MarketTrendAnalysisResponse.builder()
                                        .overallMarketSentiment("NEUTRAL")
                                        .marketTrend("MIXED")
                                        .positiveSectors(List.of())
                                        .negativeSectors(List.of())
                                        .trendingThemes(List.of())
                                        .summary("No market news available for analysis")
                                        .build()
                        );
                    }

                    String prompt =
                            marketTrendPromptBuilder.build(articles);

                    return aiClient
                            .analyze(prompt)
                            .map(marketTrendResponseParser::parse);
                })
                .onErrorResume(ex -> {

                    log.error(
                            "Unable to analyze market trend",
                            ex
                    );

                    return Mono.just(
                            MarketTrendAnalysisResponse.builder()
                                    .overallMarketSentiment("NEUTRAL")
                                    .marketTrend("MIXED")
                                    .positiveSectors(List.of())
                                    .negativeSectors(List.of())
                                    .trendingThemes(List.of())
                                    .summary("Market trend analysis unavailable")
                                    .build()
                    );
                });
    }

    public Mono<SectorStockAnalysisResponse> analyzeSectorStocks(
            MarketTrendAnalysisResponse marketTrend) {

        log.info("Starting Sector Stock Analysis");

        if (marketTrend == null
                || marketTrend.getPositiveSectors() == null
                || marketTrend.getPositiveSectors().isEmpty()) {

            log.warn(
                    "No positive sectors available for sector stock analysis"
            );

            return Mono.just(
                    SectorStockAnalysisResponse.builder()
                            .sectorStocks(List.of())
                            .build()
            );
        }

        String prompt = sectorStockPromptBuilder.build(marketTrend);

        return aiClient
                .analyze(prompt)
                .map(sectorStockResponseParser::parse)
                .doOnNext(response ->
                        log.info(
                                "Sector Stock Analysis completed | Sectors={}",
                                response.getSectorStocks() != null
                                        ? response.getSectorStocks().size()
                                        : 0
                        )
                )
                .onErrorResume(ex -> {

                    log.error(
                            "Unable to analyze sector stocks",
                            ex
                    );

                    return Mono.just(
                            SectorStockAnalysisResponse.builder()
                                    .sectorStocks(List.of())
                                    .build()
                    );
                });
    }

}