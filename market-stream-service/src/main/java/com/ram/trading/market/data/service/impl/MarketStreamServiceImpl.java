package com.ram.trading.market.data.service.impl;

import com.ram.trading.market.data.cache.LivePriceCache;
import com.ram.trading.market.data.client.StockInstrumentClient;
import com.ram.trading.market.data.dto.HealthResponse;
import com.ram.trading.market.data.dto.LivePrice;
import com.ram.trading.market.data.dto.MarketInstrument;
import com.ram.trading.market.data.dto.MarketStreamStatus;
import com.ram.trading.market.data.provider.dto.InstrumentSubscriptionResponse;
import com.ram.trading.market.data.service.MarketDataProvider;
import com.ram.trading.market.data.service.MarketMetrics;
import com.ram.trading.market.data.service.MarketSessionService;
import com.ram.trading.market.data.service.MarketStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketStreamServiceImpl implements MarketStreamService {

    private final MarketDataProvider marketDataProvider;
    private final LivePriceCache livePriceCache;
    private final MarketMetrics marketMetrics;
    private final MarketSessionService marketSessionService;
    private final StockInstrumentClient stockInstrumentClient;

    @Value("${market.websocket.broker.provider:upstox}")
    private String providerName;

    @Override
    public void start() {
        if (!marketSessionService.isMarketOpen()) {
            log.debug("Market stream start ignored because market session is closed.");
            return;
        }

        if (marketDataProvider.isConnected()) {
            log.debug("Market stream already connected.");
            return;
        }

        log.info("Starting Market Stream...");
        marketDataProvider.connect();
    }

    @Override
    public void stop() {
        if (!marketDataProvider.isConnected()) {
            log.debug("Market stream already disconnected.");
            return;
        }

        log.info("Stopping Market Stream...");
        marketDataProvider.disconnect();
    }

    @Override
    public MarketStreamStatus getStatus() {
        return MarketStreamStatus.builder()
                .provider(providerName)
                .connectionStatus(marketDataProvider.isConnected() ? "CONNECTED" : "DISCONNECTED")
                .marketStatus(marketSessionService.isMarketOpen() ? "OPEN" : "CLOSED")
                .cacheSize(livePriceCache.size())
                .ticksProcessed(marketMetrics.getTicksProcessed())
                .uptime(marketMetrics.getUptime())
                .build();
    }

    @Override
    public List<MarketInstrument> getInstruments() {
        List<InstrumentSubscriptionResponse> subscriptions;
        try {
            subscriptions = stockInstrumentClient.loadSubscriptions();
        } catch (Exception ex) {
            log.error("Unable to load instrument subscriptions for market-stream/instruments", ex);
            throw ex;
        }

        if (subscriptions == null || subscriptions.isEmpty()) {
            log.warn("Stock Service returned no instrument subscriptions.");
            return List.of();
        }

        Map<String, LivePrice> pricesBySymbol = livePriceCache.findAll().stream()
                .collect(Collectors.toMap(
                        LivePrice::getSymbol,
                        Function.identity(),
                        (first, latest) -> latest));

        boolean connected = marketDataProvider.isConnected();

        return subscriptions.stream()
                .filter(instrument -> instrument.getSymbol() != null
                        && !instrument.getSymbol().isBlank()
                        && instrument.getInstrumentKey() != null
                        && !instrument.getInstrumentKey().isBlank())
                .map(instrument -> {
                    LivePrice price = pricesBySymbol.get(instrument.getSymbol());
                    return new MarketInstrument(
                            instrument.getSymbol(),
                            "NSE",
                            instrument.getInstrumentKey(),
                            price == null ? null : price.getPrice(),
                            connected);
                })
                .toList();
    }

    @Override
    public HealthResponse getHealth() {
        return HealthResponse.builder()
                .status("UP")
                .stream(marketDataProvider.isConnected() ? "RUNNING" : "STOPPED")
                .provider(providerName)
                .connection(marketDataProvider.isConnected() ? "CONNECTED" : "DISCONNECTED")
                .cacheSize(livePriceCache.size())
                .ticksProcessed(marketMetrics.getTicksProcessed())
                .uptime(marketMetrics.getUptime())
                .build();
    }
}
