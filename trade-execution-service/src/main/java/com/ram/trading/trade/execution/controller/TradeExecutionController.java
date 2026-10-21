package com.ram.trading.trade.execution.controller;

import com.ram.trading.trade.execution.dto.TradeExecutionRequest;
import com.ram.trading.trade.execution.dto.TradeExecutionResponse;
import com.ram.trading.trade.execution.service.TradeExecutionService;
import com.ram.trading.trade.execution.readiness.ExecutionReadiness;
import lombok.RequiredArgsConstructor;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import com.ram.trading.trade.execution.dto.TradeExitRequest;
import com.ram.trading.trade.execution.dto.TradeProtectionUpdateRequest;
import com.ram.trading.trade.execution.dto.ExecutedTradeRecord;
import java.util.List;

@RestController
@RequestMapping("/api/v1/trade-execution")
@RequiredArgsConstructor
public class TradeExecutionController {

    private final TradeExecutionService tradeExecutionService;
    private final ExecutionReadiness executionReadiness;

    @PostMapping
    public Mono<ResponseEntity<TradeExecutionResponse>> executeTrade(
            @RequestBody TradeExecutionRequest request) {

        return tradeExecutionService.executeTrade(request).map(ResponseEntity::ok);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        // Liveness only: application process is running, not necessarily safe for execution.
        return ResponseEntity.ok("Trade Execution Service is UP");
    }

    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> readiness() {
        ExecutionReadiness.Snapshot snapshot = executionReadiness.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", snapshot.state() == ExecutionReadiness.State.READY);
        body.put("state", snapshot.state().name());
        body.put("reason", snapshot.reason());
        body.put("changedAt", snapshot.changedAt());
        return ResponseEntity.status(snapshot.state() == ExecutionReadiness.State.READY ? 200 : 503)
                .body(body);
    }


    /**
     * Read-only list of persisted OPEN execution positions.
     * This endpoint does not submit orders and does not imply execution readiness.
     */
    @GetMapping("/open")
    public Mono<ResponseEntity<List<ExecutedTradeRecord>>> getOpenTrades() {
        return tradeExecutionService.getOpenTrades()
                .map(ResponseEntity::ok);
    }

    /** Persist internal protection values; this does not modify a broker-side stop order. */
    @PatchMapping("/protection")
    public Mono<ResponseEntity<ExecutedTradeRecord>> updateProtection(
            @RequestBody TradeProtectionUpdateRequest request) {
        return tradeExecutionService.updateProtection(request)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/exit")
    public Mono<ResponseEntity<TradeExecutionResponse>> exitTrade(
            @RequestBody TradeExitRequest request) {

        return tradeExecutionService.exitTrade(request)
                .map(ResponseEntity::ok);
    }

}