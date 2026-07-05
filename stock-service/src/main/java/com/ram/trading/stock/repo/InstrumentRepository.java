package com.ram.trading.stock.repo;

import com.ram.trading.stock.entity.Instrument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InstrumentRepository
        extends JpaRepository<Instrument, Long> {

    Optional<Instrument> findByTradingSymbol(String tradingSymbol);

    Optional<Instrument> findByInstrumentKey(String instrumentKey);

    List<Instrument> findByExchange(String exchange);

    Optional<Instrument> findByTradingSymbolAndIsActiveTrue(
            String tradingSymbol);

    Optional<Instrument> findByTradingSymbolAndExchangeAndIsActiveTrue(
            String tradingSymbol,
            String exchange);
    List<Instrument> findByExchangeAndSegmentAndInstrumentTypeAndIsActiveTrue(
            String exchange,
            String segment,
            String instrumentType);

    Page<Instrument> findByExchangeAndSegmentAndInstrumentTypeAndIsActiveTrue(
            String exchange,
            String segment,
            String instrumentType, Pageable pageable);

}