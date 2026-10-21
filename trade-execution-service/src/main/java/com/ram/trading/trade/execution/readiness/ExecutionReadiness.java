package com.ram.trading.trade.execution.readiness;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fail-closed readiness state for broker execution.
 * The application can be alive while execution remains unavailable.
 */
@Component
public class ExecutionReadiness {

    public enum State {
        RECOVERING,
        READY,
        FAILED
    }

    public record Snapshot(State state, String reason, LocalDateTime changedAt) {}

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(
            new Snapshot(State.RECOVERING, "Startup recovery has not completed", LocalDateTime.now()));

    public Snapshot snapshot() {
        return snapshot.get();
    }

    public boolean isReady() {
        return snapshot.get().state() == State.READY;
    }

    public void beginRecovery() {
        snapshot.set(new Snapshot(State.RECOVERING,
                "Startup recovery is running", LocalDateTime.now()));
    }

    public void markReady() {
        snapshot.set(new Snapshot(State.READY,
                "Startup reconciliation completed successfully", LocalDateTime.now()));
    }

    public void markFailed(String reason) {
        String safeReason = reason == null || reason.isBlank()
                ? "Startup recovery failed; manual reconciliation may be required"
                : reason;
        snapshot.set(new Snapshot(State.FAILED, safeReason, LocalDateTime.now()));
    }
}
