package com.ram.trading.signal.engine.dto.response;

import com.ram.trading.signal.engine.dto.request.OpportunityDashboard;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpportunityDashboardResponse {

    private Double capital;

    private Double recommendedCapital;

    private Double remainingCapital;

    private Integer opportunityCount;

    private List<OpportunityDashboard> opportunities;
}