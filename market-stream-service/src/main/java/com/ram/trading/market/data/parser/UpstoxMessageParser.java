package com.ram.trading.market.data.parser;

import com.google.protobuf.InvalidProtocolBufferException;
import com.ram.trading.market.data.dto.Tick;
import com.ram.trading.market.data.service.InstrumentLookupService;
import com.ram.trading.market.data.service.TickProcessor;
import com.upstox.marketdatafeederv3udapi.rpc.proto.MarketDataFeed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.nio.ByteBuffer;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpstoxMessageParser {

    private final TickProcessor tickProcessor;

    private final InstrumentLookupService instrumentLookupService;

    public void parse(ByteBuffer buffer) {

        try {

            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            MarketDataFeed.FeedResponse response =
                    MarketDataFeed.FeedResponse.parseFrom(bytes);

            log.debug("Feed Type : {}", response.getType());

            response.getFeedsMap().forEach((instrumentKey, feed) -> {

                if (!feed.hasLtpc()) {
                    return;
                }

                var ltpc = feed.getLtpc();

                double lastPrice = ltpc.getLtp();
                double previousClose = ltpc.getCp();

                double change = lastPrice - previousClose;

                double changePercentage =
                        previousClose == 0
                                ? 0
                                : (change / previousClose) * 100;

                String exchange = "";

                if (instrumentKey.contains("|")) {

                    exchange = instrumentKey.split("\\|")[0];

                }

                String symbol =
                        instrumentLookupService.getTradingSymbol(instrumentKey);
                log.info("Tick Received -> Symbol: {}, Price: {}",
                        symbol,
                        lastPrice);


                Tick tick = Tick.builder()
                        .exchange(exchange)
                        .symbol(symbol)
                        .instrumentKey(instrumentKey)
                        .lastTradedPrice(lastPrice)
                        .closePrice(previousClose)
                        .volume((long) ltpc.getLtq())
                        .timestamp(ltpc.getLtt())
                        .previousClose(previousClose)
                        .change(change)
                        .changePercentage(changePercentage)
                        .build();

                log.info(
                        "LIVE TICK -> Symbol={}, Price={}, Change={}%, Time={}",
                        symbol,
                        tick.getLastTradedPrice(),
                        tick.getChangePercentage(),
                        tick.getTradeTime());

                tickProcessor.publishTick(tick);

            });

        } catch (InvalidProtocolBufferException ex) {

            log.error("Unable to parse protobuf.", ex);

        } catch (Exception ex) {

            log.error("Unexpected error while parsing market feed.", ex);

        }

    }

}