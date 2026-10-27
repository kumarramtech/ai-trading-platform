package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.entity.TradingSignalEntity;
import com.ram.trading.signal.engine.repo.TradingSignalRepository;
import com.ram.trading.signal.engine.service.interfac.MarketDataProvider;
import com.ram.trading.signal.engine.util.TradingSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SignalMonitorService {

    private final TradingSignalRepository repository;

    private final MarketDataProvider marketDataProvider;

    private final TradingSessionService tradingSessionService;


    /**
     * ============================================================
     * MONITOR OPEN SIGNALS
     * ============================================================
     *
     * IMPORTANT:
     *
     * This service is now MONITOR-ONLY.
     *
     * It does NOT:
     *
     * - Close PaperTrade
     * - Mark TARGET_HIT
     * - Mark STOP_LOSS_HIT
     * - Call PaperTradingService.closeTrade()
     *
     * TradeExitServiceImpl is the SINGLE EXIT AUTHORITY.
     *
     * Exit flow:
     *
     * Market Tick
     *      ↓
     * TradeExitServiceImpl
     *      ↓
     * Trailing Stop
     *      ↓
     * Hard Max Loss
     *      ↓
     * Dynamic Target Extension
     *      ↓
     * ExitOrchestrator
     *      ↓
     * PaperTradingService.closeTrade()
     *
     * ============================================================
     */
    public void checkOpenSignals() {

        if (!tradingSessionService.isMarketOpen()) {

            log.debug(
                    "Market closed. Skipping signal monitoring.");

            return;
        }


        /*
         * ========================================================
         * FIND OPEN SIGNALS
         * ========================================================
         */

        /*
         * Intraday only: an OPEN signal from an earlier day is a leftover
         * (it has no open position) and is not worth a price call per minute.
         */
        LocalDate today = tradingSessionService.today();

        List<TradingSignalEntity> signals =
                repository.findByStatus(
                                SignalStatus.OPEN)
                        .stream()
                        .filter(signal -> signal.getCreatedAt() == null
                                || !signal.getCreatedAt().toLocalDate().isBefore(today))
                        .toList();


        /*
         * ========================================================
         * MONITOR EACH SIGNAL
         * ========================================================
         */

        for (TradingSignalEntity signal : signals) {

            StockResponse stockResponse;

            try {

                stockResponse =
                        marketDataProvider
                                .getStockPrice(
                                        signal.getSymbol())
                                .block();

            } catch (Exception ex) {

                log.error(
                        "Unable to fetch price for {}",
                        signal.getSymbol(),
                        ex);

                continue;
            }


            if (stockResponse == null) {

                continue;
            }


            Double currentPrice =
                    stockResponse.getPrice();


            /*
             * ====================================================
             * MONITORING LOG ONLY
             * ====================================================
             *
             * Do NOT compare target/SL here.
             *
             * Target and Stop Loss decisions belong to
             * TradeExitServiceImpl.
             * ====================================================
             */

            log.info(
                    "SIGNAL_MONITOR | " +
                            "Symbol={} | " +
                            "Entry={} | " +
                            "Target={} | " +
                            "StopLoss={} | " +
                            "Current={}",

                    signal.getSymbol(),

                    signal.getEntryPrice(),

                    signal.getTargetPrice(),

                    signal.getStopLoss(),

                    currentPrice);


            /*
             * ====================================================
             * OPTIONAL SAFETY LOGGING
             * ====================================================
             *
             * This is ONLY informational.
             *
             * It does NOT close or update the signal.
             * ====================================================
             */

            if (signal.getEntryPrice() != null) {

                double priceMove =
                        currentPrice -
                                signal.getEntryPrice();


                if ("SELL".equalsIgnoreCase(
                        signal.getSignal())) {

                    priceMove =
                            signal.getEntryPrice() -
                                    currentPrice;
                }


                log.debug(
                        "SIGNAL_MONITOR_PNL | " +
                                "Symbol={} | " +
                                "Signal={} | " +
                                "PriceMove={}",

                        signal.getSymbol(),

                        signal.getSignal(),

                        priceMove);
            }
        }
    }
}