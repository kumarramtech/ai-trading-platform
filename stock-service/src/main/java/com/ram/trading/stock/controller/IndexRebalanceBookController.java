package com.ram.trading.stock.controller;

import com.ram.trading.stock.service.indexrebal.IndexRebalanceBookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;

/** INDEX REBALANCE paper book (separate strategy, paper only): report, and a manual release scan for one date. */
@RestController
@RequiredArgsConstructor
public class IndexRebalanceBookController {

    private final IndexRebalanceBookService book;

    @GetMapping(value = "/index-rebalance-book", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> report() {
        return Mono.fromCallable(book::report).subscribeOn(Schedulers.boundedElastic());
    }

    /** Re-checks the NSE Indices releases of one date (ind_prsDDMMYYYY*.pdf); records events, places nothing. */
    @PostMapping(value = "/index-rebalance-book/scan", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> scan(@RequestParam("date") String date) {
        return Mono.fromCallable(() -> book.scan(LocalDate.parse(date))).subscribeOn(Schedulers.boundedElastic());
    }
}
