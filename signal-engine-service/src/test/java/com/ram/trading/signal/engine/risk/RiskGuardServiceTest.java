package com.ram.trading.signal.engine.risk;

import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.service.RiskManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiskGuardServiceTest {

    private RiskManagementService riskManagementService;

    @BeforeEach
    void setUp() {
        riskManagementService = mock(RiskManagementService.class);
    }

    @Test
    void approvesOnlyWhenExistingRiskManagementAndAllRulesPass() {
        when(riskManagementService.validateTrade("TCS"))
                .thenReturn(RiskCheckResponse.builder().allowed(true).violations(List.of()).build());

        RiskRule passingRule = evaluation -> RiskViolation.builder()
                .passed(true).rule("TestRule").reason("passed").build();

        RiskGuardService service = new RiskGuardService(riskManagementService, List.of(passingRule));
        RiskGuardResult result = service.evaluate(evaluationFor("TCS"));

        assertTrue(result.isApproved());
        assertTrue(result.getViolations().isEmpty());
    }

    @Test
    void rejectsWhenExistingRiskManagementBlocksTrade() {
        when(riskManagementService.validateTrade("TCS"))
                .thenReturn(RiskCheckResponse.builder()
                        .allowed(false)
                        .violations(List.of("Daily loss limit exceeded. Trading halted."))
                        .build());

        RiskRule passingRule = evaluation -> RiskViolation.builder()
                .passed(true).rule("TestRule").reason("passed").build();

        RiskGuardService service = new RiskGuardService(riskManagementService, List.of(passingRule));
        RiskGuardResult result = service.evaluate(evaluationFor("TCS"));

        assertFalse(result.isApproved());
        assertEquals(1, result.getViolations().size());
        assertEquals("RiskManagement", result.getViolations().get(0).getRule());
    }

    @Test
    void collectsAllFailingRulesInsteadOfStoppingAtFirstViolation() {
        when(riskManagementService.validateTrade("INFY"))
                .thenReturn(RiskCheckResponse.builder().allowed(true).violations(List.of()).build());

        RiskRule first = evaluation -> RiskViolation.builder()
                .passed(false).rule("RuleA").reason("A failed").build();
        RiskRule second = evaluation -> RiskViolation.builder()
                .passed(false).rule("RuleB").reason("B failed").build();

        RiskGuardService service = new RiskGuardService(riskManagementService, List.of(first, second));
        RiskGuardResult result = service.evaluate(evaluationFor("INFY"));

        assertFalse(result.isApproved());
        assertEquals(2, result.getViolations().size());
        assertEquals(List.of("RuleA", "RuleB"),
                result.getViolations().stream().map(RiskViolation::getRule).toList());
    }


    @Test
    void preservesEveryExistingRiskManagementViolation() {
        when(riskManagementService.validateTrade("TCS"))
                .thenReturn(RiskCheckResponse.builder()
                        .allowed(false)
                        .violations(List.of(
                                "Daily loss limit exceeded. Trading halted.",
                                "Maximum open trades reached."))
                        .build());

        RiskRule passingRule = evaluation -> RiskViolation.builder()
                .passed(true).rule("TestRule").reason("passed").build();

        RiskGuardService service = new RiskGuardService(riskManagementService, List.of(passingRule));
        RiskGuardResult result = service.evaluate(evaluationFor("TCS"));

        assertFalse(result.isApproved());
        assertEquals(2, result.getViolations().size());
        assertEquals(List.of("Daily loss limit exceeded. Trading halted.",
                        "Maximum open trades reached."),
                result.getViolations().stream().map(RiskViolation::getReason).toList());
        assertTrue(result.getViolations().stream()
                .allMatch(violation -> "RiskManagement".equals(violation.getRule())));
    }

    @Test
    void propagatesRiskManagementFailureInsteadOfReturningApproval() {
        when(riskManagementService.validateTrade("TCS"))
                .thenThrow(new IllegalStateException("risk service unavailable"));

        RiskRule passingRule = evaluation -> RiskViolation.builder()
                .passed(true).rule("TestRule").reason("passed").build();

        RiskGuardService service = new RiskGuardService(riskManagementService, List.of(passingRule));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.evaluate(evaluationFor("TCS")));

        assertEquals("risk service unavailable", exception.getMessage());
        verify(riskManagementService).validateTrade("TCS");
    }

    private RiskEvaluation evaluationFor(String symbol) {
        TradingSignal signal = TradingSignal.builder().symbol(symbol).signal("BUY")
                .entryPrice(100.0).stopLoss(99.0).targetPrice(102.0).build();
        return RiskEvaluation.builder().signal(signal).build();
    }
}
