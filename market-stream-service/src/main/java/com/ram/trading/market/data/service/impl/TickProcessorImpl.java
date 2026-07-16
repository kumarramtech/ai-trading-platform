package com.ram.trading.market.data.service.impl;

import com.ram.trading.market.data.cache.LivePriceCache;
import com.ram.trading.market.data.client.SignalServiceClient;
import com.ram.trading.market.data.dto.LivePrice;
import com.ram.trading.market.data.dto.Tick;
import com.ram.trading.market.data.service.MarketEventPublisher;
import com.ram.trading.market.data.service.MarketMetrics;
import com.ram.trading.market.data.service.TickProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TickProcessorImpl implements TickProcessor {

    private final LivePriceCache livePriceCache;

    private final MarketEventPublisher publisher;

    private final MarketMetrics marketMetrics;

    private final SignalServiceClient signalServiceClient;

    @Override
    public void publishTick(Tick tick) {

        LivePrice livePrice = LivePrice.builder()
                .symbol(tick.getSymbol())
                .price(tick.getLastTradedPrice())
                .volume(tick.getVolume())
                .timestamp(tick.getTimestamp())
                .build();

        // Update live cache
        livePriceCache.update(livePrice);

        publisher.publishPriceUpdate(livePrice);

        publisher.process(tick);

        // Update metrics
        marketMetrics.incrementTick();
        marketMetrics.updateLastTick();

        try {

            signalServiceClient.publishTick(tick);

        } catch (Exception ex) {

            log.error("Unable to publish Tick to Signal Service", ex);

        }
    }
}