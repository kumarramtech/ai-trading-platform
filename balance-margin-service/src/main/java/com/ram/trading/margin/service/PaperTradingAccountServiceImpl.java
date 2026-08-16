package com.ram.trading.margin.service;

import com.ram.trading.margin.entity.PaperTradingAccount;
import com.ram.trading.margin.repo.PaperTradingAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PaperTradingAccountServiceImpl
        implements PaperTradingAccountService {

    private final PaperTradingAccountRepository accountRepository;

    @Value("${paper.trading.initial-balance}")
    private Double initialBalance;

    @Override
    public Mono<PaperTradingAccount> getOrCreateAccount() {

        return Mono.fromCallable(() ->
                        accountRepository.findAll()
                                .stream()
                                .findFirst()
                                .orElseGet(() -> {

                                    LocalDateTime now =
                                            LocalDateTime.now();

                                    PaperTradingAccount account =
                                            PaperTradingAccount.builder()
                                                    .initialBalance(initialBalance)
                                                    .availableBalance(initialBalance)
                                                    .usedMargin(0.0)
                                                    .realizedPnl(0.0)
                                                    .createdAt(now)
                                                    .updatedAt(now)
                                                    .build();

                                    return accountRepository.save(account);
                                })
                )
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<PaperTradingAccount> reserveMargin(
            Double requiredMargin) {

        return getOrCreateAccount()
                .flatMap(account ->
                        Mono.fromCallable(() -> {

                            if (account.getAvailableBalance()
                                    < requiredMargin) {

                                throw new IllegalStateException(
                                        "Insufficient paper trading balance");
                            }

                            account.setAvailableBalance(
                                    account.getAvailableBalance()
                                            - requiredMargin
                            );

                            account.setUsedMargin(
                                    account.getUsedMargin()
                                            + requiredMargin
                            );

                            account.setUpdatedAt(
                                    LocalDateTime.now()
                            );

                            return accountRepository.save(account);
                        })
                        .subscribeOn(Schedulers.boundedElastic())
                );
    }

    @Override
    public Mono<PaperTradingAccount> releaseMargin(
            Double requiredMargin,
            Double profitLoss) {

        return getOrCreateAccount()
                .flatMap(account ->
                        Mono.fromCallable(() -> {

                            double pnl = profitLoss != null
                                    ? profitLoss
                                    : 0.0;

                            account.setAvailableBalance(
                                    account.getAvailableBalance()
                                            + requiredMargin
                                            + pnl
                            );

                            account.setUsedMargin(
                                    Math.max(
                                            0.0,
                                            account.getUsedMargin()
                                                    - requiredMargin
                                    )
                            );

                            account.setRealizedPnl(
                                    account.getRealizedPnl()
                                            + pnl
                            );

                            account.setUpdatedAt(
                                    LocalDateTime.now()
                            );

                            return accountRepository.save(account);
                        })
                        .subscribeOn(Schedulers.boundedElastic())
                );
    }
}