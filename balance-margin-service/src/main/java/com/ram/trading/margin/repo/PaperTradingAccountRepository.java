package com.ram.trading.margin.repo;

import com.ram.trading.margin.entity.PaperTradingAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PaperTradingAccountRepository
        extends JpaRepository<PaperTradingAccount, Long> {

    Optional<PaperTradingAccount> findFirstByOrderByIdAsc();

    /**
     * Locks the account row for the duration of the surrounding
     * database transaction.
     *
     * Used only by reserve/release operations so concurrent trading
     * requests cannot reserve/release against the same stale balance.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PaperTradingAccount a order by a.id asc")
    Optional<PaperTradingAccount> findFirstByOrderByIdAscForUpdate();
}
