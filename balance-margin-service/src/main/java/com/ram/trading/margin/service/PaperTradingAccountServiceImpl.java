package com.ram.trading.margin.service;

import com.ram.trading.margin.entity.PaperTradingAccount;
import com.ram.trading.margin.repo.PaperTradingAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaperTradingAccountServiceImpl implements PaperTradingAccountService {

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
    public Mono<Void> reserveMargin(Double requiredMargin) {

        return Mono.<Void>fromRunnable(() -> {

            PaperTradingAccount account =
                    accountRepository.findFirstByOrderByIdAsc()
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Paper trading account not found"));

            double availableBalance =
                    account.getAvailableBalance();

            if (availableBalance < requiredMargin) {

                throw new IllegalStateException(
                        "Insufficient balance. Available = "
                                + availableBalance
                                + ", Required Margin = "
                                + requiredMargin);
            }

            account.setAvailableBalance(
                    availableBalance - requiredMargin);

            account.setUsedMargin(
                    account.getUsedMargin() + requiredMargin);

            account.setUpdatedAt(LocalDateTime.now());

            accountRepository.save(account);

            log.info(
                    "Margin Reserved | Required Margin = {} | "
                            + "Available Balance = {} | Used Margin = {}",
                    requiredMargin,
                    account.getAvailableBalance(),
                    account.getUsedMargin());

        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> releaseMargin(
            Double requiredMargin,
            Double profitLoss) {

        return Mono.<Void>fromRunnable(() -> {

            PaperTradingAccount account =
                    accountRepository.findFirstByOrderByIdAsc()
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Paper trading account not found"));

            double newAvailableBalance =
                    account.getAvailableBalance()
                            + requiredMargin
                            + profitLoss;

            account.setAvailableBalance(newAvailableBalance);

            account.setUsedMargin(
                    Math.max(
                            0.0,
                            account.getUsedMargin() - requiredMargin));

            account.setRealizedPnl(
                    account.getRealizedPnl() + profitLoss);

            account.setUpdatedAt(LocalDateTime.now());

            accountRepository.save(account);

            log.info(
                    "Margin Released | Required Margin = {} | "
                            + "Profit/Loss = {} | "
                            + "Available Balance = {} | "
                            + "Used Margin = {} | "
                            + "Realized PnL = {}",
                    requiredMargin,
                    profitLoss,
                    account.getAvailableBalance(),
                    account.getUsedMargin(),
                    account.getRealizedPnl());

        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> resetAccount() {

        return Mono.<Void>fromRunnable(() -> {

            PaperTradingAccount account =
                    accountRepository
                            .findFirstByOrderByIdAsc()
                            .orElseGet(() -> {

                                LocalDateTime now =
                                        LocalDateTime.now();

                                PaperTradingAccount newAccount =
                                        PaperTradingAccount.builder()
                                                .initialBalance(initialBalance)
                                                .availableBalance(initialBalance)
                                                .usedMargin(0.0)
                                                .realizedPnl(0.0)
                                                .createdAt(now)
                                                .updatedAt(now)
                                                .build();

                                return accountRepository.save(newAccount);
                            });

            account.setInitialBalance(initialBalance);
            account.setAvailableBalance(initialBalance);
            account.setUsedMargin(0.0);
            account.setRealizedPnl(0.0);
            account.setUpdatedAt(LocalDateTime.now());

            accountRepository.save(account);

            log.info("========================================");
            log.info("PAPER TRADING ACCOUNT RESET SUCCESSFULLY");
            log.info("Initial Balance   : {}", initialBalance);
            log.info("Available Balance : {}", initialBalance);
            log.info("Used Margin       : 0.0");
            log.info("Realized PnL      : 0.0");
            log.info("========================================");

        }).subscribeOn(Schedulers.boundedElastic());
    }
}