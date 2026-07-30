package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.service.interfac.MarketDataProvider;
import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import com.ram.trading.signal.engine.repo.TradingSignalRepository;
import com.ram.trading.signal.engine.util.TradingSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SignalMonitorService {

    private final TradingSignalRepository repository;

    private final MarketDataProvider marketDataProvider;

    private final PaperTradingService paperTradingService;

    private final TradingSessionService tradingSessionService;


    public void checkOpenSignals() {

        if (!tradingSessionService.isMarketOpen()) {

            log.debug("Market closed. Skipping signal monitoring.");

            return;
        }

        List<TradingSignalEntity> signals =
                repository.findByStatus(SignalStatus.OPEN);

        for (TradingSignalEntity signal : signals) {

            boolean updated = false;
            StockResponse stockResponse = null;
            try {
                stockResponse = marketDataProvider.getStockPrice(signal.getSymbol()).block();
            }
            catch (Exception ex) {
                log.error("Unable to fetch price for {}", signal.getSymbol(), ex);
                continue;
            }
            if (stockResponse == null) {
                continue;
            }
            Double currentPrice = stockResponse.getPrice();

            log.info(
                    "Entry=" + signal.getEntryPrice()
                            + ", Target=" + signal.getTargetPrice()
                            + ", StopLoss=" + signal.getStopLoss()
                            + ", Current=" + currentPrice);

            if (SignalType.BUY.name().equals(signal.getSignal())) {

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
                log.info(
                        "Signal Closed: "
                                + signal.getSymbol()
                                + " Status="
                                + signal.getStatus());
                repository.save(signal);

                paperTradingService.closeTrade(
                        signal.getId(),
                        currentPrice,
                        signal.getStatus());
            }
        }


    }
}