package com.ram.trading.signal.engine.exit.impl;

import com.ram.trading.signal.engine.contant.SignalType;
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

        Double currentPrice = tick.getLastTradedPrice();

        boolean stopLossHit;

        if (SignalType.BUY.name().equalsIgnoreCase(position.getSignal())) {

            stopLossHit = currentPrice <= position.getStopLoss();

        } else {

            stopLossHit = currentPrice >= position.getStopLoss();
        }

        if (stopLossHit) {

            /*
             * A stop that has moved off its initial level was raised by
             * TrailingStopService; label it so breakeven/profit exits are
             * not reported as plain stop-loss losses.
             */
            boolean trailed = position.getInitialStopLoss() != null
                    && Double.compare(position.getStopLoss(), position.getInitialStopLoss()) != 0;

            log.info("{} Triggered for {}",
                    trailed ? "Trailing Stop" : "Stop Loss",
                    position.getSymbol());

            return ExitDecision.builder()
                    .exit(true)
                    .reason(trailed ? ExitReason.TRAILING_STOP : ExitReason.STOP_LOSS)
                    .exitPrice(currentPrice)
                    .exitType(trailed ? ExitType.TRAILING_STOP : ExitType.STOP_LOSS)
                    .build();
        }

        return ExitDecision.builder()
                .exit(false)
                .build();
    }


}