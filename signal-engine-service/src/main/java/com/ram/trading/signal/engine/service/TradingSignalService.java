package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.db.TradingSignalRepository;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TradingSignalService {
    
        private final TradingSignalRepository repository;
    
        public void save(TradingSignal tradeSignal){
            TradingSignalEntity entity = new TradingSignalEntity();
            entity.setSymbol(tradeSignal.getSymbol());
            entity.setSignal(tradeSignal.getSignal());
            entity.setEntryPrice(tradeSignal.getEntryPrice());
            entity.setTargetPrice(tradeSignal.getTargetPrice());
            entity.setStopLoss(tradeSignal.getStopLoss());
            repository.save(entity);
        }
    
        public List<TradingSignalEntity> getHistory(
                String symbol) {
    
            return repository.findBySymbol(symbol);
        }
    }