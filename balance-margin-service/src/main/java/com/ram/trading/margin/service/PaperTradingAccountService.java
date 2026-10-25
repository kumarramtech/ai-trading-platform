package com.ram.trading.margin.service;

import com.ram.trading.margin.entity.PaperTradingAccount;
import reactor.core.publisher.Mono;

public interface PaperTradingAccountService {

    Mono<PaperTradingAccount> getOrCreateAccount();

    Mono<Void> reserveMargin(Double requiredMargin);

    Mono<Void> releaseMargin(Double requiredMargin, Double profitLoss);

    Mono<Void> resetAccount();

    /**
     * Returns today's opening broker funds, recording {@code currentBrokerFunds}
     * as the opening value on the first call of the day.
     */
    Mono<Double> brokerOpeningFunds(double currentBrokerFunds);
}