package com.ram.trading.stock.controller;

import com.ram.trading.stock.service.momexec.MomentumExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Momentum executor (copies the paper momentum book into delivery orders; DRY_RUN unless LIVE is fully switched on). */
@RestController
@RequiredArgsConstructor
public class MomentumExecutionController {

    private final MomentumExecutionService exec;

    @GetMapping(value = "/momentum-exec", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> report() {
        return Mono.fromCallable(exec::report).subscribeOn(Schedulers.boundedElastic());
    }

    /** One run now, in the configured mode (LIVE keeps all its guards, including the 09:15-15:00 window). */
    @PostMapping(value = "/momentum-exec/run", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> run() {
        return Mono.fromCallable(() -> exec.run("manual")).subscribeOn(Schedulers.boundedElastic());
    }
}
