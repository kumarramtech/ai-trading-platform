package com.ram.trading.stock.controller;

import com.ram.trading.stock.service.foexcl.FoExclusionBookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;

/** F&amp;O EXCLUSION paper book (separate strategy, paper only): report, and a manual bhavcopy scan. */
@RestController
@RequiredArgsConstructor
public class FoExclusionBookController {

    private final FoExclusionBookService book;

    @GetMapping(value = "/fo-exclusion-book", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> report() {
        return Mono.fromCallable(book::report).subscribeOn(Schedulers.boundedElastic());
    }

    /** Reads the latest NSE F&amp;O bhavcopy on or before the date and records stocks being removed; places nothing. */
    @PostMapping(value = "/fo-exclusion-book/scan", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> scan(@RequestParam("date") String date) {
        return Mono.fromCallable(() -> book.scan(LocalDate.parse(date))).subscribeOn(Schedulers.boundedElastic());
    }
}
