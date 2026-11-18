package com.ram.trading.stock.controller;

import com.ram.trading.stock.service.announcements.NseAnnouncementCollector;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** NSE announcements collector (data collection only): read-only summary. */
@RestController
@RequiredArgsConstructor
public class AnnouncementsController {

    private final NseAnnouncementCollector collector;

    @GetMapping(value = "/announcements/summary", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> summary() {
        return Mono.fromCallable(collector::summary).subscribeOn(Schedulers.boundedElastic());
    }
}
