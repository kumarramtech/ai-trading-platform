package com.ram.trading.signal.engine.audit.service;

import com.ram.trading.signal.engine.audit.dto.TradingAuditReport;

public interface TradingAuditService {

    void audit(TradingAuditReport report);

}