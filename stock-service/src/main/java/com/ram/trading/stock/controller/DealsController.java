package com.ram.trading.stock.controller;

import com.ram.trading.stock.service.deals.NseDealCollector;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** NSE bulk / block deals collector (data collection only): summary, and a manual read of today's files. */
@RestController
@RequiredArgsConstructor
public class DealsController {

    private final NseDealCollector collector;

    @GetMapping(value = "/deals/summary", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> summary() {
        return Mono.fromCallable(collector::summary).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping(value = "/deals/collect", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> collect() {
        return Mono.fromCallable(collector::collect).subscribeOn(Schedulers.boundedElastic());
    }
}
