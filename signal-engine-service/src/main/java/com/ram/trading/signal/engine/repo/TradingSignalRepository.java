package com.ram.trading.signal.engine.repo;

import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TradingSignalRepository
        extends JpaRepository<TradingSignalEntity, Long> {

    List<TradingSignalEntity> findBySymbol(String symbol);

    List<TradingSignalEntity> findByStatus(SignalStatus status);

    Optional<TradingSignalEntity> findById(Long id);
}