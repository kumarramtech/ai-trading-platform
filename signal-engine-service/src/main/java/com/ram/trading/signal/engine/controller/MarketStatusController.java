package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.calendar.MarketStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Market live / closed for the dashboard (calendar + clock + our data feed). Read-only. */
@RestController
@RequiredArgsConstructor
public class MarketStatusController {

    private final MarketStatusService marketStatusService;

    @GetMapping("/analytics/market-status")
    public Mono<MarketStatusService.MarketStatus> marketStatus() {
        // Blocking HTTP and JDBC calls: keep them off the event loop.
        return Mono.fromCallable(marketStatusService::status).subscribeOn(Schedulers.boundedElastic());
    }
}
