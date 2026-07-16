package com.ram.trading.stock.service.instument;

import com.ram.trading.stock.dto.InstrumentLookupResponse;
import com.ram.trading.stock.entity.Instrument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface InstrumentService {

    Instrument save(Instrument instrument);

    List<Instrument> saveAll(List<Instrument> instruments);

    Optional<Instrument> findByTradingSymbol(String tradingSymbol);

    Optional<Instrument> findByInstrumentKey(String instrumentKey);

    List<Instrument> findActiveByExchange(String exchange);

    List<Instrument> findAll();

    Instrument getActiveInstrument(String tradingSymbol);

    void deleteAll();

    List<Instrument> findTradableEquities();

    Page<Instrument> findTradableEquities(Pageable pageable);

}
