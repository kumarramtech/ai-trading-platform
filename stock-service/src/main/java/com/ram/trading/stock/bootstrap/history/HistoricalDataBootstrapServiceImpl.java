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

        Page<Instrument> page;

        do {

            page = instrumentService.findTradableEquities(
                    PageRequest.of(pageNo, batchSize));

            page.getContent().stream()

                    .filter(i -> "NSE".equalsIgnoreCase(i.getExchange()))

                    .filter(i -> "NSE_EQ".equalsIgnoreCase(i.getSegment()))

                    .filter(i -> "EQ".equalsIgnoreCase(i.getInstrumentType()))

                    .forEach(this::bootstrapHistoricalData);

            pageNo++;

        } while (page.hasNext());

        log.info("Historical Bootstrap Completed");
    }

    private void bootstrapHistoricalData(Instrument instrument) {

        try {

            // Download only completed trading days
            LocalDate toDate = LocalDate.now().minusDays(1);
            LocalDate fromDate = toDate.minusDays(properties.getLookbackDays());

            log.info("Downloading historical data for {}", instrument.getTradingSymbol());

            HistoricalCandleResponse response =
                    historicalCandleService
                            .getHistoricalCandles(
                                    instrument.getTradingSymbol(),
                                    properties.getInterval(),
                                    fromDate,
                                    toDate)
                            .subscribeOn(Schedulers.boundedElastic())
                            .block();

            if (response != null) {
                persistenceService.save(response);
                log.info("Completed historical data for {}", instrument.getTradingSymbol());
            } else {
                log.warn("No historical data returned for {}", instrument.getTradingSymbol());
            }

        } catch (Exception ex) {
            log.error("Historical bootstrap failed for symbol {}",
                    instrument.getTradingSymbol(),
                    ex);
        }

    }

}