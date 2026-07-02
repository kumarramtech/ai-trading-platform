package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.contant.IndicatorType;
import com.ram.trading.signal.engine.dto.indicator.IndicatorResult;
import com.ram.trading.signal.engine.indicator.macd.MACDIndicator;
import com.ram.trading.signal.engine.indicator.macd.MACDResult;
import com.ram.trading.signal.engine.indicator.service.IndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/indicators")
@Slf4j
public class IndicatorController {

    private final StockServiceClient historicalCandleService;
    private final IndicatorService indicatorService;
    private final MACDIndicator macdIndicator;

    @GetMapping("/rsi/{symbol}")
    public Mono<IndicatorResult> calculateRSI(

            @PathVariable String symbol,

            @RequestParam(defaultValue = "14")
            int period,

            @RequestParam(defaultValue = "days/1")
            String interval,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {

        return historicalCandleService
                .getHistoricalCandles(symbol, interval, from, to)
                .map(response -> {
                    return indicatorService.calculate(
                            IndicatorType.RSI,
                            response.getCandles(),
                            period);
                });

    }

    @GetMapping("/ema/{symbol}")
    public Mono<IndicatorResult> calculateEMA(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "20") int period,
            @RequestParam String interval,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {

        return historicalCandleService
                .getHistoricalCandles(symbol, interval, from, to)
                .map(response ->
                        indicatorService.calculate(
                                IndicatorType.EMA,
                                response.getCandles(),
                                period));
    }

    @GetMapping("/sma/{symbol}")
    public Mono<IndicatorResult> calculateSMA(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "20") int period,
            @RequestParam String interval,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {

        return historicalCandleService
                .getHistoricalCandles(symbol, interval, from, to)
                .map(response ->
                        indicatorService.calculate(
                                IndicatorType.SMA,
                                response.getCandles(),
                                period));
    }

    @GetMapping("/atr/{symbol}")
    public Mono<IndicatorResult> calculateATR(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "20") int period,
            @RequestParam String interval,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {

        return historicalCandleService
                .getHistoricalCandles(symbol, interval, from, to)
                .map(response ->
                        indicatorService.calculate(
                                IndicatorType.ATR,
                                response.getCandles(),
                                period));
    }

    @GetMapping("/macd/{symbol}")
    public Mono<MACDResult> calculateMACD(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "days/1") String interval,
            @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return historicalCandleService
                .getHistoricalCandles(symbol, interval, from, to)
                .map(response ->
                        macdIndicator.calculate(
                                response.getCandles()));
    }
}