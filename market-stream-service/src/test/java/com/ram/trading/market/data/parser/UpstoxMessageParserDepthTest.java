package com.ram.trading.market.data.parser;

import com.ram.trading.market.data.dto.Tick;
import com.upstox.marketdatafeederv3udapi.rpc.proto.MarketDataFeed;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UpstoxMessageParserDepthTest {

    private static MarketDataFeed.Feed feedWith(MarketDataFeed.Quote... quotes) {
        MarketDataFeed.MarketLevel.Builder level = MarketDataFeed.MarketLevel.newBuilder();
        for (MarketDataFeed.Quote quote : quotes) {
            level.addBidAskQuote(quote);
        }
        return MarketDataFeed.Feed.newBuilder()
                .setFullFeed(MarketDataFeed.FullFeed.newBuilder()
                        .setMarketFF(MarketDataFeed.MarketFullFeed.newBuilder()
                                .setMarketLevel(level)))
                .build();
    }

    private static MarketDataFeed.Quote quote(long bidQ, double bidP, long askQ, double askP) {
        return MarketDataFeed.Quote.newBuilder()
                .setBidQ(bidQ).setBidP(bidP).setAskQ(askQ).setAskP(askP).build();
    }

    @Test
    void copiesTheBookBestLevelFirstAndSkipsEmptyLevels() {
        Tick tick = Tick.builder().symbol("IFCI").build();

        UpstoxMessageParser.applyDepth(tick, feedWith(
                quote(500, 60.60, 300, 60.62),
                quote(800, 60.59, 0, 0),        // empty ask level
                quote(1000, 60.58, 900, 60.64)));

        assertEquals(List.of(60.60, 60.59, 60.58), tick.getBidPrices());
        assertEquals(List.of(500L, 800L, 1000L), tick.getBidQuantities());
        assertEquals(List.of(60.62, 60.64), tick.getAskPrices());
        assertEquals(List.of(300L, 900L), tick.getAskQuantities());
    }

    @Test
    void oneSidedOrMissingBookLeavesTheTickWithoutDepth() {
        Tick oneSided = Tick.builder().symbol("UC").build();
        UpstoxMessageParser.applyDepth(oneSided, feedWith(quote(500, 100.0, 0, 0)));
        assertNull(oneSided.getBidPrices());

        Tick index = Tick.builder().symbol("NIFTY").build();
        UpstoxMessageParser.applyDepth(index, MarketDataFeed.Feed.newBuilder().build());
        assertNull(index.getAskPrices());
    }
}
