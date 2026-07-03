package com.ram.trading.stock.bootstrap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class IndicatorBootstrapServiceImpl implements IndicatorBootstrapService {

    @Override
    public void bootstrap() {

        log.info("");
        log.info("----------------------------------------------------");
        log.info(" Indicator Bootstrap Started ");
        log.info("----------------------------------------------------");

        // TODO
        // technicalIndicatorService.generateIndicators();

        log.info(" Indicator Bootstrap Completed ");

    }
}