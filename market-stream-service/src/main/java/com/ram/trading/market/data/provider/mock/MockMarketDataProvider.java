/*
package com.ram.trading.market.data.provider.mock;

import com.ram.trading.market.data.cache.InstrumentCache;
import com.ram.trading.market.data.dto.MarketInstrument;
import com.ram.trading.market.data.dto.Tick;
import com.ram.trading.market.data.service.MarketDataProvider;
import com.ram.trading.market.data.service.TickProcessor;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class MockMarketDataProvider implements MarketDataProvider {

    private final TickProcessor tickProcessor;

    private final InstrumentCache instrumentCache;

    @PostConstruct
    public void init() {

        instrumentCache.put(new MarketInstrument("TCS", "NSE","NSE_EQ|INE467B01029",3500d,true));
        instrumentCache.put(new MarketInstrument("INFY","NSE","NSE_EQ|INE467B01029",1600d,true));
        instrumentCache.put(new MarketInstrument("RELIANCE", "NSE","NSE_EQ|INE467B01029",1500d,true));
        instrumentCache.put(new MarketInstrument("HDFCBANK", "NSE","NSE_EQ|INE467B01029",3200d,true));
        instrumentCache.put(new MarketInstrument("ICICIBANK", "NSE","NSE_EQ|INE467B01029",2500d,true));
        instrumentCache.put(new MarketInstrument("SBIN", "NSE","NSE_EQ|INE467B01029",1500d,true));
        instrumentCache.put(new MarketInstrument("LT", "NSE","NSE_EQ|INE467B01029",8500d,true));
        instrumentCache.put(new MarketInstrument("AXISBANK", "NSE","NSE_EQ|INE467B01029",3500d,true));
        instrumentCache.put(new MarketInstrument("ITC", "NSE","NSE_EQ|INE467B01029",3500d,true));
        instrumentCache.put(new MarketInstrument("BHARTIARTL", "NSE","NSE_EQ|INE467B01029",3500d,true));

    }
    private final ScheduledExecutorService executor =  Executors.newSingleThreadScheduledExecutor();


    private final Random random = new Random();

    @Override
    public void connect() {

        executor.scheduleAtFixedRate(() -> {
            for (MarketInstrument instrument : instrumentCache.findAll()){
                        Tick tick = generateTick(instrument);
                        tickProcessor.publishTick(tick);
                    }

                },0,1, TimeUnit.SECONDS);

    }

    private Tick generateTick(MarketInstrument instrument){

        Double newPrice = instrument.getLastPrice() + random.nextDouble(-3,3);

        instrument.setLastPrice(newPrice);
        return Tick.builder()
                .symbol(instrument.getSymbol())
                .lastTradedPrice(newPrice)
                .volume(random.nextLong(50000,100000))
                .timestamp(System.currentTimeMillis())
                .build();

    }

    @Override
    public void disconnect() {
        executor.shutdownNow();
    }

    @Override
    public void subscribe(List<String> instrumentKeys) {

    }

    @Override
    public void unsubscribe(List<String> instrumentKeys) {

    }

    @Override
    public boolean isConnected() {
        return false;
    }

}*/
