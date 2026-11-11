package com.ram.trading.stock.controller;

import com.ram.trading.stock.service.earnings.EarningsCollectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Earnings drift data collection (research only): status, and a manual poll. */
@RestController
@RequiredArgsConstructor
public class EarningsCollectorController {

    private final EarningsCollectorService collector;

    @GetMapping("/earnings/collector/status")
    public Mono<EarningsCollectorService.Status> status() {
        return Mono.fromCallable(collector::status).subscribeOn(Schedulers.boundedElastic());
    }

    /** Starts one poll in the background (takes a few minutes); see the status for the result. */
    @PostMapping("/earnings/collector/run")
    public String run() {
        Schedulers.boundedElastic().schedule(collector::collectNow);
        return "Earnings poll started; GET /earnings/collector/status for the result.";
    }
}
