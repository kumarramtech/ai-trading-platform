package com.ram.trading.newsanalysis.repo;

import com.ram.trading.newsanalysis.entity.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InstrumentMasterRepository
        extends JpaRepository<Instrument, Long> {

    Instrument findByTradingSymbol(String tradingSymbol);

}