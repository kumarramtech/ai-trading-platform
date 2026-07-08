package com.ram.trading.newsanalysis.client;

import reactor.core.publisher.Mono;

public interface AIClient {

    Mono<String> analyze(String prompt);

}