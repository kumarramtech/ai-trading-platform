package com.ram.trading.market.data.service.impl;

import com.ram.trading.market.data.cache.LivePriceCache;
import com.ram.trading.market.data.dto.HealthResponse;
import com.ram.trading.market.data.dto.MarketInstrument;
import com.ram.trading.market.data.dto.MarketStreamStatus;
import com.ram.trading.market.data.service.MarketDataProvider;
import com.ram.trading.market.data.service.MarketMetrics;
import com.ram.trading.market.data.service.MarketStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketStreamServiceImpl implements MarketStreamService {

    private final MarketDataProvider marketDataProvider;

    private final LivePriceCache livePriceCache;

    private final MarketMetrics marketMetrics;

    @Override
    public void start() {

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
                .provider("MOCK")
                .connectionStatus("CONNECTED")
                .marketStatus("OPEN")
                .cacheSize(livePriceCache.size())
                .ticksProcessed(0L)
                .uptime(0L)
                .build();

    }

    @Override
    public List<MarketInstrument> getInstruments() {

        return List.of(
                new MarketInstrument("TCS", "NSE","NSE_EQ|INE467B01029",3500d,true),
        new MarketInstrument("INFY","NSE","NSE_EQ|INE467B01029",1600d,true),
        new MarketInstrument("RELIANCE", "NSE","NSE_EQ|INE467B01029",1500d,true),
        new MarketInstrument("HDFCBANK", "NSE","NSE_EQ|INE467B01029",3200d,true),
        new MarketInstrument("ICICIBANK", "NSE","NSE_EQ|INE467B01029",2500d,true),
        new MarketInstrument("SBIN", "NSE","NSE_EQ|INE467B01029",1500d,true),
        new MarketInstrument("LT", "NSE","NSE_EQ|INE467B01029",8500d,true),
        new MarketInstrument("AXISBANK", "NSE","NSE_EQ|INE467B01029",3500d,true),
        new MarketInstrument("ITC", "NSE","NSE_EQ|INE467B01029",3500d,true),
        new MarketInstrument("BHARTIARTL", "NSE","NSE_EQ|INE467B01029",3500d,true)

        );

    }

    @Override
    public HealthResponse getHealth() {

        return HealthResponse.builder()
                .status("UP")
                .stream("RUNNING")
                .provider("MOCK")
                .connection("CONNECTED")
                .cacheSize(livePriceCache.size())
                .ticksProcessed(marketMetrics.getTicksProcessed())
                .uptime(marketMetrics.getUptime())
                .build();

    }

}