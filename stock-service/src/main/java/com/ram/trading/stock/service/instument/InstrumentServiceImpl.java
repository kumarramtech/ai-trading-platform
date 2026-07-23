package com.ram.trading.stock.service.instument;

import com.ram.trading.stock.bootstrap.properties.BootStrapProperties;
import com.ram.trading.stock.dto.InstrumentLookupResponse;
import com.ram.trading.stock.dto.InstrumentSubscriptionResponse;
import com.ram.trading.stock.entity.Instrument;
import com.ram.trading.stock.exceptions.InstrumentNotFoundException;
import com.ram.trading.stock.repo.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class InstrumentServiceImpl implements InstrumentService {

    private final InstrumentRepository repository;

    private final BootStrapProperties bootStrapProperties;

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
    public List<Instrument> findActiveByExchange(String exchange) {
        return repository.findByExchange(exchange);
    }

    @Override
    public List<Instrument> findAll() {
        return repository.findAll();
    }

    @Override
    public void deleteAll() {
        repository.deleteAllInBatch();
        repository.flush();
    }

    @Override
    public List<Instrument> findTradableEquities(){
        return repository
                .findByExchangeAndSegmentAndInstrumentTypeAndIsActiveTrue(
                        bootStrapProperties.getExchange(),
                        bootStrapProperties.getSegment(),
                        bootStrapProperties.getInstrumentType());
    }

    @Override
    public Page<Instrument> findTradableEquities(Pageable pageable) {

        return repository
                .findByExchangeAndSegmentAndInstrumentTypeAndIsActiveTrue(
                        bootStrapProperties.getExchange(),
                        bootStrapProperties.getSegment(),
                        bootStrapProperties.getInstrumentType(),
                        pageable);
    }

    @Override
    public List<InstrumentSubscriptionResponse> getSubscriptionInstruments() {

        return repository.findSubscriptionInstruments();
    }

}