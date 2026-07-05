package com.ram.trading.stock.controller;

import com.ram.trading.stock.bootstrap.dto.HistoricalPriceResponse;
import com.ram.trading.stock.bootstrap.entity.HistoricalPrice;
import com.ram.trading.stock.bootstrap.history.HistoricalPriceService;
import com.ram.trading.stock.mapper.HistoricalPriceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/history")
public class HistoricalPriceController {

    private final HistoricalPriceService historicalPriceService;

    /**
     * Returns complete historical data for a symbol.
     */
    @GetMapping("/{symbol}")
    public ResponseEntity<List<HistoricalPriceResponse>> getHistoricalPrices(
            @PathVariable String symbol) {

        log.info("Fetching historical prices for {}", symbol);
        List<HistoricalPrice> prices =
                historicalPriceService.findBySymbol(symbol);
        List<HistoricalPriceResponse> priceResponses = prices.stream()
                .map(HistoricalPriceMapper::toResponse).toList();
        return ResponseEntity.ok(priceResponses);
    }

    /**
     * Returns historical data between two dates.
     */
    @GetMapping("/{symbol}/range")
    public ResponseEntity<List<HistoricalPrice>> getHistoricalPricesBetweenDates(
            @PathVariable String symbol,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {

        log.info(
                "Fetching history for {} between {} and {}",
                symbol,
                from,
                to);

        return ResponseEntity.ok(
                historicalPriceService.findBySymbolAndDateRange(
                        symbol,
                        from,
                        to));
    }

    /**
     * Returns latest N candles.
     */
    @GetMapping("/{symbol}/latest")
    public ResponseEntity<List<HistoricalPrice>> getLatestHistory(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "100")
            Integer limit) {

        log.info(
                "Fetching latest {} candles for {}",
                limit,
                symbol);

        return ResponseEntity.ok(
                historicalPriceService.findLatest(symbol, limit));
    }

}