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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EntryExposureGuard entryExposureGuard;

    public RiskGuardResult evaluate(RiskEvaluation evaluation) {

        log.debug("========== RISK GUARD ==========");

        List<RiskViolation> violations = new ArrayList<>();

        // Existing Risk Management Checks
        RiskCheckResponse riskCheck =
                riskManagementService.validateTrade(evaluation.getSignal().getSymbol());

        if (!riskCheck.isAllowed()) {

            riskCheck.getViolations().forEach(v ->
                    violations.add(
                            RiskViolation.builder()
                                    .passed(false)
                                    .rule("RiskManagement")
                                    .reason(v)
                                    .build()));
        }

        // Crowding and open-risk rules again on the AI's answer: the AI calls run in
        // parallel, so setups that passed the pre-AI check may have opened trades since.
        if (entryExposureGuard != null) {
            String exposure = entryExposureGuard.exposureRejection(
                    evaluation.getSignal().getSymbol(), evaluation.getSignal().getSignal());
            if (exposure != null) {
                violations.add(RiskViolation.builder()
                        .passed(false)
                        .rule("EntryExposure")
                        .reason(exposure)
                        .build());
            }
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

        log.debug("Risk Approved : {}", approved);

        return RiskGuardResult.builder()
                .approved(approved)
                .violations(violations)
                .build();
    }
}