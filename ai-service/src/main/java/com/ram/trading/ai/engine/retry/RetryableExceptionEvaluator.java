package com.ram.trading.ai.engine.retry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RetryableExceptionEvaluator {

    public boolean isRetryable(Exception ex) {

        Throwable cause = ex;

        while (cause != null) {

            String message = cause.getMessage();

            if (message != null) {

                String lower = message.toLowerCase();

                if (lower.contains("429")
                        || lower.contains("rate limit")
                        || lower.contains("quota")
                        || lower.contains("500")
                        || lower.contains("502")
                        || lower.contains("503")
                        || lower.contains("504")
                        || lower.contains("timeout")
                        || lower.contains("connection refused")
                        || lower.contains("connection reset")
                        || lower.contains("temporarily unavailable")
                        || lower.contains("service unavailable")) {

                    log.warn("Retryable exception detected: {}", message);
                    return true;
                }
            }

            cause = cause.getCause();
        }

        return false;
    }

}