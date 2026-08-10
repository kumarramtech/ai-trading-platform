package com.ram.trading.market.data.controller;

import com.ram.trading.market.data.cache.LivePriceCache;
import com.ram.trading.market.data.dto.HealthResponse;
import com.ram.trading.market.data.dto.LivePrice;
import com.ram.trading.market.data.dto.MarketInstrument;
import com.ram.trading.market.data.dto.MarketStreamStatus;
import com.ram.trading.market.data.service.MarketStreamService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/market-stream")
@RequiredArgsConstructor
public class MarketStreamController {

    private final LivePriceCache livePriceCache;

    private final MarketStreamService marketStreamService;

    @GetMapping("/live-price/{symbol}")
    public ResponseEntity<LivePrice> getLivePrice(
            @PathVariable String symbol) {

        return livePriceCache.findBySymbol(symbol)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/live-prices")
    public List<LivePrice> prices() {
        return livePriceCache.findAll();
    }

    @GetMapping("/status")
    public ResponseEntity<MarketStreamStatus> status() {
        return ResponseEntity.ok(marketStreamService.getStatus());
    }

    @GetMapping("/instruments")
    public ResponseEntity<List<MarketInstrument>> instruments() {
        return ResponseEntity.ok(marketStreamService.getInstruments());
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(marketStreamService.getHealth());
    }

    @PostMapping("/test-price")
    public Mono<String> setTestPrice(
            @RequestBody LivePrice livePrice) {

        livePriceCache.update(livePrice);

        return Mono.just(
                "Test price updated: "
                        + livePrice.getSymbol()
                        + " -> "
                        + livePrice.getPrice());
    }
}
