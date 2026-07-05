package com.ram.trading.stock.controller;

import com.ram.trading.stock.bootstrap.BootstrapService;
import com.ram.trading.stock.bootstrap.InstrumentBootstrapService;
import com.ram.trading.stock.bootstrap.dto.BootstrapResult;
import com.ram.trading.stock.bootstrap.history.HistoricalDataBootstrapService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/bootstrap")
public class BootstrapController {

    private final BootstrapService bootstrapService;
    private final InstrumentBootstrapService instrumentBootstrapService;
    private final HistoricalDataBootstrapService historicalDataBootstrapService;

    /**
     * Download Instrument Master
     */
    @PostMapping("/instruments")
    public Mono<ResponseEntity<String>>  downloadInstruments() {

        log.info("Manual Instrument Bootstrap Triggered");

        instrumentBootstrapService.bootstrap();

        return Mono.fromCallable(() -> {
                    bootstrapService.bootstrap();
                    return ResponseEntity.ok("Instrument Bootstrap Completed Successfully");
                })
                .subscribeOn(Schedulers.boundedElastic());

    }

    /**
     * Download Historical Data
     */
    @PostMapping("/history")
    public Mono<ResponseEntity<String>> downloadHistoricalData() {

        return Mono.fromRunnable(() -> historicalDataBootstrapService.bootstrap())
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(ResponseEntity.ok("Historical Bootstrap Completed Successfully"));
    }

    /**
     * Complete Bootstrap
     */
    @PostMapping("/full")
    public Mono<ResponseEntity<String>> fullBootstrap() {

        log.info("Manual Full Bootstrap Triggered");

        return Mono.fromCallable(() -> {
                    bootstrapService.bootstrap();
                    return ResponseEntity.ok("Full Bootstrap Completed Successfully");
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

}