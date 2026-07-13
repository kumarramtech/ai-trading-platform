package com.ram.trading.signal.engine.exit;

import com.ram.trading.signal.engine.dto.market.ExitReason;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExitDecision {

    private boolean exit;

    private ExitReason reason;

    private BigDecimal exitPrice;

    private ExitType exitType;

}