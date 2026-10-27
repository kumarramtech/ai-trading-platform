package com.ram.trading.signal.engine.indicator.service;

import com.ram.trading.signal.engine.dto.indicator.Candle;

import java.util.List;

/** Test access to the package-private turnover calculation. */
public final class TechnicalIndicatorTurnoverAccess {

    private TechnicalIndicatorTurnoverAccess() {
    }

    public static Double averageDailyTurnover(List<Candle> candles, int sessions) {
        return TechnicalIndicatorServiceImpl.averageDailyTurnover(candles, sessions);
    }
}
