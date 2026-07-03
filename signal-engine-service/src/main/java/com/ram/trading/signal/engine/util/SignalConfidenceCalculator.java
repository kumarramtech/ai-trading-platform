package com.ram.trading.signal.engine.util;

import org.springframework.stereotype.Service;

@Service
public class SignalConfidenceCalculator {

    public Integer calculate(
            Integer technicalScore,
            Integer newsScore) {

        if (newsScore == null) {
            return technicalScore;
        }

        return (int)
                ((technicalScore * 0.7)
                        +
                 (newsScore * 0.3));
    }
}