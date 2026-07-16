package com.ram.trading.signal.engine.exit.impl;

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

        if (tick.getLastTradedPrice().compareTo(position.getTargetPrice()) >= 0) {
            log.info("Target Achieved for {}", position.getSymbol());
            return ExitDecision.builder()
                    .exit(true)
                    .reason(ExitReason.TARGET)
                    .exitPrice(tick.getLastTradedPrice())
                    .exitType(ExitType.TARGET)
                    .build();
        }

        return ExitDecision.builder()
                .exit(false)
                .build();
    }
}