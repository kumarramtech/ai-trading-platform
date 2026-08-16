package com.ram.trading.margin.service;

import com.ram.trading.margin.entity.PaperTradingAccount;
import reactor.core.publisher.Mono;

public interface PaperTradingAccountService {

    Mono<PaperTradingAccount> getOrCreateAccount();

    Mono<PaperTradingAccount> reserveMargin(Double requiredMargin);

    Mono<PaperTradingAccount> releaseMargin(
            Double requiredMargin,
            Double profitLoss
    );
}