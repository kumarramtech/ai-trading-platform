package com.ram.trading.signal.engine.risk;

import com.ram.trading.signal.engine.client.BalanceMarginClient;
import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.dto.InstrumentResponse;
import com.ram.trading.signal.engine.dto.MarginCalculationResponse;
import com.ram.trading.signal.engine.dto.MarginInstrumentRequest;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.UpstoxMarginRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PositionSizingService {

    private final BalanceMarginClient balanceMarginClient;
    private final StockServiceClient stockServiceClient;

    @Value("${trading.margin-per-trade:10000}")
    private double targetMarginPerTrade;

    @Value("${trading.risk.max-loss-per-trade:250}")
    private double maxLossPerTrade;

    /**
     * Position sizing is based on the broker's actual margin requirement.
     *
     * The configured ₹10,000 is the margin allocation per trade.
     * Broker leverage increases exposure; it does NOT increase our
     * allocated margin. The ₹250 value is an EXIT loss trigger and is
     * deliberately not used to reduce entry quantity.
     */
    public Mono<PositionSizingResult> calculate(
            TradingSignal signal) {

        return Mono.fromCallable(() -> calculateBlocking(signal))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private PositionSizingResult calculateBlocking(
            TradingSignal signal) {

        double entryPrice = signal.getEntryPrice();
        double stopLoss = signal.getStopLoss();

        if (entryPrice <= 0 || stopLoss <= 0) {
            return rejected(
                    signal,
                    entryPrice,
                    stopLoss,
                    0,
                    "Invalid entry price or stop loss");
        }

        double riskPerShare = Math.abs(entryPrice - stopLoss);

        if (riskPerShare <= 0) {
            return rejected(
                    signal,
                    entryPrice,
                    stopLoss,
                    riskPerShare,
                    "Risk per share must be greater than zero");
        }

        InstrumentResponse instrumentResponse =
                stockServiceClient
                        .getInstrument(signal.getSymbol())
                        .block();

        if (instrumentResponse == null
                || instrumentResponse.getInstrumentKey() == null
                || instrumentResponse.getInstrumentKey().isBlank()) {

            return rejected(
                    signal,
                    entryPrice,
                    stopLoss,
                    riskPerShare,
                    "Instrument key not found");
        }

        String instrumentKey = instrumentResponse.getInstrumentKey();
        int quantity = 1;
        MarginCalculationResponse marginResponse = null;

        /*
         * First determine broker margin for one share. Then iteratively
         * move quantity toward the configured target margin. Every final
         * quantity is rechecked with the broker margin API.
         */
        for (int attempt = 1; attempt <= 5; attempt++) {

            marginResponse = calculateMargin(
                    instrumentKey,
                    signal,
                    quantity);

            if (marginResponse == null
                    || marginResponse.getRequiredMargin() == null
                    || marginResponse.getRequiredMargin() <= 0) {

                return rejected(
                        signal,
                        entryPrice,
                        stopLoss,
                        riskPerShare,
                        "Broker margin unavailable");
            }

            double requiredMargin =
                    marginResponse.getRequiredMargin();

            log.info(
                    "MARGIN BASED POSITION SIZING | " +
                            "Symbol={} | Attempt={} | Qty={} | " +
                            "RequiredMargin={} | TargetMargin={} | Leverage={}",
                    signal.getSymbol(),
                    attempt,
                    quantity,
                    requiredMargin,
                    targetMarginPerTrade,
                    marginResponse.getLeverage());

            if (requiredMargin <= targetMarginPerTrade
                    && requiredMargin >= targetMarginPerTrade * 0.95) {
                break;
            }

            int nextQuantity =
                    (int) Math.floor(
                            quantity
                                    * (targetMarginPerTrade / requiredMargin));

            if (nextQuantity <= 0) {
                nextQuantity = 1;
            }

            if (Boolean.TRUE.equals(marginResponse.getSufficientBalance())
                    && marginResponse.getMaximumQuantity() > 0) {
                nextQuantity = Math.min(
                        nextQuantity,
                        marginResponse.getMaximumQuantity());
            }

            if (requiredMargin > targetMarginPerTrade) {
                nextQuantity = Math.min(nextQuantity, quantity - 1);
            }

            if (nextQuantity <= 0 || nextQuantity == quantity) {
                break;
            }

            quantity = nextQuantity;
        }

        if (marginResponse == null
                || marginResponse.getRequiredMargin() == null) {
            return rejected(
                    signal,
                    entryPrice,
                    stopLoss,
                    riskPerShare,
                    "Final broker margin response unavailable");
        }

        double requiredMargin = marginResponse.getRequiredMargin();

        if (requiredMargin > targetMarginPerTrade) {
            return rejected(
                    signal,
                    entryPrice,
                    stopLoss,
                    riskPerShare,
                    "Required margin exceeds target margin");
        }

        if (!Boolean.TRUE.equals(marginResponse.getSufficientBalance())) {
            return rejected(
                    signal,
                    entryPrice,
                    stopLoss,
                    riskPerShare,
                    "Insufficient available margin");
        }

        double expectedStopLossRisk = quantity * riskPerShare;

        log.info(
                "FINAL MARGIN SIZING | Symbol={} | Margin={} | " +
                        "Quantity={} | Exposure={} | Leverage={} | " +
                        "StopLossRisk={} | MaxLossTrigger={}",
                signal.getSymbol(),
                requiredMargin,
                quantity,
                marginResponse.getTradeValue(),
                marginResponse.getLeverage(),
                expectedStopLossRisk,
                maxLossPerTrade);

        return PositionSizingResult.builder()
                .valid(true)
                .entryPrice(entryPrice)
                .stopLoss(stopLoss)
                .riskPerShare(riskPerShare)
                .maxRiskPerTrade(maxLossPerTrade)
                .riskBasedQuantity(quantity) // backward compatibility
                .marginBasedQuantity(quantity)
                .expectedRisk(expectedStopLossRisk)
                .targetMargin(targetMarginPerTrade)
                .requiredMargin(requiredMargin)
                .tradeValue(marginResponse.getTradeValue())
                .leverage(marginResponse.getLeverage())
                .availableBalance(marginResponse.getAvailableBalance())
                .rejectionReason(null)
                .build();
    }

    private MarginCalculationResponse calculateMargin(
            String instrumentKey,
            TradingSignal signal,
            int quantity) {

        MarginInstrumentRequest instrument =
                MarginInstrumentRequest.builder()
                        .instrumentKey(instrumentKey)
                        .quantity(quantity)
                        .transactionType(signal.getSignal())
                        .product("I")
                        .price(BigDecimal.valueOf(signal.getEntryPrice()))
                        .build();

        return balanceMarginClient
                .calculateMargin(
                        UpstoxMarginRequest.builder()
                                .instruments(List.of(instrument))
                                .build())
                .block();
    }

    private PositionSizingResult rejected(
            TradingSignal signal,
            double entryPrice,
            double stopLoss,
            double riskPerShare,
            String reason) {

        log.warn(
                "MARGIN POSITION SIZING REJECTED | Symbol={} | Reason={}",
                signal.getSymbol(),
                reason);

        return PositionSizingResult.builder()
                .valid(false)
                .entryPrice(entryPrice)
                .stopLoss(stopLoss)
                .riskPerShare(riskPerShare)
                .maxRiskPerTrade(maxLossPerTrade)
                .riskBasedQuantity(0)
                .marginBasedQuantity(0)
                .expectedRisk(0)
                .targetMargin(targetMarginPerTrade)
                .rejectionReason(reason)
                .build();
    }
}
