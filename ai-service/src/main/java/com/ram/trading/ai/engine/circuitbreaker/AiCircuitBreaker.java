package com.ram.trading.ai.engine.circuitbreaker;

import com.ram.trading.ai.engine.constant.enums.CircuitBreakerState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AiCircuitBreaker {

    @Value("${ai.circuit.failure-threshold:3}")
    private int failureThreshold;

    @Value("${ai.circuit.open-duration-seconds:120}")
    private long openDurationSeconds;

    private volatile CircuitBreakerState state =
            CircuitBreakerState.CLOSED;

    private volatile int failureCount = 0;

    private volatile long openedAt = 0;

    public synchronized boolean allowRequest() {

        if (state == CircuitBreakerState.CLOSED) {
            return true;
        }

        if (state == CircuitBreakerState.OPEN) {

            long elapsed =
                    (System.currentTimeMillis() - openedAt) / 1000;

            if (elapsed >= openDurationSeconds) {

                state = CircuitBreakerState.HALF_OPEN;

                failureCount = 0;

                log.info("======================================");
                log.info("AI CIRCUIT HALF OPEN");
                log.info("Trying AI Again...");
                log.info("======================================");

                return true;
            }

            return false;
        }

        return true;
    }

    public synchronized void recordSuccess() {

        if (state != CircuitBreakerState.CLOSED) {

            log.info("======================================");
            log.info("AI CIRCUIT CLOSED");
            log.info("AI Provider Healthy Again");
            log.info("======================================");
        }

        state = CircuitBreakerState.CLOSED;
        failureCount = 0;
    }

    public synchronized void recordFailure() {

        failureCount++;

        log.warn("AI Failure Count : {}", failureCount);

        if (failureCount >= failureThreshold  && state != CircuitBreakerState.OPEN) {

            state = CircuitBreakerState.OPEN;

            openedAt = System.currentTimeMillis();

            log.error("======================================");
            log.error("AI CIRCUIT OPENED");
            log.error("Failure Count : {}", failureCount);
            log.error("Will Retry After {} Seconds",
                    openDurationSeconds);
            log.error("======================================");
        }
    }

    public CircuitBreakerState getState() {
        return state;
    }

}