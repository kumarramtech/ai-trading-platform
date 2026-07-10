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

        try {

            LocalDate toDate = LocalDate.now().minusDays(1);

            LocalDate lastDownloadedDate =
                    persistenceService.getLastDownloadedDate(
                            instrument.getTradingSymbol());

            LocalDate fromDate;

            if (lastDownloadedDate == null) {

                // First bootstrap
                fromDate = toDate.minusDays(properties.getLookbackDays());

                log.info("Initial bootstrap for {} from {} to {}",
                        instrument.getTradingSymbol(),
                        fromDate,
                        toDate);

            } else {

                // Incremental download
                fromDate = lastDownloadedDate.plusDays(1);

                if (fromDate.isAfter(toDate)) {

                    log.info("{} is already up-to-date. Last Candle={}",
                            instrument.getTradingSymbol(),
                            lastDownloadedDate);

                    return;
                }

                log.info("Incremental download for {} from {} to {}",
                        instrument.getTradingSymbol(),
                        fromDate,
                        toDate);
            }

            HistoricalCandleResponse response =
                    historicalCandleService
                            .getHistoricalCandles(
                                    instrument.getTradingSymbol(),
                                    properties.getInterval(),
                                    fromDate,
                                    toDate)
                            .subscribeOn(Schedulers.boundedElastic())
                            .block();

            if (response != null && response.getCandles() != null
                    && !response.getCandles().isEmpty()) {
                persistenceService.save(response);
                log.info("Completed historical data for {}", instrument.getTradingSymbol());
            } else {
                log.info("No new candles available for {}", instrument.getTradingSymbol());
            }

        } catch (Exception ex) {
            log.error("Historical bootstrap failed for symbol {}", instrument.getTradingSymbol(),ex);
        }
    }

}