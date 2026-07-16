package com.ram.trading.signal.engine.exit.impl;

import com.ram.trading.signal.engine.dto.market.*;
import com.ram.trading.signal.engine.exit.ExitDecision;
import com.ram.trading.signal.engine.exit.ExitType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import com.ram.trading.signal.engine.exit.ExitStrategy;
@Slf4j
@Component
@Order(StrategyOrder.STOP_LOSS)
public class StopLossStrategy implements ExitStrategy {

    @Override
    public ExitDecision evaluate(OpenPosition position,
                                 Tick tick) {

        if (tick.getLastTradedPrice().compareTo(position.getStopLoss()) <= 0) {

            log.info("Stop Loss Triggered for {}", position.getSymbol());

            return ExitDecision.builder()
                    .exit(true)
                    .reason(ExitReason.STOP_LOSS)
                    .exitPrice(tick.getLastTradedPrice())
                    .exitType(ExitType.STOP_LOSS)
                    .build();
        }

        return ExitDecision.builder()
                .exit(false)
                .build();
    }


}