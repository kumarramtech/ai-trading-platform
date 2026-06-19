package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.service.TradingOrchestratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/orchestrator")
@RequiredArgsConstructor
public class TradingOrchestratorController {

    private final TradingOrchestratorService service;

    @PostMapping("/run")
    public Mono<String> run() {

        return service.runTradingCycle();
    }
}