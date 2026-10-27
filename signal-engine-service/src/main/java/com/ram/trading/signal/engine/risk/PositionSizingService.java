package com.ram.trading.signal.engine.risk;

import com.ram.trading.signal.engine.client.BalanceMarginClient;
import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.dto.InstrumentResponse;
import com.ram.trading.signal.engine.dto.MarginCalculationResponse;
import com.ram.trading.signal.engine.dto.MarginInstrumentRequest;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.UpstoxMarginRequest;
import com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator;
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
    private final IntradayChargesCalculator chargesCalculator;

    @Value("${trading.margin-per-trade:10000}")
    private double targetMarginPerTrade;

    @Value("${trading.risk.max-loss-per-trade:250}")
    private double maxLossPerTrade;

    /**
     * Planned loss at the stop-loss for one trade. Quantity is capped so that
     * quantity x |entry - stop| never exceeds it. 0 disables the cap.
     *
     * The broker-side stop sits at the strategy stop, so this is the loss that
     * actually happens if Signal Engine, Trade Execution or the network is down.
     * Keep it below max-loss-per-trade so the hard-loss guard stays a gap/slippage
     * backstop instead of pre-empting the strategy stop.
     */
    @Value("${trading.risk.risk-per-trade:200}")
    private double riskPerTrade = 200;

    /**
     * When true, the risk cap budgets the full loss of a stopped-out trade:
     * the stop distance plus adverse slippage on both fills plus round-trip
     * charges. Without it a tight stop on a cheap stock overshoots the budget
     * (RENUKA 2026-10-27: Rs 250 budget, Rs 357 realised).
     */
    @Value("${trading.risk.include-costs-in-sizing:true}")
    private boolean includeCostsInSizing = true;

    /** Adverse slippage per fill, % of price; the same value paper fills use. */
    @Value("${paper.trading.slippage-pct:0.03}")
    private double slippagePct = 0.03;

    /**
     * Position sizing starts from the broker's actual margin requirement.
     *
     * The configured ₹10,000 is the margin allocation per trade.
     * Broker leverage increases exposure; it does NOT increase our
     * allocated margin. The quantity is then capped by risk-per-trade,
     * which may leave part of the margin allocation unused.
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
         * First determine broker margin for one share.
         * Then iteratively move quantity toward the configured
         * target margin.
         *
         * Every final quantity is rechecked with the broker
         * margin API.
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

            /*
             * Accept the quantity when the broker margin is
             * between 95% and 100% of the configured target.
             *
             * Example:
             * Target = ₹10,000
             * Acceptable range = ₹9,500 to ₹10,000
             */
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

            /*
             * Do not exceed the maximum quantity that can be
             * supported by the currently available balance.
             */
            if (Boolean.TRUE.equals(marginResponse.getSufficientBalance())
                    && marginResponse.getMaximumQuantity() > 0) {

                nextQuantity = Math.min(
                        nextQuantity,
                        marginResponse.getMaximumQuantity());
            }

            /*
             * If the current margin is already above the target,
             * quantity must be reduced.
             */
            if (requiredMargin > targetMarginPerTrade) {
                nextQuantity = Math.min(
                        nextQuantity,
                        quantity - 1);
            }

            if (nextQuantity <= 0 || nextQuantity == quantity) {
                break;
            }

            quantity = nextQuantity;
        }

        /*
         * Risk cap: the loss at the stop must not exceed risk-per-trade.
         * A capped quantity is re-priced with the broker margin API.
         */
        boolean riskCapped = false;

        if (riskPerTrade > 0 && marginResponse != null) {

            int riskQuantity = riskQuantity(signal, entryPrice, stopLoss, riskPerShare);

            if (riskQuantity < 1) {
                return rejected(
                        signal,
                        entryPrice,
                        stopLoss,
                        riskPerShare,
                        "Stop distance " + riskPerShare
                                + " per share exceeds the per-trade risk budget " + riskPerTrade);
            }

            if (quantity > riskQuantity) {

                log.info(
                        "RISK BASED QUANTITY CAP | Symbol={} | MarginQty={} | RiskQty={} | "
                                + "RiskPerShare={} | RiskPerTrade={}",
                        signal.getSymbol(),
                        quantity,
                        riskQuantity,
                        riskPerShare,
                        riskPerTrade);

                quantity = riskQuantity;
                riskCapped = true;

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
                            "Broker margin unavailable for risk-capped quantity");
                }
            }
        }

        /*
         * Final broker margin response must be available.
         */
        if (marginResponse == null
                || marginResponse.getRequiredMargin() == null) {

            return rejected(
                    signal,
                    entryPrice,
                    stopLoss,
                    riskPerShare,
                    "Final broker margin response unavailable");
        }

        double requiredMargin =
                marginResponse.getRequiredMargin();

        /*
         * Do not allow a trade above the configured margin allocation.
         */
        if (requiredMargin > targetMarginPerTrade) {

            return rejected(
                    signal,
                    entryPrice,
                    stopLoss,
                    riskPerShare,
                    "Required margin exceeds target margin");
        }

        /*
         * IMPORTANT:
         * Do not allow very small trades when the system could not
         * allocate approximately the configured ₹10,000 margin.
         *
         * Target = ₹10,000
         * Minimum acceptable = ₹9,500
         *
         * This prevents trades such as:
         * ₹701 margin
         * ₹306 margin
         * etc.
         */
        double minimumAcceptableMargin =
                targetMarginPerTrade * 0.95;

        // A risk-capped quantity is deliberately smaller than the margin allocation.
        if (!riskCapped && requiredMargin < minimumAcceptableMargin) {

            return rejected(
                    signal,
                    entryPrice,
                    stopLoss,
                    riskPerShare,
                    "Required margin is below minimum target allocation");
        }

        /*
         * Verify that the paper/broker account has sufficient
         * available margin.
         */
        if (!Boolean.TRUE.equals(
                marginResponse.getSufficientBalance())) {

            return rejected(
                    signal,
                    entryPrice,
                    stopLoss,
                    riskPerShare,
                    "Insufficient available margin");
        }

        /*
         * Expected stop-loss risk (incl. slippage and charges when enabled)
         * is bounded by risk-per-trade above.
         * The ₹250 max-loss value remains the EXIT trigger handled by
         * the exit service (gap/slippage backstop).
         */
        double expectedStopLossRisk =
                stopOutLoss(signal, entryPrice, stopLoss, riskPerShare, quantity);

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
                .riskBasedQuantity(riskPerTrade > 0
                        ? riskQuantity(signal, entryPrice, stopLoss, riskPerShare) : quantity)
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

    /**
     * Largest quantity whose stop-out loss stays within risk-per-trade.
     * Charges depend on quantity (brokerage is capped per order), so the
     * estimate is refined a few times and then stepped down until it fits.
     */
    private int riskQuantity(
            TradingSignal signal,
            double entryPrice,
            double stopLoss,
            double riskPerShare) {

        int quantity = (int) Math.floor(riskPerTrade / riskPerShare);

        if (!includeCostsInSizing || quantity < 1) {
            return quantity;
        }

        double lossPerShare = riskPerShare + slippagePerShare(entryPrice, stopLoss);

        quantity = (int) Math.floor(riskPerTrade / lossPerShare);

        for (int i = 0; i < 5 && quantity > 0; i++) {
            double charges = stopOutCharges(signal, entryPrice, stopLoss, quantity);
            int next = (int) Math.floor((riskPerTrade - charges) / lossPerShare);
            if (next == quantity) {
                break;
            }
            quantity = next;
        }

        while (quantity > 0
                && stopOutLoss(signal, entryPrice, stopLoss, riskPerShare, quantity) > riskPerTrade) {
            quantity--;
        }

        return Math.max(quantity, 0);
    }

    private double stopOutLoss(
            TradingSignal signal,
            double entryPrice,
            double stopLoss,
            double riskPerShare,
            int quantity) {

        if (!includeCostsInSizing) {
            return quantity * riskPerShare;
        }

        return quantity * (riskPerShare + slippagePerShare(entryPrice, stopLoss))
                + stopOutCharges(signal, entryPrice, stopLoss, quantity);
    }

    private double slippagePerShare(double entryPrice, double stopLoss) {
        return (entryPrice + stopLoss) * slippagePct / 100.0;
    }

    private double stopOutCharges(
            TradingSignal signal,
            double entryPrice,
            double stopLoss,
            int quantity) {

        return chargesCalculator
                .roundTrip(signal.getSignal(), entryPrice, stopLoss, quantity)
                .getTotal();
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
