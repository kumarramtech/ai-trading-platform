package com.ram.trading.signal.engine.dto.request;

import com.ram.trading.signal.engine.dto.response.OpenPositionResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpenPositionDashboard {
    private Integer openTrades;
    private Double totalInvestment;
    private Double currentValue;
    private Double currentPnL;
    private Double availableCapital;
    private String bestPosition;
    private Double bestPnL;
    private String worstPosition;
    private Double worstPnL;
    private List<OpenPositionResponse> positions;
}