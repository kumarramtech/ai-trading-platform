package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.indicator.service.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/indicator")
@RequiredArgsConstructor
public class TechnicalIndicatorController {

    private final TechnicalIndicatorService technicalIndicatorService;

    @GetMapping("/{symbol}")
    public Mono<TechnicalIndicatorResponse> calculate(
            @PathVariable String symbol) {

        return technicalIndicatorService.calculate(symbol);
    }
}