package com.ram.trading.signal.engine.exit;

import com.ram.trading.signal.engine.dto.market.OpenPosition;
import com.ram.trading.signal.engine.dto.market.Tick;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ExitOrchestrator {

    private final List<ExitStrategy> strategies;

    public ExitDecision evaluate(OpenPosition position,
                                 Tick tick) {

        for (ExitStrategy strategy : strategies) {

            ExitDecision decision =
                    strategy.evaluate(position, tick);

            if (decision.isExit()) {
                return decision;
            }
        }

        return ExitDecision.builder()
                .exit(false)
                .build();
    }
}