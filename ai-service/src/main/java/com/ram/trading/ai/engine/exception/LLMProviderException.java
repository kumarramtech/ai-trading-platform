package com.ram.trading.ai.engine.exception;

public class LLMProviderException extends RuntimeException {

    public LLMProviderException(String message) {
        super(message);
    }

    public LLMProviderException(String message, Throwable cause) {
        super(message, cause);
    }

}