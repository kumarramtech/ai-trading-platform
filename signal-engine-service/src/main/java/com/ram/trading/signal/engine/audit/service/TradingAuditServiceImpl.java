package com.ram.trading.signal.engine.audit.service;

import com.ram.trading.signal.engine.audit.dto.TradingAuditReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TradingAuditServiceImpl implements TradingAuditService {

    @Override
    public void audit(TradingAuditReport report) {

        log.info("===========================================");

        log.info("SYMBOL : {}", report.getSymbol());

        log.info("-------------------------------------------");

        log.info("EMA SIGNAL   : {}", report.getEmaSignal());

        log.info("MACD SIGNAL  : {}", report.getMacdSignal());

        log.info("RSI SIGNAL   : {}", report.getRsiSignal());

        log.info("-------------------------------------------");

        log.info("FINAL SIGNAL : {}", report.getFinalSignal());

        log.info("CONFIDENCE   : {}", report.getConfidence());

        log.info("ENGINEERING  : {}",
                report.isEngineeringFilterPassed());

        log.info("REASON       : {}",
                report.getRejectionReason());

        log.info("===========================================");
    }

}