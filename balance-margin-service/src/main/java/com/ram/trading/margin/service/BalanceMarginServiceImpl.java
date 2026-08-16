package com.ram.trading.margin.service;


import com.ram.trading.margin.client.UpstoxClient;
import com.ram.trading.margin.dto.*;
import com.ram.trading.margin.entity.PaperTradingAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class BalanceMarginServiceImpl
        implements BalanceMarginService {

    private final UpstoxClient upstoxClient;

    private final PaperTradingAccountService paperTradingAccountService;

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
                paperTradingAccountService.getOrCreateAccount()
        ).map(tuple -> {

            UpstoxMarginResponse marginResponse = tuple.getT1();

            PaperTradingAccount account = tuple.getT2();

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

            // Current virtual paper trading balance
            double availableBalance =
                    account.getAvailableBalance();

            // Actual leverage based on Upstox margin
            double leverage = requiredMargin > 0
                    ? tradeValue / requiredMargin
                    : 0;

            // Check whether current paper balance is sufficient
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
}