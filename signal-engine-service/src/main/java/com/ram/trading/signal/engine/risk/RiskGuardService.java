package com.ram.trading.signal.engine.risk;

import com.ram.trading.signal.engine.service.RiskManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RiskGuardService {

    private final RiskManagementService riskManagementService;

    private final List<RiskRule> rules;

    public RiskGuardResult evaluate(RiskEvaluation evaluation) {

        log.info("========== RISK GUARD ==========");

        List<RiskViolation> violations = new ArrayList<>();

        // Existing Risk Management Checks
        RiskCheckResponse riskCheck =
                riskManagementService.validateTrade();

        if (!riskCheck.isAllowed()) {

            riskCheck.getViolations().forEach(v ->
                    violations.add(
                            RiskViolation.builder()
                                    .passed(false)
                                    .rule("RiskManagement")
                                    .reason(v)
                                    .build()));
        }

        // New Rule Engine
        for (RiskRule rule : rules) {

            RiskViolation result =
                    rule.validate(evaluation);

            if (!result.isPassed()) {
                violations.add(result);
            }
        }

        boolean approved = violations.isEmpty();

        log.info("Risk Approved : {}", approved);

        return RiskGuardResult.builder()
                .approved(approved)
                .violations(violations)
                .build();
    }
}