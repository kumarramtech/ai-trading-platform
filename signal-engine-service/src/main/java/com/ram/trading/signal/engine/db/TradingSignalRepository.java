package com.ram.trading.signal.engine.db;

import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TradingSignalRepository
        extends JpaRepository<TradingSignalEntity, Long> {
}