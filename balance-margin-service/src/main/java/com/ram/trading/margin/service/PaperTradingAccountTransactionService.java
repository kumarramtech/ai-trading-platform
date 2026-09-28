package com.ram.trading.margin.service;

import com.ram.trading.margin.entity.PaperTradingAccount;
import com.ram.trading.margin.repo.PaperTradingAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Contains the database transaction boundary for paper-trading
 * margin mutations.
 *
 * The repository query uses PESSIMISTIC_WRITE, so the account row is
 * locked while reserve/release is calculated and persisted. This prevents
 * concurrent trading requests from reading the same available balance and
 * both reserving against it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaperTradingAccountTransactionService {

    private final PaperTradingAccountRepository accountRepository;

    @Transactional
    public void reserveMargin(Double requiredMargin) {

        validateMargin(requiredMargin);

        PaperTradingAccount account =
                accountRepository.findFirstByOrderByIdAscForUpdate()
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Paper trading account not found"));

        double availableBalance =
                safe(account.getAvailableBalance());

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
                safe(account.getUsedMargin()) + requiredMargin);

        account.setUpdatedAt(LocalDateTime.now());

        accountRepository.save(account);

        log.info(
                "Margin Reserved | Required Margin = {} | "
                        + "Available Balance = {} | Used Margin = {}",
                requiredMargin,
                account.getAvailableBalance(),
                account.getUsedMargin());
    }

    @Transactional
    public void releaseMargin(
            Double requiredMargin,
            Double profitLoss) {

        validateMargin(requiredMargin);

        double pnl = safe(profitLoss);

        PaperTradingAccount account =
                accountRepository.findFirstByOrderByIdAscForUpdate()
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Paper trading account not found"));

        double availableBalance =
                safe(account.getAvailableBalance());

        double usedMargin =
                safe(account.getUsedMargin());

        double realizedPnl =
                safe(account.getRealizedPnl());

        double newAvailableBalance =
                availableBalance
                        + requiredMargin
                        + pnl;

        double newUsedMargin =
                Math.max(
                        0.0,
                        usedMargin - requiredMargin);

        account.setAvailableBalance(newAvailableBalance);
        account.setUsedMargin(newUsedMargin);
        account.setRealizedPnl(realizedPnl + pnl);
        account.setUpdatedAt(LocalDateTime.now());

        accountRepository.save(account);

        log.info(
                "Margin Released | Required Margin = {} | "
                        + "Profit/Loss = {} | "
                        + "Available Balance = {} | "
                        + "Used Margin = {} | "
                        + "Realized PnL = {}",
                requiredMargin,
                pnl,
                account.getAvailableBalance(),
                account.getUsedMargin(),
                account.getRealizedPnl());
    }

    private void validateMargin(Double requiredMargin) {

        if (requiredMargin == null
                || !Double.isFinite(requiredMargin)
                || requiredMargin <= 0.0) {

            throw new IllegalArgumentException(
                    "Required margin must be greater than zero");
        }
    }

    private double safe(Double value) {
        return value == null ? 0.0 : value;
    }
}
