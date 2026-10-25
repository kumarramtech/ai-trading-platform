package com.ram.trading.margin.service;


import com.ram.trading.margin.client.UpstoxClient;
import com.ram.trading.margin.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class BalanceMarginServiceImpl
        implements BalanceMarginService {

    private final UpstoxClient upstoxClient;

    private final PaperTradingAccountService paperTradingAccountService;

    /** paper | live — must match signal-engine execution.trade. */
    @Value("${balance.source:paper}")
    private String balanceSource = "paper";

    /** Capital the bot may use. Live mode never sizes beyond it, whatever Upstox holds. */
    @Value("${trading.allocated-capital:100000}")
    private double allocatedCapital = 100000;

    @Override
    public Mono<BalanceMarginResponse> getBalance() {

        return upstoxClient.getFundsAndMargin()
                .map(response -> {

                    UpstoxFundsResponse.DataResponse data =
                            response.getData();

                    return BalanceMarginResponse.builder()
                            .availableBalance(
                                    data.getAvailableToTrade().getTotal())
                            .cashAvailable(
                                    data.getAvailableToTrade()
                                            .getCashAvailableToTrade()
                                            .getTotal())
                            .pledgedMargin(
                                    data.getAvailableToTrade()
                                            .getPledgeAvailableToTrade()
                                            .getTotal())
                            .unsettledProfitToday(
                                    data.getUnavailableToTrade()
                                            .getCashUnavailableToTrade()
                                            .getUnsettledProfit()
                                            .getTodaysProfit())
                            .unsettledProfitPreviousDays(
                                    data.getUnavailableToTrade()
                                            .getCashUnavailableToTrade()
                                            .getUnsettledProfit()
                                            .getPreviousDays())
                            .build();
                });
    }

    @Override
    public Mono<MarginCalculationResponse> calculateMargin(
            UpstoxMarginRequest request) {

        return Mono.zip(
                upstoxClient.calculateMargin(request),
                availableBalance()
        ).map(tuple -> {

            UpstoxMarginResponse marginResponse = tuple.getT1();

            MarginInstrumentRequest instrument =
                    request.getInstruments().getFirst();

            double price = instrument.getPrice().doubleValue();

            double tradeValue =
                    price * instrument.getQuantity();

            double requiredMargin =
                    marginResponse.getData()
                            .getRequiredMargin()
                            .doubleValue();

            double finalMargin =
                    marginResponse.getData()
                            .getFinalMargin()
                            .doubleValue();

            // Paper account balance, or real Upstox funds in live mode
            double availableBalance = tuple.getT2();

            // Actual leverage based on Upstox margin
            double leverage = requiredMargin > 0
                    ? tradeValue / requiredMargin
                    : 0;

            // Check whether current balance is sufficient
            boolean sufficientBalance =
                    availableBalance >= requiredMargin;

            // Margin required for one share
            double marginPerShare =
                    instrument.getQuantity() > 0
                            ? requiredMargin / instrument.getQuantity()
                            : 0;

            // Maximum quantity possible with CURRENT balance
            int maximumQuantity = marginPerShare > 0
                    ? (int) Math.floor(
                    availableBalance / marginPerShare
            )
                    : 0;

            return MarginCalculationResponse.builder()
                    .instrumentKey(instrument.getInstrumentKey())
                    .quantity(instrument.getQuantity())
                    .transactionType(instrument.getTransactionType())
                    .product(instrument.getProduct())
                    .price(price)
                    .tradeValue(tradeValue)
                    .requiredMargin(requiredMargin)
                    .finalMargin(finalMargin)
                    .availableBalance(availableBalance)
                    .leverage(leverage)
                    .maximumQuantity(maximumQuantity)
                    .sufficientBalance(sufficientBalance)
                    .build();
        });
    }

    /**
     * Capital available for a new trade. Paper mode uses the virtual paper
     * account (reserved/released per paper trade, reset daily). Live mode must
     * use the real Upstox funds: live orders never touch the paper account, so
     * sizing against it would approve trades the broker then rejects. A missing
     * funds response errors, which makes position sizing reject the trade.
     *
     * Live mode is also capped at trading.allocated-capital: Upstox funds used
     * since the first check of the day (margin blocked by open positions plus
     * losses) count against it, so with Rs 2L in the account and a Rs 1L
     * allocation the bot never uses more than Rs 1L.
     */
    private Mono<Double> availableBalance() {
        if (isLive()) {
            return upstoxClient.getFundsAndMargin()
                    .map(response -> {
                        if (response == null || response.getData() == null
                                || response.getData().getAvailableToTrade() == null
                                || response.getData().getAvailableToTrade().getTotal() == null) {
                            throw new IllegalStateException(
                                    "Upstox funds response has no available-to-trade total");
                        }
                        return response.getData().getAvailableToTrade().getTotal();
                    })
                    .flatMap(brokerFunds -> paperTradingAccountService
                            .brokerOpeningFunds(brokerFunds)
                            .map(openingFunds -> {
                                double available = liveAvailable(brokerFunds, openingFunds, allocatedCapital);
                                log.info("LIVE CAPITAL | UpstoxFunds={} | OpeningFunds={} | Allocated={} | AvailableToBot={}",
                                        brokerFunds, openingFunds, allocatedCapital, available);
                                return available;
                            }));
        }
        return paperTradingAccountService.getOrCreateAccount()
                .map(account -> account.getAvailableBalance() == null
                        ? 0.0 : account.getAvailableBalance());
    }

    static double liveAvailable(double brokerFunds, double openingFunds, double allocatedCapital) {
        double usedToday = Math.max(0.0, openingFunds - brokerFunds);
        double budgetLeft = Math.max(0.0, allocatedCapital - usedToday);
        return Math.max(0.0, Math.min(brokerFunds, budgetLeft));
    }

    private boolean isLive() {
        return balanceSource != null && "live".equalsIgnoreCase(balanceSource.trim());
    }
}