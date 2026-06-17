package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.service.PaperTradingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/trade-monitor")
@RequiredArgsConstructor
public class TradeMonitorController {

    private final PaperTradingService paperTradingService;

    @PostMapping("/run")
    public Mono<String> run() {
        return paperTradingService
                .monitorOpenTrades()
                .thenReturn("Trade Monitor Executed");
    }
}