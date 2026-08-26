package com.ram.trading.ai.engine.gateway;

import com.ram.trading.ai.engine.circuitbreaker.AiCircuitBreaker;
import com.ram.trading.ai.engine.exception.CircuitBreakerOpenException;
import com.ram.trading.ai.engine.exception.LLMProviderException;
import com.ram.trading.ai.engine.provider.LLMProvider;
import com.ram.trading.ai.engine.retry.RetryableExceptionEvaluator;
import com.ram.trading.ai.engine.util.AiPromptOptimizer;
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

    private final AiCircuitBreaker aiCircuitBreaker;

    private final AiPromptOptimizer aiPromptOptimizer;

    private List<LLMProvider> sortedProviders;

    private LLMProvider geminiProvider;

    @PostConstruct
    public void init() {

        sortedProviders = providers.stream()
                .sorted(
                        Comparator.comparingInt(
                                LLMProvider::getPriority))
                .toList();

        geminiProvider = providers.stream()
                .filter(provider ->
                        "GEMINI".equalsIgnoreCase(
                                provider.getProviderName()))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException(
                                "GEMINI provider is not configured"));

        log.info("=========================================");
        log.info("AI Providers Initialized");

        sortedProviders.forEach(provider ->
                log.info(
                        "{} Priority={}",
                        provider.getProviderName(),
                        provider.getPriority()));

        log.info(
                "Dedicated News AI Provider : {}",
                geminiProvider.getProviderName());

        log.info("=========================================");
    }

    /**
     * Existing trading AI flow.
     *
     * IMPORTANT:
     * Keep the existing provider failover behaviour.
     *
     * OpenAI / DeepSeek / Gemini can participate
     * according to their configured priority.
     */
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

        String optimizedPrompt =
                aiPromptOptimizer.optimize(prompt);

        long gatewayStart =
                System.currentTimeMillis();

        Exception lastException = null;

        for (LLMProvider provider :
                sortedProviders) {

            if (!provider.isAvailable()) {

                log.warn(
                        "{} is disabled or unavailable.",
                        provider.getProviderName());

                continue;
            }

            long providerStart =
                    System.currentTimeMillis();

            try {

                log.info("=========================================");
                log.info(
                        "Trading AI Provider : {}",
                        provider.getProviderName());
                log.info("=========================================");

                String response =
                        provider.analyze(
                                optimizedPrompt);

                aiCircuitBreaker.recordSuccess();

                long providerElapsed =
                        System.currentTimeMillis()
                                - providerStart;

                long gatewayElapsed =
                        System.currentTimeMillis()
                                - gatewayStart;

                log.info(
                        "{} SUCCESS in {} ms",
                        provider.getProviderName(),
                        providerElapsed);

                log.info(
                        "Total AI Gateway Time : {} ms",
                        gatewayElapsed);

                return response;

            } catch (Exception ex) {

                lastException = ex;

                long providerElapsed =
                        System.currentTimeMillis()
                                - providerStart;

                if (retryableExceptionEvaluator
                        .isRetryable(ex)) {

                    log.warn(
                            "{} failed after {} ms. "
                                    + "Trying next provider.",
                            provider.getProviderName(),
                            providerElapsed,
                            ex);

                } else {

                    log.error(
                            "{} failed after {} ms. "
                                    + "Trying next provider.",
                            provider.getProviderName(),
                            providerElapsed,
                            ex);
                }
            }
        }

        long gatewayElapsed =
                System.currentTimeMillis()
                        - gatewayStart;

        log.error(
                "All AI providers failed after {} ms",
                gatewayElapsed);

        aiCircuitBreaker.recordFailure();

        throw new LLMProviderException(
                "All AI providers failed.",
                lastException);
    }

    /**
     * Dedicated News / Watchlist AI flow.
     *
     * Gemini ONLY.
     *
     * IMPORTANT:
     * We deliberately do NOT fail over to OpenAI
     * or DeepSeek here because the purpose of this
     * path is to keep broad Watchlist analysis
     * inexpensive during testing.
     */
    @Override
    public String analyzeNews(String prompt) {

        if (!aiCircuitBreaker.allowRequest()) {

            log.warn("=========================================");
            log.warn("AI Circuit is OPEN");
            log.warn("News AI request blocked");
            log.warn("=========================================");

            throw new CircuitBreakerOpenException(
                    "AI Circuit Breaker is OPEN");
        }

        if (geminiProvider == null) {

            throw new LLMProviderException(
                    "Gemini provider is not configured.",
                    null);
        }

        if (!geminiProvider.isAvailable()) {

            throw new LLMProviderException(
                    "Gemini provider is unavailable.",
                    null);
        }

        String optimizedPrompt =
                aiPromptOptimizer.optimize(prompt);

        long start =
                System.currentTimeMillis();

        try {

            log.info("=========================================");
            log.info("NEWS/WATCHLIST AI");
            log.info("Provider : GEMINI ONLY");
            log.info("=========================================");

            String response =
                    geminiProvider.analyze(
                            optimizedPrompt);

            aiCircuitBreaker.recordSuccess();

            log.info(
                    "Gemini News AI SUCCESS in {} ms",
                    System.currentTimeMillis() - start);

            return response;

        } catch (Exception ex) {

            log.error(
                    "Gemini News AI failed after {} ms",
                    System.currentTimeMillis() - start,
                    ex);

            /*
             * IMPORTANT:
             *
             * Do not call OpenAI/DeepSeek here.
             *
             * The News Service already has its own
             * neutral/default fallback behaviour.
             */
            aiCircuitBreaker.recordFailure();

            throw new LLMProviderException(
                    "Gemini News AI failed.",
                    ex);
        }
    }
}