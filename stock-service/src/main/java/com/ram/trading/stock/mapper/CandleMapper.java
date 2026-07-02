package com.ram.trading.stock.mapper;

import com.ram.trading.stock.dto.history.Candle;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Component
public class CandleMapper {

    public List<Candle> map(List<List<Object>> source) {

        return source.stream()
                .map(this::toCandle)
                .toList();

    }

    private Candle toCandle(List<Object> values) {

        return Candle.builder()
                .dateTime(OffsetDateTime.parse(values.get(0).toString()))
                .open(BigDecimal.valueOf(((Number) values.get(1)).doubleValue()))
                .high(BigDecimal.valueOf(((Number) values.get(2)).doubleValue()))
                .low(BigDecimal.valueOf(((Number) values.get(3)).doubleValue()))
                .close(BigDecimal.valueOf(((Number) values.get(4)).doubleValue()))
                .volume(((Number) values.get(5)).longValue())
                .openInterest(((Number) values.get(6)).longValue())
                .build();

    }

}