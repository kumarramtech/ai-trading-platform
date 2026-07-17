/*
package com.ram.trading.market.data.controller;

import com.ram.trading.market.data.cache.LivePriceCache;
import com.ram.trading.market.data.dto.LivePrice;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/market-stream")
@RequiredArgsConstructor
public class MarketPriceController {

    private final LivePriceCache livePriceCache;

    @GetMapping("/live-price/{symbol}")
    public ResponseEntity<LivePrice> getLivePrice(
            @PathVariable String symbol) {

        return livePriceCache.findBySymbol(symbol)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/live-prices")
    public List<LivePrice> getAllLivePrices() {
        return livePriceCache.findAll();
    }
}*/
