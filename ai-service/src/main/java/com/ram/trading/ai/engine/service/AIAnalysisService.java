package com.ram.trading.ai.engine.service;

import com.ram.trading.ai.engine.dto.*;

import java.util.List;

public interface AIAnalysisService {

    SignalExplanationResponse explainSignal(SignalExplanationRequest request);
    TradeReviewResponse reviewTrade(TradeReviewRequest request);
    StrategyReviewResponse reviewStrategy(List<TradeReviewRequest> trades);
    RiskAnalysisResponse analyzeRisk(RiskAnalysisRequest request);
}