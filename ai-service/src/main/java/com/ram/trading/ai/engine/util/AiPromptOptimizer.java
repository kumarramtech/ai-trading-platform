package com.ram.trading.ai.engine.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AiPromptOptimizer {

    private static final int MIN_PROMPT_LENGTH_FOR_OPTIMIZATION = 100;

    private final boolean enabled;

    public AiPromptOptimizer(
            @Value("${ai.prompt-optimizer.enabled:true}") boolean enabled) {
        this.enabled = enabled;

        log.info("AI Prompt Optimizer initialized. Enabled={}", enabled);
    }

    /**
     * Safely optimizes an AI prompt before it is sent to an LLM.
     *
     * Design principles:
     * - Never changes semantic/business content
     * - Never removes trading instructions
     * - Never removes dynamic values
     * - Never truncates prompts
     * - Never changes JSON structures
     * - Never changes BUY/SELL/HOLD instructions
     * - Fails open by returning the original prompt
     */
    public String optimize(String prompt) {

        if (!enabled) {
            return prompt;
        }

        if (prompt == null || prompt.isBlank()) {
            return prompt;
        }

        if (prompt.length() < MIN_PROMPT_LENGTH_FOR_OPTIMIZATION) {
            return prompt;
        }

        try {

            int originalLength = prompt.length();

            String optimizedPrompt = normalizePrompt(prompt);

            int optimizedLength = optimizedPrompt.length();

            if (optimizedLength >= originalLength) {
                return prompt;
            }

            log.debug(
                    "AI prompt optimized. OriginalLength={}, OptimizedLength={}, Reduction={}%",
                    originalLength,
                    optimizedLength,
                    calculateReductionPercentage(
                            originalLength,
                            optimizedLength));

            return optimizedPrompt;

        } catch (Exception ex) {

            /*
             * Fail-open design.
             *
             * Prompt optimization must NEVER cause
             * an AI trading request to fail.
             */
            log.warn(
                    "AI prompt optimization failed. Using original prompt.",
                    ex);

            return prompt;
        }
    }

    /**
     * Performs only safe structural normalization.
     *
     * No semantic content is removed or rewritten.
     */
    private String normalizePrompt(String prompt) {

        String normalized = prompt;

        /*
         * Normalize Windows line endings.
         */
        normalized = normalized.replace("\r\n", "\n");

        /*
         * Normalize remaining carriage returns.
         */
        normalized = normalized.replace('\r', '\n');

        /*
         * Remove trailing spaces/tabs from every line.
         *
         * Example:
         *
         * "BUY   \n"
         *
         * becomes:
         *
         * "BUY\n"
         */
        normalized = normalized.replaceAll("[ \\t]+(?=\\n)", "");

        /*
         * Remove excessive consecutive blank lines.
         *
         * We retain at most one blank line between sections.
         */
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");

        /*
         * Remove unnecessary whitespace at the
         * beginning and end of the complete prompt.
         */
        normalized = normalized.trim();

        return normalized;
    }

    private double calculateReductionPercentage(
            int originalLength,
            int optimizedLength) {

        if (originalLength <= 0) {
            return 0.0;
        }

        return ((originalLength - optimizedLength)
                * 100.0)
                / originalLength;
    }

    public boolean isEnabled() {
        return enabled;
    }
}