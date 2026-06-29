package com.ram.trading.stock.controller;

import com.ram.trading.stock.service.instument.InstrumentDownloadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/instruments")
public class InstrumentController {

    private final InstrumentDownloadService downloadService;

    @PostMapping("/refresh")
    public Mono<ResponseEntity<String>> refresh() {

        return Mono.fromCallable(() -> {

            downloadService.downloadAndImport();

            return ResponseEntity.ok("Instrument Master Imported Successfully");

        }).subscribeOn(Schedulers.boundedElastic());
    }

}