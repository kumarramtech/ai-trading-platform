package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.IndicatorClient;
import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import com.ram.trading.signal.engine.repo.TradingSignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SignalMonitorService {

    private final TradingSignalRepository repository;

    private final StockServiceClient stockClient;


    public void checkOpenSignals() {

        List<TradingSignalEntity> signals =
                repository.findByStatus(SignalStatus.OPEN);

        for (TradingSignalEntity signal : signals) {

            boolean updated = false;
            StockResponse stockResponse = stockClient.getStockPrice(signal.getSymbol()).block();
            if (stockResponse == null) {
                continue;
            }
            Double currentPrice = stockResponse.getPrice();
            if (SignalType.BUY.name().equals(signal.getSignal())) {

                System.out.println(
                        "Entry=" + signal.getEntryPrice()
                                + ", Target=" + signal.getTargetPrice()
                                + ", StopLoss=" + signal.getStopLoss()
                                + ", Current=" + currentPrice);

                if (currentPrice >= signal.getTargetPrice()) {

                    signal.setStatus(SignalStatus.TARGET_HIT);
                    updated = true;
                    signal.setExitPrice(currentPrice);

                    signal.setProfitLoss(
                            currentPrice -
                                    signal.getEntryPrice());

                    signal.setExitTime(
                            LocalDateTime.now());
                }

                else if (currentPrice <= signal.getStopLoss()) {

                    signal.setStatus(SignalStatus.STOP_LOSS_HIT);
                    updated = true;
                    signal.setExitPrice(currentPrice);

                    signal.setProfitLoss(
                            currentPrice -
                                    signal.getEntryPrice());

                    signal.setExitTime(
                            LocalDateTime.now());
                }
            }
            else if (SignalType.SELL.name().equals(signal.getSignal())) {

                if (currentPrice <= signal.getTargetPrice()) {

                    signal.setStatus(SignalStatus.TARGET_HIT);
                    updated = true;
                    signal.setExitPrice(currentPrice);

                    signal.setProfitLoss(
                            signal.getEntryPrice()
                                    - currentPrice);

                    signal.setExitTime(
                            LocalDateTime.now());
                }

                else if (currentPrice >= signal.getStopLoss()) {

                    signal.setStatus(SignalStatus.STOP_LOSS_HIT);
                    updated = true;
                    signal.setExitPrice(currentPrice);

                    signal.setProfitLoss(
                            signal.getEntryPrice()
                                    - currentPrice);

                    signal.setExitTime(
                            LocalDateTime.now());
                }
            }

            if (updated) {
                System.out.println(
                        "Signal Closed: "
                                + signal.getSymbol()
                                + " Status="
                                + signal.getStatus());
                repository.save(signal);
            }
        }


    }
}