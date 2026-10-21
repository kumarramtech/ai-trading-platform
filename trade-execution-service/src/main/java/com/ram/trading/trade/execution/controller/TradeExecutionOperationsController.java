package com.ram.trading.trade.execution.controller;

import com.ram.trading.trade.execution.dto.ExecutedTradeRecord;
import com.ram.trading.trade.execution.repository.ExecutionIdempotencyRecord;
import com.ram.trading.trade.execution.repository.ExecutionIdempotencyRepository;
import com.ram.trading.trade.execution.repository.ExecutedTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only operational visibility for execution orders that require
 * reconciliation. This endpoint never submits, cancels, or modifies orders.
 */
@RestController
@RequestMapping("/api/v1/trade-execution/operations")
@RequiredArgsConstructor
@Slf4j
public class TradeExecutionOperationsController {

    private final ExecutedTradeRepository executedTradeRepository;
    private final ExecutionIdempotencyRepository idempotencyRepository;

    @GetMapping("/pending")
    public Mono<ResponseEntity<Map<String, Object>>> getPendingOrders() {
        return Mono.fromCallable(() -> {
                    List<ExecutedTradeRecord> entryPending =
                            executedTradeRepository.findEntryPendingTrades();
                    List<ExecutedTradeRecord> exitPending =
                            executedTradeRepository.findExitPendingTrades();
                    return new PendingSnapshot(entryPending, exitPending);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(snapshot -> idempotencyRepository.findUnresolved()
                        .map(unresolved -> {
                            Map<String, Object> body = new LinkedHashMap<>();
                            body.put("generatedAt", LocalDateTime.now());
                            body.put("entryPendingCount", snapshot.entryPending().size());
                            body.put("entryPending", snapshot.entryPending());
                            body.put("exitPendingCount", snapshot.exitPending().size());
                            body.put("exitPending", snapshot.exitPending());
                            body.put("unresolvedIdempotencyCount", unresolved.size());
                            body.put("unresolvedIdempotency", unresolved);
                            body.put("safetyNote", "Read-only snapshot. Do not resubmit an order solely because it appears here; reconcile using its existing broker order ID or tag.");
                            return ResponseEntity.ok(body);
                        }))
                .onErrorResume(error -> {
                    log.error("Failed to load pending execution operations snapshot", error);
                    Map<String, Object> errorBody = new LinkedHashMap<>();
                    errorBody.put("error", "PENDING_EXECUTION_SNAPSHOT_FAILED");
                    errorBody.put("message", "Could not load the complete pending-order snapshot. Check service logs and database connectivity.");
                    return Mono.just(ResponseEntity.internalServerError().body(errorBody));
                });
    }

    private record PendingSnapshot(
            List<ExecutedTradeRecord> entryPending,
            List<ExecutedTradeRecord> exitPending) {
    }
}
