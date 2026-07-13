package com.ram.trading.market.data.parser;

import com.google.protobuf.InvalidProtocolBufferException;
import com.ram.trading.market.data.dto.Tick;
import com.ram.trading.market.data.service.TickProcessor;
import com.upstox.marketdatafeederv3udapi.rpc.proto.MarketDataFeed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpstoxMessageParser {

    private final TickProcessor tickProcessor;

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
                String symbol = instrumentKey;

                if (instrumentKey.contains("|")) {

                    String[] values = instrumentKey.split("\\|");

                    exchange = values[0];

                    symbol = values[1];

                }

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

                log.info("Trade Time : {}", tick.getTradeTime());
                log.info("Tick Received : {}", tick);

                tickProcessor.publishTick(tick);

            });

        } catch (InvalidProtocolBufferException ex) {

            log.error("Unable to parse protobuf.", ex);

        } catch (Exception ex) {

            log.error("Unexpected error while parsing market feed.", ex);

        }

    }

}