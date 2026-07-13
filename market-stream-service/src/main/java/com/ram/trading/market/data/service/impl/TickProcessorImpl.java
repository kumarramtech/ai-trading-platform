package com.ram.trading.market.data.service.impl;

import com.ram.trading.market.data.cache.LivePriceCache;
import com.ram.trading.market.data.dto.LivePrice;
import com.ram.trading.market.data.dto.Tick;
import com.ram.trading.market.data.service.MarketEventPublisher;
import com.ram.trading.market.data.service.MarketMetrics;
import com.ram.trading.market.data.service.TickProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TickProcessorImpl implements TickProcessor {

    private final LivePriceCache livePriceCache;

    private final MarketEventPublisher publisher;

    private final MarketMetrics marketMetrics;

    @Override
    public void publishTick(Tick tick) {

        LivePrice livePrice = LivePrice.builder()
                .symbol(tick.getSymbol())
                .price(tick.getLastTradedPrice())
                .volume(tick.getVolume())
                .timestamp(tick.getTimestamp())
                .build();

        livePriceCache.update(livePrice);
        publisher.publishPriceUpdate(livePrice);
        publisher.process(tick);
        marketMetrics.incrementTick();
        marketMetrics.updateLastTick();
    }
}