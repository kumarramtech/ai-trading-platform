package com.ram.trading.signal.engine.service.replay;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Daily candles from historical_price, for the facts a trader knows before the
 * open: yesterday's close and the 20-session average volume and turnover.
 * Shared by the strategy replay and the 09:30 opening playbook.
 */
public final class DailyBars {

    public record Bar(LocalDate date, double close, long volume) {
    }

    /** As of a trading day: only sessions strictly before it. */
    public record Stats(double prevClose, double avgVolume20, double avgTurnover20) {
    }

    private final Map<String, List<Bar>> bars;

    private DailyBars(Map<String, List<Bar>> bars) {
        this.bars = bars;
    }

    /** Daily bars of the given symbols (all symbols when null) from {@code from} to before {@code toExclusive}. */
    public static DailyBars load(JdbcTemplate jdbc, Collection<String> symbols, LocalDate from, LocalDate toExclusive) {
        Map<String, List<Bar>> bars = new HashMap<>();
        StringBuilder sql = new StringBuilder(
                "select symbol, trade_date, close_price, volume from historical_price "
                        + "where interval_type = 'DAY' and trade_date >= ? and trade_date < ?");
        List<Object> args = new ArrayList<>(List.of(from, toExclusive));
        if (symbols != null) {
            if (symbols.isEmpty()) {
                return new DailyBars(bars);
            }
            sql.append(" and symbol in (").append(String.join(",", symbols.stream().map(s -> "?").toList())).append(")");
            args.addAll(symbols);
        }
        sql.append(" order by symbol, trade_date");

        jdbc.query(sql.toString(), rs -> {
            if (rs.getBigDecimal("close_price") == null) {
                return;
            }
            bars.computeIfAbsent(rs.getString("symbol"), k -> new ArrayList<>())
                    .add(new Bar(rs.getDate("trade_date").toLocalDate(),
                            rs.getBigDecimal("close_price").doubleValue(),
                            rs.getLong("volume")));
        }, args.toArray());
        return new DailyBars(bars);
    }

    /** Null when the symbol has fewer than 5 sessions before the day. */
    public Stats statsBefore(String symbol, LocalDate day) {
        List<Bar> list = bars.get(symbol);
        if (list == null) {
            return null;
        }
        int end = 0;
        while (end < list.size() && list.get(end).date().isBefore(day)) {
            end++;
        }
        if (end < 5) {
            return null;
        }
        int start = Math.max(0, end - 20);
        double volume = 0;
        double turnover = 0;
        for (int i = start; i < end; i++) {
            volume += list.get(i).volume();
            turnover += list.get(i).volume() * list.get(i).close();
        }
        int n = end - start;
        return new Stats(list.get(end - 1).close(), volume / n, turnover / n);
    }
}
