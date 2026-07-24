package com.ram.trading.signal.engine.audit.service;


import com.ram.trading.signal.engine.audit.dto.StrategyStatistics;
import com.ram.trading.signal.engine.audit.dto.TradingAuditReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class StrategyStatisticsServiceImpl  implements StrategyStatisticsService {

    private final StrategyStatistics statistics = new StrategyStatistics();

    @Override
    public void recordAudit(TradingAuditReport report) {

        statistics.setStocksScanned(
                statistics.getStocksScanned() + 1);

        switch (report.getFinalSignal()) {

            case BUY ->
                    statistics.setBuySignals(
                            statistics.getBuySignals() + 1);

            case SELL ->
                    statistics.setSellSignals(
                            statistics.getSellSignals() + 1);

            default ->
                    statistics.setHoldSignals(
                            statistics.getHoldSignals() + 1);
        }

        if (Boolean.TRUE.equals(report.isEngineeringFilterPassed())) {

            statistics.setEngineeringPassed(
                    statistics.getEngineeringPassed() + 1);

        } else {

            statistics.setEngineeringRejected(
                    statistics.getEngineeringRejected() + 1);
        }

    }

    @Override
    public void printStatistics() {

        log.info("==========================================");
        log.info(" Trading Strategy Statistics");
        log.info("==========================================");

        log.info("Stocks Scanned : {}", statistics.getStocksScanned());

        log.info("BUY  : {}", statistics.getBuySignals());
        log.info("SELL : {}", statistics.getSellSignals());
        log.info("HOLD : {}", statistics.getHoldSignals());

        log.info("------------------------------------------");

        log.info("EMA BUY  : {}", statistics.getEmaBuy());
        log.info("EMA SELL : {}", statistics.getEmaSell());

        log.info("------------------------------------------");

        log.info("MACD BUY  : {}", statistics.getMacdBuy());
        log.info("MACD SELL : {}", statistics.getMacdSell());

        log.info("------------------------------------------");

        log.info("RSI BUY  : {}", statistics.getRsiBuy());
        log.info("RSI SELL : {}", statistics.getRsiSell());

        log.info("------------------------------------------");

        log.info("Engineering Passed : {}",
                statistics.getEngineeringPassed());

        log.info("Engineering Rejected : {}",
                statistics.getEngineeringRejected());

        log.info("==========================================");
    }

    @Override
    public StrategyStatistics getStatistics() {
        return statistics;
    }
}