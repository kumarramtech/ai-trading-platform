package com.ram.trading.newsanalysis.client;

import com.ram.trading.newsanalysis.dto.NewsArticle;
import reactor.core.publisher.Mono;

import java.util.List;

public interface GoogleNewsClient {

    Mono<List<NewsArticle>> getLatestHeadlines(String symbol);

}