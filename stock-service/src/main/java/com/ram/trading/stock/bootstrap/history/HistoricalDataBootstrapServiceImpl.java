package com.ram.trading.stock.bootstrap.history;

import com.ram.trading.stock.dto.history.HistoricalCandleResponse;
import com.ram.trading.stock.entity.Instrument;
import com.ram.trading.stock.service.history.HistoricalCandleService;
import com.ram.trading.stock.service.instument.InstrumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@Service
@RequiredArgsConstructor
@Slf4j
public class HistoricalDataBootstrapServiceImpl
        implements HistoricalDataBootstrapService {

    private final InstrumentService instrumentService;

    private final HistoricalCandleService historicalCandleService;

    private final HistoricalPricePersistenceService persistenceService;

    private final HistoryBootstrapProperties properties;

    private static final int MINIMUM_REQUIRED_CANDLES = 60;

    @Override
    public void bootstrap() {

        log.info("Starting Historical Bootstrap...");

        int pageNo = 0;
        int batchSize = properties.getMaxSymbolsPerRun();

        ExecutorService executor = Executors.newFixedThreadPool(properties.getThreadPoolSize());
        try {
            Page<Instrument> page;
            do {
                page = instrumentService.findTradableEquities(
                        PageRequest.of(pageNo, batchSize));
                for (Instrument instrument : page.getContent()) {
                    if (!"NSE".equalsIgnoreCase(instrument.getExchange())) {
                        continue;
                    }
                    if (!"NSE_EQ".equalsIgnoreCase(instrument.getSegment())) {
                        continue;
                    }
                    if (!"EQ".equalsIgnoreCase(instrument.getInstrumentType())) {
                        continue;
                    }
                    executor.submit(() -> {
                        try {
                            bootstrapHistoricalData(instrument);
                        } catch (Exception ex) {
                            log.error("Unexpected bootstrap failure for {}",
                                    instrument.getTradingSymbol(),
                                    ex);
                        }
                    });
                }
                pageNo++;

            } while (page.hasNext());

        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.HOURS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("Historical Bootstrap Completed");
    }

    private void bootstrapHistoricalData(Instrument instrument) {

        String symbol = instrument.getTradingSymbol();

        try {

            LocalDate toDate =
                    LocalDate.now().minusDays(1);

            long existingCandleCount = persistenceService.countBySymbol(symbol);

            LocalDate fromDate;

            /*
             * If historical data is incomplete,
             * perform a full backfill.
             */
            if (existingCandleCount < MINIMUM_REQUIRED_CANDLES) {

                fromDate =
                        toDate.minusDays(
                                properties.getLookbackDays());

                log.info(
                        "Historical data incomplete | " +
                                "Symbol={} | Existing Candles={} | " +
                                "Minimum Required={} | " +
                                "Performing FULL BACKFILL | From={} | To={}",
                        symbol,
                        existingCandleCount,
                        MINIMUM_REQUIRED_CANDLES,
                        fromDate,
                        toDate);

            } else {

                LocalDate lastDownloadedDate =
                        persistenceService
                                .getLastDownloadedDate(symbol);

                /*
                 * Safety check.
                 */
                if (lastDownloadedDate == null) {

                    fromDate =
                            toDate.minusDays(
                                    properties.getLookbackDays());

                    log.info(
                            "No previous historical data found | " +
                                    "Symbol={} | Performing FULL BACKFILL | " +
                                    "From={} | To={}",
                            symbol,
                            fromDate,
                            toDate);

                } else {

                    /*
                     * Historical data is sufficient.
                     * Download only missing candles.
                     */
                    fromDate =
                            lastDownloadedDate.plusDays(1);

                    if (fromDate.isAfter(toDate)) {

                        log.info(
                                "Historical data already up-to-date | " +
                                        "Symbol={} | Candle Count={} | " +
                                        "Last Candle={}",
                                symbol,
                                existingCandleCount,
                                lastDownloadedDate);

                        return;
                    }

                    log.info(
                            "Incremental historical download | " +
                                    "Symbol={} | Candle Count={} | " +
                                    "From={} | To={}",
                            symbol,
                            existingCandleCount,
                            fromDate,
                            toDate);
                }
            }

            HistoricalCandleResponse response =
                    historicalCandleService
                            .getHistoricalCandles(
                                    symbol,
                                    properties.getInterval(),
                                    fromDate,
                                    toDate)
                            .subscribeOn(
                                    Schedulers.boundedElastic())
                            .block();

            if (response != null
                    && response.getCandles() != null
                    && !response.getCandles().isEmpty()) {

                log.info(
                        "Historical candles received | " +
                                "Symbol={} | Candle Count={}",
                        symbol,
                        response.getCandles().size());

                persistenceService.save(response);

                log.info(
                        "Historical data saved successfully | Symbol={}",
                        symbol);

            } else {

                log.warn(
                        "No historical candles available | " +
                                "Symbol={} | From={} | To={}",
                        symbol,
                        fromDate,
                        toDate);
            }

        } catch (Exception ex) {

            log.error(
                    "Historical bootstrap failed | Symbol={}",
                    symbol,
                    ex);
        }
    }

}