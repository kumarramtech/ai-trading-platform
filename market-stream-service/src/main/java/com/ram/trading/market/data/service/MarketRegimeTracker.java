package com.ram.trading.market.data.service;

import com.ram.trading.market.data.dto.MarketRegimeSnapshot;
import com.ram.trading.market.data.dto.Tick;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;

/**
 * Maintains a feed-derived market context from NIFTY and BANK NIFTY ticks.
 * A directional regime is valid only while BOTH index values are present
 * and fresh. This service enriches ticks; it does not reject trades.
 *
 * Direction is measured from today's open, not yesterday's close, so a gap
 * that the market has since reversed does not read as a trend.
 *
 * Two guards stop the label from flickering while an index sits on the line:
 *  - hysteresis: an index must move a buffer past the threshold to turn
 *    UP/DOWN, and fall a buffer back inside it to return to FLAT;
 *  - minimum hold: once published, a label is kept for at least
 *    min-hold-seconds before another label replaces it.
 * Losing a fresh feed still drops the regime to UNKNOWN immediately.
 */
@Service
@Slf4j
public class MarketRegimeTracker {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final Set<String> NIFTY_SYMBOLS = Set.of(
            "NIFTY", "NIFTY50", "NIFTY 50",
            "NSE_INDEX|NIFTY 50", "NSE_INDEX|NIFTY50");

    private static final Set<String> BANK_NIFTY_SYMBOLS = Set.of(
            "BANKNIFTY", "BANK NIFTY", "NIFTYBANK", "NIFTY BANK",
            "NSE_INDEX|NIFTY BANK", "NSE_INDEX|BANKNIFTY");

    private enum Direction { UP, DOWN, FLAT }

    /** Per-index state, reset at the start of each trading day. */
    private static final class IndexState {
        private final String name;
        private Double dayOpen;
        private boolean dayOpenFromFeed;
        private Double change;
        private LocalDateTime updatedAt;
        private Direction direction = Direction.FLAT;

        private IndexState(String name) {
            this.name = name;
        }

        private void reset() {
            dayOpen = null;
            dayOpenFromFeed = false;
            change = null;
            updatedAt = null;
            direction = Direction.FLAT;
        }
    }

    private final IndexState nifty = new IndexState("NIFTY");
    private final IndexState bankNifty = new IndexState("BANKNIFTY");

    private LocalDate tradeDate;
    private String publishedRegime;
    private LocalDateTime publishedAt;

    private Clock clock = Clock.system(IST);

    @Value("${market.regime.direction-threshold-percent:0.15}")
    private double directionThresholdPercent;

    /** How far past the threshold a move must go to count as a clear cross. */
    @Value("${market.regime.hysteresis-buffer-percent:0.05}")
    private double hysteresisBufferPercent;

    @Value("${market.regime.min-hold-seconds:300}")
    private long minHoldSeconds;

    @Value("${market.regime.max-age-seconds:60}")
    private long maxAgeSeconds;

    public void update(Tick tick) {
        if (tick == null || tick.getSymbol() == null) {
            return;
        }

        String symbol = normalize(tick.getSymbol());
        String instrumentKey = normalize(tick.getInstrumentKey());

        IndexState index;
        if (NIFTY_SYMBOLS.contains(symbol) || NIFTY_SYMBOLS.contains(instrumentKey)) {
            index = nifty;
        } else if (BANK_NIFTY_SYMBOLS.contains(symbol)
                || BANK_NIFTY_SYMBOLS.contains(instrumentKey)) {
            index = bankNifty;
        } else {
            return;
        }

        synchronized (this) {
            LocalDateTime now = LocalDateTime.now(clock);
            rollOverIfNewDay(now.toLocalDate());

            Double change = changeFromOpen(index, tick);
            if (change == null) {
                return;
            }

            String oldRegime = currentRegime(now);

            index.change = change;
            index.updatedAt = now;
            index.direction = nextDirection(index.direction, change);

            publish(now, oldRegime);

            String newRegime = currentRegime(now);

            if (!newRegime.equals(oldRegime)) {
                log.info(
                        "MARKET REGIME CHANGED | Regime={} | Previous={} | NIFTY={}% | BANKNIFTY={}% (from open)",
                        newRegime,
                        oldRegime,
                        freshChange(nifty, now),
                        freshChange(bankNifty, now));
            } else {
                log.debug(
                        "MARKET REGIME UPDATED | Regime={} | NIFTY={}% | BANKNIFTY={}% (from open)",
                        newRegime,
                        freshChange(nifty, now),
                        freshChange(bankNifty, now));
            }
        }
    }

    public synchronized MarketRegimeSnapshot snapshot() {
        LocalDateTime now = LocalDateTime.now(clock);

        return MarketRegimeSnapshot.builder()
                .niftyChange(freshChange(nifty, now))
                .bankNiftyChange(freshChange(bankNifty, now))
                .regime(currentRegime(now))
                // Backward-compatible DTO field: latest update from either index,
                // not proof that both indices are fresh.
                .updatedAt(latest(nifty.updatedAt, bankNifty.updatedAt))
                .build();
    }

