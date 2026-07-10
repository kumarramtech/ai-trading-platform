package com.ram.trading.signal.engine.risk;

public interface RiskRule {

    RiskViolation validate(RiskEvaluation evaluation);

}