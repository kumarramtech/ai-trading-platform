package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.RiskAnalysisResponse;
import com.ram.trading.signal.engine.repo.TradingSignalRepository;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
                    .entryPrice(round(signal.getEntryPrice()))
                    .targetPrice(round(signal.getTargetPrice()))
                    .stopLoss(round(signal.getStopLoss()))
                    .confidence(signal.getConfidence())
                    .reason(signal.getReason())
                    .signalTime(LocalDateTime.now())
                    .status(SignalStatus.OPEN)
                    .createdAt(LocalDateTime.now())
                    .build();

            return repository.save(entity);
        }

    private Double round(Double value) {

        if (value == null) {
            return null;
        }

        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
    
        public List<TradingSignalEntity> getHistory(
                String symbol) {
    
            return repository.findBySymbol(symbol);
        }

    public List<TradingSignalEntity> findByStatus(String open) {

        return repository.findByStatus(SignalStatus.OPEN);
    }


    public TradingSignalEntity findById(Long signalId) {
        return repository.findById(signalId).orElse(null);
    }
}