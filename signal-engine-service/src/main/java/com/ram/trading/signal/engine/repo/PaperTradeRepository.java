package com.ram.trading.signal.engine.repo;

import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.entity.PaperTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaperTradeRepository
        extends JpaRepository<PaperTrade, Long> {

    List<PaperTrade> findByStatus(
            SignalStatus status);

    Optional<PaperTrade>
    findTopBySymbolAndStatusOrderByEntryTimeDesc(
            String symbol,
            SignalStatus status);

    Optional<PaperTrade> findBySignalId(
            Long signalId);

    Optional<PaperTrade> findBySignalIdAndStatus(
            Long signalId,
            SignalStatus status);

    List<PaperTrade> findAllByOrderByEntryTimeDesc();

    List<PaperTrade> findTop20ByStatusNotOrderByIdDesc(SignalStatus status);
}