package com.ram.trading.signal.engine.exit.impl;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.market.*;
import com.ram.trading.signal.engine.exit.ExitDecision;
import com.ram.trading.signal.engine.exit.ExitStrategy;
import com.ram.trading.signal.engine.exit.ExitType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(StrategyOrder.TARGET)
public class TargetStrategy implements ExitStrategy {

    @Override
    public ExitDecision evaluate(OpenPosition position,
                                 Tick tick) {

        Double currentPrice = tick.getLastTradedPrice();

        boolean targetHit;

        if (SignalType.BUY.name().equalsIgnoreCase(position.getSignal())) {

            targetHit = currentPrice >= position.getTargetPrice();

        } else {

            targetHit = currentPrice <= position.getTargetPrice();
        }

        if (targetHit) {

            log.info("Target Achieved for {}", position.getSymbol());

            return ExitDecision.builder()
                    .exit(true)
                    .reason(ExitReason.TARGET)
                    .exitPrice(currentPrice)
                    .exitType(ExitType.TARGET)
                    .build();
        }

        return ExitDecision.builder()
                .exit(false)
                .build();
    }
}