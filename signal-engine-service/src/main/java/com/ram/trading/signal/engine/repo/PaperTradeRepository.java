package com.ram.trading.signal.engine.repo;

import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.entity.PaperTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaperTradeRepository
        extends JpaRepository<PaperTrade, Long> {

    List<PaperTrade> findByStatus(
            SignalStatus status);
}