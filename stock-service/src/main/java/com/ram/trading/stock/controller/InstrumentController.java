package com.ram.trading.stock.controller;

import com.ram.trading.stock.dto.InstrumentResponse;
import com.ram.trading.stock.dto.TradableInstrumentResponse;
import com.ram.trading.stock.entity.Instrument;
import com.ram.trading.stock.service.instument.InstrumentDownloadService;
import com.ram.trading.stock.service.instument.InstrumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

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

    @GetMapping("/tradable-equities")
    public ResponseEntity<List<TradableInstrumentResponse>> getTradableEquities() {

        List<TradableInstrumentResponse> instruments =
                instrumentService
                        .findTradableEquities()
                        .stream()
                        .map(instrument ->
                                TradableInstrumentResponse.builder()
                                        .tradingSymbol(
                                                instrument.getTradingSymbol())
                                        .companyName(
                                                instrument.getCompanyName())
                                        .instrumentKey(
                                                instrument.getInstrumentKey())
                                        .exchange(
                                                instrument.getExchange())
                                        .segment(
                                                instrument.getSegment())
                                        .instrumentType(
                                                instrument.getInstrumentType())
                                        .build())
                        .toList();

        return ResponseEntity.ok(instruments);
    }

}