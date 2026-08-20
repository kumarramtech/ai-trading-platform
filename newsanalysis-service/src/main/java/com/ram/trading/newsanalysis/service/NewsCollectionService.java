package com.ram.trading.newsanalysis.service;

import com.ram.trading.newsanalysis.dto.NewsArticle;
import reactor.core.publisher.Mono;

import java.util.List;

public interface NewsCollectionService {

    Mono<List<NewsArticle>> collectNews(String symbol);

    Mono<List<NewsArticle>> collectMarketNews();

}