package com.ram.trading.ai.engine.service;

import com.ram.trading.ai.engine.dto.AiDecisionResponse;
import com.ram.trading.ai.engine.dto.TradingDecisionRequest;

public interface AiDecisionService {

    AiDecisionResponse evaluate(TradingDecisionRequest request);

}