package com.ram.trading.signal.engine.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "minute_candle", uniqueConstraints = @UniqueConstraint(
        name = "uk_minute_candle_symbol_minute", columnNames = {"symbol", "candle_minute"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MinuteCandleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(name = "candle_minute", nullable = false)
    private LocalDateTime minute;

    private Double openPrice;
    private Double highPrice;
    private Double lowPrice;
    private Double closePrice;
    private Long volume;
}
