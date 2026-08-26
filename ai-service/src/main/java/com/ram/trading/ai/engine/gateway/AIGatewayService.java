package com.ram.trading.ai.engine.gateway;

public interface AIGatewayService {

    String analyze(String prompt);

    String analyzeNews(String prompt);

}