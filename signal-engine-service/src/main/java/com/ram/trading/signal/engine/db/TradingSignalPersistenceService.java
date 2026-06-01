package com.ram.trading.signal.engine.db;

import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TradingSignalPersistenceService {

    private final TradingSignalRepository repository;

    public void save(TradingSignal signal) {

        TradingSignalEntity entity = new TradingSignalEntity();

        entity.setSymbol(signal.getSymbol());
        entity.setSignal(signal.getSignal());
        entity.setEntryPrice(signal.getEntryPrice());
        entity.setTargetPrice(signal.getTargetPrice());
        entity.setStopLoss(signal.getStopLoss());

        repository.save(entity);
    }
}