package com.ram.trading.stock.controller;

import com.ram.trading.stock.service.shortexec.ShortExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/** Intraday short executor (copies the FADE and F&O exclusion paper books; DRY_RUN unless LIVE is fully switched on). */
@RestController
@RequiredArgsConstructor
public class ShortExecutionController {

    private final ShortExecutionService exec;

    @GetMapping(value = "/short-exec", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> report() {
        return Mono.fromCallable(exec::report).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(value = "/short-exec/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> summary() {
        return Mono.fromCallable(exec::summary).subscribeOn(Schedulers.boundedElastic());
    }

    /** One run of one book (fade or foex) now, in its configured mode, with all its guards. */
    @PostMapping(value = "/short-exec/run", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> run(@RequestParam String book) {
        return Mono.fromCallable(() -> exec.run(book, LocalDateTime.now(ZoneId.of("Asia/Kolkata"))))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
