package com.ram.trading.stock.service.instument;

import com.ram.trading.stock.entity.Instrument;
import com.ram.trading.stock.exceptions.InstrumentNotFoundException;
import com.ram.trading.stock.repo.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class InstrumentServiceImpl implements InstrumentService {

    private final InstrumentRepository repository;

    @Override
    public Instrument save(Instrument instrument) {
        return repository.save(instrument);
    }

    @Override
    public List<Instrument> saveAll(List<Instrument> instruments) {
        return repository.saveAll(instruments);
    }

    @Override
    public Optional<Instrument> findByTradingSymbol(String tradingSymbol) {
        return repository.findByTradingSymbol(tradingSymbol);
    }

    @Override
    public Optional<Instrument> findByInstrumentKey(String instrumentKey) {
        return repository.findByInstrumentKey(instrumentKey);
    }

    @Override
    public Instrument getActiveInstrument(String tradingSymbol) {

        return repository
                .findByTradingSymbolAndExchangeAndIsActiveTrue(
                        tradingSymbol,
                        "NSE")
                .orElseThrow(() ->
                        new InstrumentNotFoundException(tradingSymbol));

    }

    @Override
    public List<Instrument> findByExchange(String exchange) {
        return repository.findByExchange(exchange);
    }

    @Override
    public List<Instrument> findAll() {
        return repository.findAll();
    }

    @Override
    public void deleteAll() {
        repository.deleteAll();
    }
}