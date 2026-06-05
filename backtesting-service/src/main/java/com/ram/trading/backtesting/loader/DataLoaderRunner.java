package com.ram.trading.backtesting.loader;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataLoaderRunner
        implements CommandLineRunner {

    private final HistoricalDataLoaderService loader;

    @Override
    public void run(String... args)
            throws Exception {

        loader.loadData();
    }
}