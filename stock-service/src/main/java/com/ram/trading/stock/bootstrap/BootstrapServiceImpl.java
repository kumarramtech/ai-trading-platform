package com.ram.trading.stock.bootstrap;

import com.ram.trading.stock.bootstrap.history.HistoricalDataBootstrapService;
import com.ram.trading.stock.service.instument.BootstrapStatusService;
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

    private final BootstrapStatusService bootstrapStatusService;

    @Override
    public void bootstrap() {

        bootstrapStatusService.bootstrapStarted();

        try {

            log.info("");
            log.info("====================================================");
            log.info("AI Trading Platform Bootstrap Started");
            log.info("====================================================");

            long start = System.currentTimeMillis();

            instrumentBootstrapService.bootstrap();

            historicalDataBootstrapService.bootstrap();

            indicatorBootstrapService.bootstrap();

            long end = System.currentTimeMillis();

            bootstrapStatusService.bootstrapCompleted();

            log.info("====================================================");
            log.info("Bootstrap Completed Successfully");
            log.info("Execution Time : {} Seconds",
                    (end - start) / 1000);
            log.info("Platform Ready For Trading");
            log.info("====================================================");

        } catch (Exception ex) {

            bootstrapStatusService.bootstrapFailed();

            log.error("Bootstrap Failed", ex);

            throw ex;
        }
    }
}