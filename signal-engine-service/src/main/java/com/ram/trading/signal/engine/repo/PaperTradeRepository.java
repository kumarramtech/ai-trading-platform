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

    List<PaperTrade> findByStatus(SignalStatus status);

    List<PaperTrade> findBySymbol(String symbol);

    Optional<PaperTrade> findTopBySymbolAndStatusOrderByEntryTimeDesc(String symbol, SignalStatus status);

    Optional<PaperTrade> findBySignalId(Long signalId);

    Optional<PaperTrade> findBySignalIdAndStatus(
            Long signalId,
            SignalStatus status);

    Optional<PaperTrade> findFirstBySymbolAndStatus(String symbol,SignalStatus status);

    // Latest completed/recent trade for symbol
    Optional<PaperTrade> findTopBySymbolOrderByExitTimeDesc(String symbol);

    List<PaperTrade> findAllByOrderByEntryTimeDesc();
    List<PaperTrade> findTop20ByStatusNotOrderByIdDesc(SignalStatus status);
    long countByStatus(SignalStatus status);

    boolean existsBySymbolAndStatus(String symbol,SignalStatus status);

    List<PaperTrade> findAll();

    List<PaperTrade> findByStatusIn(List<SignalStatus> targetHit);
}