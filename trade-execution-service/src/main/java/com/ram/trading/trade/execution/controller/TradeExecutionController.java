package com.ram.trading.trade.execution.controller;

import com.ram.trading.trade.execution.dto.TradeExecutionRequest;
import com.ram.trading.trade.execution.dto.TradeExecutionResponse;
import com.ram.trading.trade.execution.service.TradeExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/trade-execution")
@RequiredArgsConstructor
public class TradeExecutionController {

    private final TradeExecutionService tradeExecutionService;

    @PostMapping
    public Mono<ResponseEntity<TradeExecutionResponse>> executeTrade(
            @RequestBody TradeExecutionRequest request) {

        return tradeExecutionService.executeTrade(request).map(ResponseEntity::ok);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {

        return ResponseEntity.ok(
                "Trade Execution Service is UP"
        );
    }
}