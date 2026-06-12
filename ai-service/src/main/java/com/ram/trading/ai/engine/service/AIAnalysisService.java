package com.ram.trading.ai.engine.service;

import com.ram.trading.ai.engine.dto.SignalExplanationRequest;
import com.ram.trading.ai.engine.dto.SignalExplanationResponse;

public interface AIAnalysisService {

    SignalExplanationResponse
    explainSignal(
            SignalExplanationRequest request);
}