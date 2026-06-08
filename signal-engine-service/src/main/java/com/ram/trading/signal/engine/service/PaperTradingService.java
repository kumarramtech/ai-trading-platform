package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaperTradingService {

    private final PaperTradeRepository repository;

    public void createTrade(
            TradingSignal signal) {

        System.out.println(
                "Signal Generated = "
                        + signal.getSignal());

        System.out.println(
                "Inside createTrade");

        if (!"BUY".equals(signal.getSignal())) {
            return;
        }

        double capitalPerTrade = 10000;

        int quantity =
                (int) (capitalPerTrade
                        / signal.getEntryPrice());

        System.out.println(
                quantity);
        if (quantity <= 0) {
            return;
        }

        PaperTrade trade =
                PaperTrade.builder()
                        .symbol(signal.getSymbol())
                        .signal(signal.getSignal())
                        .entryPrice(signal.getEntryPrice())
                        .quantity(quantity)
                        .investedAmount(
                                quantity *
                                        signal.getEntryPrice())
                        .status(SignalStatus.OPEN)
                        .entryTime(LocalDateTime.now())
                        .build();

        repository.save(trade);
    }

    public List<PaperTrade> getByStatus(SignalStatus status) {

        return repository.findByStatus(status);
    }

    public List<PaperTrade> getAll() {
        return repository.findAll();
    }
}