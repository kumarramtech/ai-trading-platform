package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.repo.TradingSignalRepository;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TradingSignalService {
    
        private final TradingSignalRepository repository;
    
        public TradingSignalEntity save(TradingSignal signal){
            TradingSignalEntity entity = TradingSignalEntity.builder()
                    .symbol(signal.getSymbol())
                    .signal(signal.getSignal())
                    .entryPrice(signal.getEntryPrice())
                    .targetPrice(signal.getTargetPrice())
                    .stopLoss(signal.getStopLoss())
                    .confidence(signal.getConfidence())
                    .reason(signal.getReason())
                    .signalTime(LocalDateTime.now())
                    .status(SignalStatus.OPEN)
                    .createdAt(LocalDateTime.now())
                    .build();

            return repository.save(entity);
        }
    
        public List<TradingSignalEntity> getHistory(
                String symbol) {
    
            return repository.findBySymbol(symbol);
        }

    public List<TradingSignalEntity> findByStatus(String open) {

        return repository.findByStatus(SignalStatus.OPEN);
    }
}