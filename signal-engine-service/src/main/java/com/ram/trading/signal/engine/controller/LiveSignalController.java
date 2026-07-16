package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.service.SignalGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/signals")
@RequiredArgsConstructor
public class LiveSignalController {

    private final SignalGenerationService signalGenerationService;

    @PostMapping("/live")
    public Mono<TradingSignal> processTick(
            @RequestBody Tick tick) {

        return signalGenerationService.generateSignal(tick);
    }
}