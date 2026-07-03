package com.ram.trading.stock.bootstrap;

import com.ram.trading.stock.service.instument.InstrumentDownloadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class InstrumentBootstrapServiceImpl implements InstrumentBootstrapService {

    private final InstrumentDownloadService instrumentDownloadService;
    @Override
    public void bootstrap() {

        log.info("");
        log.info("----------------------------------------------------");
        log.info(" Instrument Bootstrap Started ");
        log.info("----------------------------------------------------");

        instrumentDownloadService.downloadAndImport();

        log.info(" Instrument Bootstrap Completed ");
    }
}