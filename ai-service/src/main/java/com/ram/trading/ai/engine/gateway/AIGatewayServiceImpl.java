package com.ram.trading.ai.engine.gateway;

import com.ram.trading.ai.engine.circuitbreaker.AiCircuitBreaker;
import com.ram.trading.ai.engine.exception.CircuitBreakerOpenException;
import com.ram.trading.ai.engine.exception.LLMProviderException;
import com.ram.trading.ai.engine.provider.LLMProvider;
import com.ram.trading.ai.engine.retry.RetryableExceptionEvaluator;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AIGatewayServiceImpl implements AIGatewayService {

    private final List<LLMProvider> providers;

    private final RetryableExceptionEvaluator retryableExceptionEvaluator;

    private List<LLMProvider> sortedProviders;

    private final AiCircuitBreaker aiCircuitBreaker;

    @PostConstruct
    public void init() {

        sortedProviders = providers.stream()
                .sorted(Comparator.comparingInt(LLMProvider::getPriority))
                .toList();

        log.info("=========================================");
        log.info("AI Providers Initialized");
        sortedProviders.forEach(provider ->
                log.info("{} Priority={}",
                        provider.getProviderName(),
                        provider.getPriority()));
        log.info("=========================================");
    }

    @Override
    public String analyze(String prompt) {

        if (!aiCircuitBreaker.allowRequest()) {

            log.warn("=========================================");
            log.warn("AI Circuit is OPEN");
            log.warn("Using Engineering Decision");
            log.warn("=========================================");

            throw new CircuitBreakerOpenException(
                    "AI Circuit Breaker is OPEN");
        }

        long gatewayStart = System.currentTimeMillis();

        Exception lastException = null;

        for (LLMProvider provider : sortedProviders) {

            if (!provider.isAvailable()) {

                log.warn("{} is disabled or unavailable.",
                        provider.getProviderName());

                continue;
            }

            long providerStart = System.currentTimeMillis();

            try {

                log.info("=========================================");
                log.info("Trying Provider : {}", provider.getProviderName());
                log.info("=========================================");

                String response = provider.analyze(prompt);

                aiCircuitBreaker.recordSuccess();

                long providerElapsed =
                        System.currentTimeMillis() - providerStart;

                long gatewayElapsed =
                        System.currentTimeMillis() - gatewayStart;

                log.info("{} SUCCESS in {} ms",
                        provider.getProviderName(),
                        providerElapsed);

                log.info("Total AI Gateway Time : {} ms",
                        gatewayElapsed);

                return response;

            } catch (Exception ex) {

                lastException = ex;

                long providerElapsed =
                        System.currentTimeMillis() - providerStart;

                if (retryableExceptionEvaluator.isRetryable(ex)) {

                    log.warn("{} failed after {} ms. Trying next provider.",
                            provider.getProviderName(),
                            providerElapsed,
                            ex);

                } else {

                    log.error("{} failed after {} ms. Trying next provider.",
                            provider.getProviderName(),
                            providerElapsed,
                            ex);
                }
            }
        }

        long gatewayElapsed =
                System.currentTimeMillis() - gatewayStart;

        log.error("All AI providers failed after {} ms",
                gatewayElapsed);

        aiCircuitBreaker.recordFailure();

        throw new LLMProviderException(
                "All AI providers failed.",
                lastException);
    }
}