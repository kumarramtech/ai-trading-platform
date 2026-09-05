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

                var ltpc = extractLtpc(feed);

                if (ltpc == null) {
                    return;
                }

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

                FullFeedSnapshot snapshot = extractFullFeedSnapshot(feed);

                Tick tick = Tick.builder()
                        .exchange(exchange)
                        .symbol(symbol)
                        .instrumentKey(instrumentKey)
                        .lastTradedPrice(lastPrice)
                        .openPrice(snapshot.openPrice())
                        .highPrice(snapshot.highPrice())
                        .lowPrice(snapshot.lowPrice())
                        .closePrice(
                                snapshot.closePrice() != null
                                        ? snapshot.closePrice()
                                        : previousClose)
                        /*
                         * IMPORTANT: this is the current I1 candle's cumulative
                         * traded volume, not LTQ. The signal candle aggregator
                         * replaces this value on every tick rather than summing it.
                         */
                        .volume(snapshot.volume())
                        .timestamp(ltpc.getLtt())
                        .previousClose(previousClose)
                        .change(change)
                        .changePercentage(changePercentage)
                        .build();

                log.debug(
                        "LIVE TICK -> Symbol={}, Price={}, Volume={}, Change={}%, Time={}",
                        symbol,
                        tick.getLastTradedPrice(),
                        tick.getVolume(),
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

    private MarketDataFeed.LTPC extractLtpc(
            MarketDataFeed.Feed feed) {

        if (feed.hasLtpc()) {
            return feed.getLtpc();
        }

        if (feed.hasFullFeed()
                && feed.getFullFeed().hasMarketFF()
                && feed.getFullFeed().getMarketFF().hasLtpc()) {
            return feed.getFullFeed().getMarketFF().getLtpc();
        }

        return null;
    }

    private FullFeedSnapshot extractFullFeedSnapshot(
            MarketDataFeed.Feed feed) {

        if (!feed.hasFullFeed()
                || !feed.getFullFeed().hasMarketFF()
                || !feed.getFullFeed().getMarketFF().hasMarketOHLC()) {
            return FullFeedSnapshot.empty();
        }

        var marketOhlc =
                feed.getFullFeed()
                        .getMarketFF()
                        .getMarketOHLC();

        for (var ohlc : marketOhlc.getOhlcList()) {

            if ("I1".equalsIgnoreCase(ohlc.getInterval())) {
                return new FullFeedSnapshot(
                        ohlc.getOpen(),
                        ohlc.getHigh(),
                        ohlc.getLow(),
                        ohlc.getClose(),
                        ohlc.getVol());
            }
        }

        return FullFeedSnapshot.empty();
    }

    private record FullFeedSnapshot(
            Double openPrice,
            Double highPrice,
            Double lowPrice,
            Double closePrice,
            Long volume) {

        private static FullFeedSnapshot empty() {
            return new FullFeedSnapshot(null, null, null, null, null);
        }
    }


}