package com.ram.trading.market.data.service.impl;

import com.ram.trading.market.data.client.StockInstrumentClient;
import com.ram.trading.market.data.service.InstrumentLookupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentLookupServiceImpl implements InstrumentLookupService {

    private final StockInstrumentClient client;

    private final Map<String, String> cache =
            new ConcurrentHashMap<>();

    private static final int MAX_RETRIES = 10;
    private static final long RETRY_DELAY_MS = 3000L;

    @EventListener(ApplicationReadyEvent.class)
    public void load() {

        boolean loaded = refreshCacheWithRetry();

        if (!loaded) {
            log.error(
                    "CRITICAL: Unable to load instrument cache after {} attempts. "
                            + "Market Stream will not have reliable instrument mapping.",
                    MAX_RETRIES);
        }
    }

    public boolean refreshCache() {

        try {

            /*
             * Build the new cache separately.
             *
             * Do NOT clear the existing cache before the remote call.
             * If Stock Service is temporarily unavailable, the existing
             * cache remains usable.
             */
            Map<String, String> newCache = new HashMap<>();

            client.loadSubscriptions().forEach(i ->
                    newCache.put(
                            i.getInstrumentKey(),
                            i.getSymbol()
                    )
            );

            if (newCache.isEmpty()) {
                log.warn("Instrument refresh returned 0 instruments. Existing cache retained.");
                return false;
            }

            /*
             * Replace the cache only after a successful refresh.
             */
            cache.clear();
            cache.putAll(newCache);

            log.info("Loaded {} Instruments", cache.size());

            return true;

        } catch (Exception ex) {

            log.error(
                    "Unable to refresh instrument cache. Existing cache retained.",
                    ex
            );

            return false;
        }
    }

    private boolean refreshCacheWithRetry() {

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {

            log.info(
                    "Loading instrument cache. Attempt {}/{}",
                    attempt,
                    MAX_RETRIES
            );

            if (refreshCache()) {
                log.info(
                        "Instrument cache loaded successfully on attempt {}/{}",
                        attempt,
                        MAX_RETRIES
                );

                return true;
            }

            if (attempt < MAX_RETRIES) {

                log.warn(
                        "Instrument cache load failed. Retrying in {} ms...",
                        RETRY_DELAY_MS
                );

                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ex) {

                    Thread.currentThread().interrupt();

                    log.error(
                            "Instrument cache retry interrupted.",
                            ex
                    );

                    return false;
                }
            }
        }

        return false;
    }

    @Override
    public String getTradingSymbol(String instrumentKey) {

        return cache.getOrDefault(
                instrumentKey,
                instrumentKey
        );
    }
}