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

            /*
             * IMPORTANT DIAGNOSTIC LOG
             * Confirms that a binary market-data message actually
             * reached the protobuf parser.
             */
            log.debug(
                    "UPSTOX FEED RECEIVED -> type={}, feedCount={}, bytes={}",
                    response.getType(),
                    response.getFeedsCount(),
                    bytes.length
            );

            response.getFeedsMap().forEach((instrumentKey, feed) -> {

                /*
                 * IMPORTANT DIAGNOSTIC LOG
                 * Shows what type of feed Upstox actually delivered.
                 */
                log.debug(
                        "UPSTOX FEED -> instrumentKey={}, hasLtpc={}, hasFullFeed={}",
                        instrumentKey,
                        feed.hasLtpc(),
                        feed.hasFullFeed()
                );

                var ltpc = extractLtpc(feed);

                /*
                 * We cannot create a valid Tick without LTP.
                 */
                if (ltpc == null) {

                    log.warn(
                            "UPSTOX FEED SKIPPED -> instrumentKey={} | LTPC not available",
                            instrumentKey
                    );

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

                /*
                 * Protect the downstream cache and signal pipeline
                 * from an unresolved instrument.
                 */
                if (symbol == null || symbol.isBlank()) {

                    log.warn(
                            "UPSTOX FEED SKIPPED -> instrumentKey={} | trading symbol not found",
                            instrumentKey
                    );

                    return;
                }

                FullFeedSnapshot snapshot =
                        extractFullFeedSnapshot(feed);

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
                                        : previousClose
                        )
                        /*
                         * IMPORTANT:
                         * This is the current I1 candle's cumulative
                         * traded volume, not LTQ.
                         *
                         * MinuteCandleAggregator replaces the candle
                         * volume with the latest value instead of
                         * summing this cumulative value repeatedly.
                         */
                        .volume(snapshot.volume())
                        .timestamp(ltpc.getLtt())
                        .previousClose(previousClose)
                        .change(change)
                        .changePercentage(changePercentage)
                        .build();

                /*
                 * IMPORTANT DIAGNOSTIC LOG
                 * Confirms that a valid Tick has been created.
                 */
                log.debug(
                        "LIVE TICK -> Symbol={}, InstrumentKey={}, Price={}, Volume={}, Change={}%, Time={}",
                        symbol,
                        instrumentKey,
                        tick.getLastTradedPrice(),
                        tick.getVolume(),
                        tick.getChangePercentage(),
                        tick.getTradeTime()
                );

                /*
                 * This should eventually result in:
                 *
                 * LivePriceCache.update(...)
                 *
                 * inside TickProcessorImpl.
                 */
                tickProcessor.publishTick(tick);
            });

        } catch (InvalidProtocolBufferException ex) {

            log.error(
                    "Unable to parse Upstox protobuf market message.",
                    ex
            );

        } catch (Exception ex) {

            log.error(
                    "Unexpected error while parsing Upstox market feed.",
                    ex
            );
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