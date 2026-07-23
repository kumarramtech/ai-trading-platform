package com.ram.trading.signal.engine.dto.rules;

import com.ram.trading.signal.engine.contant.SignalType;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleResult {

    private SignalType signal;

    private int score;

    private String reason;

    private String ruleName;

}