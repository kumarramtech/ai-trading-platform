package com.ram.trading.ai.engine.provider;

public interface LLMProvider {

    String getProviderName();

    int getPriority();

    boolean isAvailable();

    String analyze(String prompt);

}