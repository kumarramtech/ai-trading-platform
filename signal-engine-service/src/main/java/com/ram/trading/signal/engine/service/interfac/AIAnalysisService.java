package com.ram.trading.signal.engine.service.interfac;

import com.ram.trading.signal.engine.dto.SignalExplanation;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;

public interface AIAnalysisService {

    SignalExplanation explainSignal(
            TradingSignal signal,
            TechnicalIndicatorResponse indicator);
}