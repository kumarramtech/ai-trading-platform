package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.service.SignalGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/signals")
@RequiredArgsConstructor
@Slf4j
public class LiveSignalController {

    private final SignalGenerationService signalGenerationService;

    @PostMapping("/live")
    public Mono<TradingSignal> processTick(@RequestBody Tick tick) {

        log.info("LIVE REQUEST RECEIVED : {}", tick.getSymbol());

        long start = System.currentTimeMillis();

        return signalGenerationService.generateSignal(tick)
                .doOnSuccess(signal -> {
                    long elapsed = System.currentTimeMillis() - start;
                    log.info(
                            "LIVE REQUEST COMPLETED : {} in {} ms",
                            tick.getSymbol(),
                            elapsed);
                })
                .doOnError(ex -> {
                    long elapsed = System.currentTimeMillis() - start;
                    log.error(
                            "LIVE REQUEST FAILED : {} after {} ms",
                            tick.getSymbol(),
                            elapsed,
                            ex);
                });
    }
}