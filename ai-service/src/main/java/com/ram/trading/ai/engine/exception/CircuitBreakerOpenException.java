package com.ram.trading.ai.engine.exception;

public class CircuitBreakerOpenException
        extends RuntimeException {

    public CircuitBreakerOpenException(String message) {
        super(message);
    }

}