package com.ram.trading.market.data.service.impl;

import com.ram.trading.market.data.client.StockInstrumentClient;
import com.ram.trading.market.data.service.InstrumentLookupService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class InstrumentLookupServiceImpl implements InstrumentLookupService {

    private final StockInstrumentClient client;

    private final Map<String,String> cache =
            new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void load() {

        refreshCache();
    }

    public void refreshCache() {

        try {

            cache.clear();

            client.loadAll().forEach(i ->
                    cache.put(i.getInstrumentKey(),
                            i.getTradingSymbol()));

            log.info("Loaded {} Instruments", cache.size());

        } catch (Exception ex) {

            log.error("Unable to refresh instrument cache.", ex);
        }
    }

    @Override
    public String getTradingSymbol(String instrumentKey) {

        return cache.getOrDefault(instrumentKey, instrumentKey);
    }

}