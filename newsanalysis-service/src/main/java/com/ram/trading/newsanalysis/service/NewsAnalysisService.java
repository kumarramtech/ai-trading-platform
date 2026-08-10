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

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class NewsAnalysisService {

    private final AIClient aiClient;

    private final NewsCollectionService newsCollectionService;

    private final NewsAnalysisPromptBuilder promptBuilder;

    private final NewsAnalysisResponseParser parser;

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

}