package com.ram.trading.signal.engine.indicator.service;

import com.ram.trading.signal.engine.contant.IndicatorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class IndicatorFactory {

    private final List<Indicator> indicators;

    public Indicator getIndicator(
            IndicatorType type) {

        return indicators.stream()

                .filter(i -> i.getType() == type)

                .findFirst()

                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Indicator not found : " + type));

    }

}