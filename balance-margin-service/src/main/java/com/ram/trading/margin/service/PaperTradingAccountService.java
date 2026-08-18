package com.ram.trading.margin.service;

import com.ram.trading.margin.entity.PaperTradingAccount;
import reactor.core.publisher.Mono;

public interface PaperTradingAccountService {

    Mono<PaperTradingAccount> getOrCreateAccount();

    Mono<Void> reserveMargin(Double requiredMargin);

    Mono<Void> releaseMargin(Double requiredMargin, Double profitLoss);

    Mono<Void> resetAccount();
}