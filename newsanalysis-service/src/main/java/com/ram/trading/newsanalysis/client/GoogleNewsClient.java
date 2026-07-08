package com.ram.trading.newsanalysis.client;

import reactor.core.publisher.Mono;

import java.util.List;

public interface GoogleNewsClient {

    Mono<List<String>> getLatestHeadlines(String symbol);

}