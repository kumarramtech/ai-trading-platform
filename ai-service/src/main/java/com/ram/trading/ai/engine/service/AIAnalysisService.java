package com.ram.trading.ai.engine.service;

import com.ram.trading.ai.engine.dto.SignalExplanationRequest;
import com.ram.trading.ai.engine.dto.SignalExplanationResponse;
import com.ram.trading.ai.engine.dto.TradeReviewRequest;
import com.ram.trading.ai.engine.dto.TradeReviewResponse;

public interface AIAnalysisService {

    SignalExplanationResponse explainSignal(SignalExplanationRequest request);
    TradeReviewResponse reviewTrade(TradeReviewRequest request);
}