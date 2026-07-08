package com.ram.trading.newsanalysis.service;

import reactor.core.publisher.Mono;

import java.util.List;

public interface NewsCollectionService {

    Mono<List<String>> collectNews(String symbol);

}