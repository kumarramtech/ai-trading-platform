package com.ram.trading.signal.engine.risk;

import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class PositionSizingService {

    @Value("${trading.risk.max-per-trade:400}")
    private double maxRiskPerTrade;

    public PositionSizingResult calculate(
            TradingSignal signal) {

        double entryPrice =
                signal.getEntryPrice();

        double stopLoss =
                signal.getStopLoss();

        if (entryPrice <= 0 || stopLoss <= 0) {

            log.warn(
                    "RISK SIZING REJECTED | Symbol={} | Entry={} | StopLoss={}",
                    signal.getSymbol(),
                    entryPrice,
                    stopLoss);

            return PositionSizingResult.builder()
                    .valid(false)
                    .entryPrice(entryPrice)
                    .stopLoss(stopLoss)
                    .maxRiskPerTrade(maxRiskPerTrade)
                    .riskBasedQuantity(0)
                    .expectedRisk(0)
                    .rejectionReason(
                            "Invalid entry price or stop loss")
                    .build();
        }

        double riskPerShare =
                Math.abs(entryPrice - stopLoss);

        if (riskPerShare <= 0) {

            log.warn(
                    "RISK SIZING REJECTED | Symbol={} | Entry={} | " +
                            "StopLoss={} | RiskPerShare={}",
                    signal.getSymbol(),
                    entryPrice,
                    stopLoss,
                    riskPerShare);

            return PositionSizingResult.builder()
                    .valid(false)
                    .entryPrice(entryPrice)
                    .stopLoss(stopLoss)
                    .riskPerShare(riskPerShare)
                    .maxRiskPerTrade(maxRiskPerTrade)
                    .riskBasedQuantity(0)
                    .expectedRisk(0)
                    .rejectionReason(
                            "Risk per share must be greater than zero")
                    .build();
        }

        int riskBasedQuantity =
                (int) Math.floor(
                        maxRiskPerTrade / riskPerShare);

        if (riskBasedQuantity <= 0) {

            log.warn(
                    "RISK SIZING REJECTED | Risk per share too high | " +
                            "Symbol={} | Entry={} | StopLoss={} | " +
                            "RiskPerShare={} | MaxRisk={}",
                    signal.getSymbol(),
                    entryPrice,
                    stopLoss,
                    riskPerShare,
                    maxRiskPerTrade);

            return PositionSizingResult.builder()
                    .valid(false)
                    .entryPrice(entryPrice)
                    .stopLoss(stopLoss)
                    .riskPerShare(riskPerShare)
                    .maxRiskPerTrade(maxRiskPerTrade)
                    .riskBasedQuantity(0)
                    .expectedRisk(0)
                    .rejectionReason(
                            "Risk per share exceeds maximum risk")
                    .build();
        }

        double expectedRisk =
                riskBasedQuantity * riskPerShare;

        log.info(
                "RISK BASED POSITION SIZING | " +
                        "Symbol={} | Entry={} | StopLoss={} | " +
                        "RiskPerShare={} | MaxRisk={} | " +
                        "RiskQty={} | ExpectedRisk={}",
                signal.getSymbol(),
                entryPrice,
                stopLoss,
                riskPerShare,
                maxRiskPerTrade,
                riskBasedQuantity,
                expectedRisk);

        return PositionSizingResult.builder()
                .valid(true)
                .entryPrice(entryPrice)
                .stopLoss(stopLoss)
                .riskPerShare(riskPerShare)
                .maxRiskPerTrade(maxRiskPerTrade)
                .riskBasedQuantity(riskBasedQuantity)
                .expectedRisk(expectedRisk)
                .build();
    }
}