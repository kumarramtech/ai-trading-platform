package com.ram.trading.ai.engine.gateway;

import com.ram.trading.ai.engine.exception.LLMProviderException;
import com.ram.trading.ai.engine.provider.LLMProvider;
import com.ram.trading.ai.engine.retry.RetryableExceptionEvaluator;
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

    @Override
    public String analyze(String prompt) {

        List<LLMProvider> sortedProviders =
                providers.stream()
                        .sorted(Comparator.comparingInt(LLMProvider::getPriority))
                        .toList();

        Exception lastException = null;

        for (LLMProvider provider : sortedProviders) {

            try {

                if (!provider.isAvailable()) {

                    log.warn("{} is currently unavailable.",
                            provider.getProviderName());

                    continue;
                }

                log.info("=========================================");
                log.info("Trying Provider : {}", provider.getProviderName());
                log.info("=========================================");

                String response = provider.analyze(prompt);

                log.info("{} completed successfully.",
                        provider.getProviderName());

                return response;

            } catch (Exception ex) {

                lastException = ex;

                if (retryableExceptionEvaluator.isRetryable(ex)) {

                    log.warn(
                            "{} failed with retryable exception. Trying next provider.",
                            provider.getProviderName(),
                            ex);

                } else {

                    log.error(
                            "{} failed. Trying next available provider.",
                            provider.getProviderName(),
                            ex);
                }

                // Try the next configured provider
            }
        }

        log.error("All AI providers failed.");

        throw new LLMProviderException(
                "All AI providers failed.",
                lastException);
    }

}