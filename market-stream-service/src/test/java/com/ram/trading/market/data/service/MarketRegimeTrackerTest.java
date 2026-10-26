package com.ram.trading.market.data.service;

import com.ram.trading.market.data.dto.Tick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class MarketRegimeTrackerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final double OPEN = 10_000.0;

    private MutableClock clock;
    private MarketRegimeTracker tracker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(ZonedDateTime.of(2026, 10, 26, 9, 20, 0, 0, IST).toInstant());
        tracker = new MarketRegimeTracker();
        tracker.setClock(clock);
        ReflectionTestUtils.setField(tracker, "directionThresholdPercent", 0.15);
        ReflectionTestUtils.setField(tracker, "hysteresisBufferPercent", 0.05);
        ReflectionTestUtils.setField(tracker, "minHoldSeconds", 300L);
        ReflectionTestUtils.setField(tracker, "maxAgeSeconds", 60L);
    }

    @Test
    void directionIsMeasuredFromTodaysOpenNotPreviousClose() {
        // Gapped up 1% over yesterday's close, but now trading 0.3% below today's open.
        both(-0.30, 1.0);

        assertThat(tracker.snapshot().getRegime()).isEqualTo("BEARISH");
        assertThat(tracker.snapshot().getNiftyChange()).isCloseTo(-0.30, within());
    }

    @Test
    void touchingTheLineIsNotAClearMove() {
        both(0.00);
        both(0.16); // past 0.15, but not past 0.15 + 0.05 buffer

        assertThat(tracker.snapshot().getRegime()).isEqualTo("SIDEWAYS");
    }

    @Test
    void oscillatingAroundTheLineDoesNotFlicker() {
        both(0.00);
        tickFor(Duration.ofMinutes(6), 0.00);
        both(0.25);
        assertThat(tracker.snapshot().getRegime()).isEqualTo("BULLISH");

        // Wiggle around the 0.15 line, well after the hold has expired.
        tickFor(Duration.ofMinutes(6), 0.25);
        for (double change : new double[] {0.14, 0.16, 0.12, 0.17, 0.11, 0.15}) {
            both(change);
            clock.advance(Duration.ofSeconds(30));
        }

        assertThat(tracker.snapshot().getRegime()).isEqualTo("BULLISH");
    }

    @Test
    void labelIsHeldForMinimumFiveMinutes() {
        both(0.25);
        assertThat(tracker.snapshot().getRegime()).isEqualTo("BULLISH");

        tickFor(Duration.ofMinutes(4), 0.00); // a clear fall back inside the band
        assertThat(tracker.snapshot().getRegime()).isEqualTo("BULLISH");

        tickFor(Duration.ofSeconds(59), 0.00);
        assertThat(tracker.snapshot().getRegime()).isEqualTo("BULLISH");

        clock.advance(Duration.ofSeconds(1));
        both(0.00);
        assertThat(tracker.snapshot().getRegime()).isEqualTo("SIDEWAYS");
    }

    @Test
    void staleFeedDropsToUnknownWithoutWaitingForTheHold() {
        both(0.25);
        clock.advance(Duration.ofSeconds(61));

        assertThat(tracker.snapshot().getRegime()).isEqualTo("UNKNOWN");

        both(-0.25); // fresh again: first label after UNKNOWN applies at once
        assertThat(tracker.snapshot().getRegime()).isEqualTo("BEARISH");
    }

    @Test
    void firstTickIsLatchedAsOpenWhenFeedHasNoDayOpen() {
        tracker.update(tick("NIFTY", OPEN, null));
        tracker.update(tick("BANKNIFTY", OPEN, null));
        assertThat(tracker.snapshot().getNiftyChange()).isCloseTo(0.0, within());

        tracker.update(tick("NIFTY", OPEN * 1.003, null));
        tracker.update(tick("BANKNIFTY", OPEN * 1.003, null));
        assertThat(tracker.snapshot().getNiftyChange()).isCloseTo(0.30, within());
        assertThat(tracker.snapshot().getRegime()).isEqualTo("SIDEWAYS"); // still in the hold

        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofSeconds(30));
            tracker.update(tick("NIFTY", OPEN * 1.003, null));
            tracker.update(tick("BANKNIFTY", OPEN * 1.003, null));
        }
        assertThat(tracker.snapshot().getRegime()).isEqualTo("BULLISH");
    }

    @Test
    void stateResetsOnANewTradingDay() {
        both(0.25);
        assertThat(tracker.snapshot().getRegime()).isEqualTo("BULLISH");

        clock.advance(Duration.ofDays(1));
        both(-0.25);
        assertThat(tracker.snapshot().getRegime()).isEqualTo("BEARISH");
    }

    @Test
    void enrichCarriesThePublishedRegime() {
        both(0.25);
        clock.advance(Duration.ofMinutes(1));
        both(0.00); // candidate SIDEWAYS, still inside the hold

        Tick stock = Tick.builder().symbol("RELIANCE").lastTradedPrice(1.0).build();
        tracker.enrich(stock);

        assertThat(stock.getMarketRegime()).isEqualTo("BULLISH");
        assertThat(stock.getNiftyChange()).isCloseTo(0.0, within());
    }

    /** Lets time pass while both feeds keep ticking every 30s, as live. */
    private void tickFor(Duration duration, double changeFromOpen) {
        Instant end = clock.instant().plus(duration);
        while (clock.instant().plusSeconds(30).isBefore(end)) {
            clock.advance(Duration.ofSeconds(30));
            both(changeFromOpen);
        }
        clock.advance(Duration.between(clock.instant(), end));
        both(changeFromOpen);
    }

    private void both(double changeFromOpen) {
        both(changeFromOpen, 0.0);
    }

    private void both(double changeFromOpen, double changeFromPrevClose) {
        double ltp = OPEN * (1 + changeFromOpen / 100.0);
        for (String symbol : new String[] {"NIFTY", "BANKNIFTY"}) {
            Tick tick = tick(symbol, ltp, OPEN);
            tick.setChangePercentage(changeFromPrevClose);
            tracker.update(tick);
        }
    }

    private Tick tick(String symbol, double ltp, Double dayOpen) {
        return Tick.builder()
                .symbol(symbol)
                .lastTradedPrice(ltp)
                .dayOpenPrice(dayOpen)
                .build();
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-9);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return IST;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
