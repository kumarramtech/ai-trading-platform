package com.ram.trading.stock.bootstrap;

import com.ram.trading.stock.bootstrap.history.HistoricalDataBootstrapService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class BootstrapServiceImpl
        implements BootstrapService {

    private final InstrumentBootstrapService instrumentBootstrapService;

    private final HistoricalDataBootstrapService historicalDataBootstrapService;

    private final IndicatorBootstrapService indicatorBootstrapService;

    @Override
    public void bootstrap() {

        log.info("");
        log.info("====================================================");
        log.info(" AI Trading Platform Bootstrap Started ");
        log.info("====================================================");

        instrumentBootstrapService.bootstrap();

        historicalDataBootstrapService.bootstrap();

        indicatorBootstrapService.bootstrap();

        log.info("====================================================");
        log.info(" Bootstrap Completed Successfully ");
        log.info("====================================================");
    }
}