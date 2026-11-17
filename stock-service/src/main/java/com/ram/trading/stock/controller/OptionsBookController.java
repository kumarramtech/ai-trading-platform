package com.ram.trading.stock.controller;

import com.ram.trading.stock.service.options.OptionsCondorBookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** OPTIONS paper book (separate strategy, paper only): read-only report. */
@RestController
@RequiredArgsConstructor
public class OptionsBookController {

    private final OptionsCondorBookService book;

    @GetMapping(value = "/options-book", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> report() {
        return Mono.fromCallable(book::report).subscribeOn(Schedulers.boundedElastic());
    }
}
