package com.ram.trading.newsanalysis.client;

import com.ram.trading.newsanalysis.dto.NewsArticle;
import reactor.core.publisher.Mono;

import java.util.List;

public interface NewsApiClient {

    Mono<List<NewsArticle>> getLatestHeadlines(
            String symbol,
            String companyName);

}