package com.ram.trading.newsanalysis.repo;

import com.ram.trading.newsanalysis.entity.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InstrumentRepository
        extends JpaRepository<Instrument, Long> {

    Optional<Instrument> findByTradingSymbolAndExchangeAndIsActive(
            String tradingSymbol,
            String exchange,
            Boolean isActive);

}