package com.ram.trading.stock.bootstrap.history;

import com.ram.trading.stock.dto.history.HistoricalCandleResponse;
import com.ram.trading.stock.entity.Instrument;
import com.ram.trading.stock.service.history.HistoricalCandleService;
import com.ram.trading.stock.service.instument.InstrumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

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

        log.info("======================================");
        log.info("Historical Bootstrap Started");
        log.info("======================================");

        List<Instrument> instruments = instrumentService.findActiveByExchange("NSE");

        log.info("Found {} instruments", instruments.size());

        instruments.stream().limit(properties.getMaxSymbolsPerRun()).forEach(this::bootstrapHistoricalData);

        log.info("Historical Bootstrap Completed");

    }

    private void bootstrapHistoricalData(Instrument instrument) {

        try {
            LocalDate toDate = LocalDate.now();
            LocalDate fromDate =
                    toDate.minusDays(properties.getLookbackDays());

            log.info("Downloading historical data for {}",instrument.getTradingSymbol());

            HistoricalCandleResponse response =
                    historicalCandleService
                            .getHistoricalCandles(instrument.getTradingSymbol(),properties.getInterval(),
                                    fromDate,
                                    toDate).block();

            if (response != null) {
                persistenceService.save(response);
                log.info("Completed historical data for {}", instrument.getTradingSymbol());
            } else {
                log.warn("No historical data returned for {}", instrument.getTradingSymbol());

            }
            log.info("Completed historical data");

        } catch (Exception ex) {
            log.error("Historical bootstrap failed for symbol {}", instrument.getTradingSymbol(),
                    ex);

        }

    }

}