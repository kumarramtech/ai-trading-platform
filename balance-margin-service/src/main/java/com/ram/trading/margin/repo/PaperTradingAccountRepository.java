package com.ram.trading.margin.repo;

import com.ram.trading.margin.entity.PaperTradingAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaperTradingAccountRepository
        extends JpaRepository<PaperTradingAccount, Long> {

    Optional<PaperTradingAccount> findFirstByOrderByIdAsc();

}