package com.ram.trading.signal.engine.repo;

import com.ram.trading.signal.engine.entity.ShadowTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ShadowTradeRepository
        extends JpaRepository<ShadowTrade, Long> {

    List<ShadowTrade> findByStatus(String status);

    List<ShadowTrade> findByEntryTimeGreaterThanEqual(LocalDateTime from);
}