    public void enrich(Tick tick) {
        if (tick == null) {
            return;
        }

        synchronized (this) {
            LocalDateTime now = LocalDateTime.now(clock);

            tick.setNiftyChange(freshChange(nifty, now));
            tick.setBankNiftyChange(freshChange(bankNifty, now));
            tick.setMarketRegime(currentRegime(now));
        }
    }

    /**
     * Moves the published label toward the candidate label, subject to the
     * minimum hold. Stale data and the first label after UNKNOWN apply at once.
     */
    private void publish(LocalDateTime now, String oldRegime) {
        String candidate = candidateRegime(now);

        if ("UNKNOWN".equals(candidate) || "UNKNOWN".equals(oldRegime)) {
            setPublished(candidate, now);
            return;
        }

        if (candidate.equals(publishedRegime)) {
            return;
        }

        Duration held = Duration.between(publishedAt, now);
        if (held.getSeconds() >= minHoldSeconds) {
            setPublished(candidate, now);
        } else {
            log.debug(
                    "MARKET REGIME HELD | Regime={} | Candidate={} | HeldFor={}s | MinHold={}s",
                    publishedRegime,
                    candidate,
                    held.getSeconds(),
                    minHoldSeconds);
        }
    }

    private void setPublished(String regime, LocalDateTime now) {
        if (!regime.equals(publishedRegime)) {
            publishedRegime = regime;
            publishedAt = now;
        }
    }

    private String currentRegime(LocalDateTime now) {
        if (publishedRegime == null
                || freshChange(nifty, now) == null
                || freshChange(bankNifty, now) == null) {
            return "UNKNOWN";
        }

        return publishedRegime;
    }

    private String candidateRegime(LocalDateTime now) {
        if (freshChange(nifty, now) == null || freshChange(bankNifty, now) == null) {
            return "UNKNOWN";
        }

        if (nifty.direction == Direction.UP && bankNifty.direction == Direction.UP) {
            return "BULLISH";
        }
        if (nifty.direction == Direction.DOWN && bankNifty.direction == Direction.DOWN) {
            return "BEARISH";
        }

        // Flat or mixed index direction is intentionally treated as SIDEWAYS in V1.
        return "SIDEWAYS";
    }

    /**
     * Hysteresis band around the threshold: entering UP/DOWN needs
     * threshold + buffer, leaving it needs a drop below threshold - buffer.
     */
    private Direction nextDirection(Direction current, double change) {
        double enter = directionThresholdPercent + hysteresisBufferPercent;
        double exit = Math.max(0.0, directionThresholdPercent - hysteresisBufferPercent);

        if (change >= enter) return Direction.UP;
        if (change <= -enter) return Direction.DOWN;

        if (current == Direction.UP && change > exit) return Direction.UP;
        if (current == Direction.DOWN && change < -exit) return Direction.DOWN;

        return Direction.FLAT;
    }

    /**
     * Percent move from today's open. The feed's 1d open is preferred; if the
     * feed has not supplied one, the first price seen today is latched instead.
     */
    private Double changeFromOpen(IndexState index, Tick tick) {
        Double ltp = tick.getLastTradedPrice();
        if (ltp == null || ltp <= 0) {
            return null;
        }

        Double feedOpen = tick.getDayOpenPrice();
        if (feedOpen != null && feedOpen > 0) {
            if (!index.dayOpenFromFeed || !feedOpen.equals(index.dayOpen)) {
                log.info("MARKET REGIME DAY OPEN | Index={} | Open={} | Source=FEED",
                        index.name, feedOpen);
            }
            index.dayOpen = feedOpen;
            index.dayOpenFromFeed = true;
        } else if (index.dayOpen == null) {
            index.dayOpen = ltp;
            log.warn("MARKET REGIME DAY OPEN | Index={} | Open={} | Source=FIRST_TICK "
                    + "(feed sent no 1d open; correct only if started before the open)",
                    index.name, ltp);
        }

        return ((ltp - index.dayOpen) / index.dayOpen) * 100.0;
    }

    private void rollOverIfNewDay(LocalDate today) {
        if (today.equals(tradeDate)) {
            return;
        }

        tradeDate = today;
        nifty.reset();
        bankNifty.reset();
        publishedRegime = null;
        publishedAt = null;
    }

    private Double freshChange(IndexState index, LocalDateTime now) {
        if (index.change == null || !isFresh(index.updatedAt, now)) {
            return null;
        }

        return index.change;
    }

    private boolean isFresh(LocalDateTime updatedAt, LocalDateTime now) {
        if (updatedAt == null || maxAgeSeconds < 0) {
            return false;
        }

        return !updatedAt.plusSeconds(maxAgeSeconds).isBefore(now);
    }

    private LocalDateTime latest(LocalDateTime first, LocalDateTime second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isAfter(second) ? first : second;
    }

    private String normalize(String symbol) {
        if (symbol == null) {
            return "";
        }

        // Preserve '_' so Upstox keys such as NSE_INDEX|NIFTY 50 still match.
        return symbol.trim().toUpperCase(Locale.ROOT)
                .replace("-", " ")
                .replaceAll("\\s+", " ");
    }

    /** Test hook: replaces the IST system clock. */
    void setClock(Clock clock) {
        this.clock = clock;
    }
}
