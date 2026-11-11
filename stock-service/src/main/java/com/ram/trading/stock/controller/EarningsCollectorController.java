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

    private final com.ram.trading.stock.service.earnings.EarningsEventService events;

    /** Scored announcements (reaction, drift 20/40/60, growth), newest first. */
    @GetMapping("/earnings/events")
    public Mono<java.util.List<java.util.Map<String, Object>>> events(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int limit) {
        return Mono.fromCallable(() -> events.latest(limit)).subscribeOn(Schedulers.boundedElastic());
    }

    /** Re-scores the open events now (also runs daily at 08:10 IST). */
    @PostMapping("/earnings/events/refresh")
    public Mono<String> refreshEvents() {
        return Mono.fromCallable(events::refresh).subscribeOn(Schedulers.boundedElastic());
    }

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
