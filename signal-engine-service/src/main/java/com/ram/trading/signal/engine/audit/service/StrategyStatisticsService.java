package com.ram.trading.signal.engine.audit.service;


import com.ram.trading.signal.engine.audit.dto.StrategyStatistics;
import com.ram.trading.signal.engine.audit.dto.TradingAuditReport;

public interface StrategyStatisticsService {

    void recordAudit(TradingAuditReport report);

    void printStatistics();

    StrategyStatistics getStatistics();

}