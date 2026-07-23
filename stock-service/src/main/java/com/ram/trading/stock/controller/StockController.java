package com.ram.trading.stock.controller;

import com.ram.trading.stock.dto.InstrumentLookupResponse;
import com.ram.trading.stock.dto.InstrumentSubscriptionResponse;
import com.ram.trading.stock.dto.StockResponse;
import com.ram.trading.stock.service.StockService;
import com.ram.trading.stock.service.instument.InstrumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;

    private final InstrumentService instrumentService;

    @GetMapping("/stocks/{symbol}")
    public Mono<StockResponse> getStock(
            @PathVariable String symbol) {

        return stockService.getPrice(symbol);
    }

    @GetMapping("/stocks/allstocks")
    public Flux<StockResponse> getAllStocks() {

        return Flux.fromIterable(
                stockService.getAllStocks());
    }

    @GetMapping("/api/v1/instruments/lookup")
    public List<InstrumentLookupResponse> lookup() {

        return instrumentService.findAll()
                .stream()
                .map(i -> InstrumentLookupResponse.builder()
                        .instrumentKey(i.getInstrumentKey())
                        .tradingSymbol(i.getTradingSymbol())
                        .build())
                .toList();

    }

    @GetMapping("/api/v1/instruments/subscriptions")
    public ResponseEntity<List<InstrumentSubscriptionResponse>> subscriptions() {

        return ResponseEntity.ok(
                instrumentService.getSubscriptionInstruments());
    }

}
