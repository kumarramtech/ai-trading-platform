package com.ram.trading.backtesting.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "technical_indicators")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TechnicalIndicator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;

    private LocalDate tradeDate;

    private Double closePrice;

    private Double rsi14;

    private Double sma20;

    private Double sma50;

    private Double ema20;

    private Double ema50;

    private Double macd;

    private Double volumeRatio;
}