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
public class PaperTradingAccountServiceImpl
        implements PaperTradingAccountService {

    private final PaperTradingAccountRepository accountRepository;

    private final PaperTradingAccountTransactionService
            accountTransactionService;

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

    /**
     * The actual balance mutation is executed inside a separate
     * transactional service. The transaction must remain open while
     * the pessimistic row lock is held.
     */
    @Override
    public Mono<Void> reserveMargin(Double requiredMargin) {

        return Mono.fromRunnable(() ->
                        accountTransactionService
                                .reserveMargin(requiredMargin))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * Releases the reserved margin and applies the realized P/L.
     *
     * The margin is returned first and the realized P/L is then applied:
     *
     * available = available + requiredMargin + profitLoss
     *
     * The operation is performed atomically while the account row is
     * pessimistically locked.
     */
    @Override
    public Mono<Void> releaseMargin(
            Double requiredMargin,
            Double profitLoss) {

        return Mono.fromRunnable(() ->
                        accountTransactionService
                                .releaseMargin(
                                        requiredMargin,
                                        profitLoss))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
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
