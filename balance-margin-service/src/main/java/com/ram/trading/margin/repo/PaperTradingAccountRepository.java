package com.ram.trading.margin.repo;

import com.ram.trading.margin.entity.PaperTradingAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperTradingAccountRepository
        extends JpaRepository<PaperTradingAccount, Long> {
}