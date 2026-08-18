package com.ram.trading.stock.controller;

import com.ram.trading.stock.dto.InstrumentResponse;
import com.ram.trading.stock.entity.Instrument;
import com.ram.trading.stock.service.instument.InstrumentDownloadService;
import com.ram.trading.stock.service.instument.InstrumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/instruments")
public class InstrumentController {

    private final InstrumentDownloadService downloadService;

    private final InstrumentService instrumentService;

    @PostMapping("/refresh")
    public Mono<ResponseEntity<String>> refresh() {

        return Mono.fromCallable(() -> {

            downloadService.downloadAndImport();

            return ResponseEntity.ok("Instrument Master Imported Successfully");

        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{symbol}")
    public ResponseEntity<InstrumentResponse> getInstrument(
            @PathVariable String symbol) {

        Instrument instrument =
                instrumentService.getActiveInstrument(symbol);

        return ResponseEntity.ok(
                InstrumentResponse.builder()
                        .tradingSymbol(
                                instrument.getTradingSymbol())
                        .instrumentKey(
                                instrument.getInstrumentKey())
                        .build());
    }

}