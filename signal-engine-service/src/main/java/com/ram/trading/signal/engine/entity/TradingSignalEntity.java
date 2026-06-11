package com.ram.trading.signal.engine.entity;

import com.ram.trading.signal.engine.contant.SignalStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "trading_signal")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradingSignalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;

    @Column(name = "trading_signal")
    private String signal;

    @Column(name = "entry_price")
    private Double entryPrice;

    @Column(name = "target_price")
    private Double targetPrice;

    @Column(name = "stop_loss")
    private Double stopLoss;

    private Integer confidence;

    private String reason;

    private LocalDateTime signalTime;

    @Enumerated(EnumType.STRING)
    private SignalStatus status;

    private Double profitLoss;

    private Double exitPrice;

    private LocalDateTime exitTime;

    private LocalDateTime createdAt;

    private Double rsi;

    private Double ema20;

    private Double ema50;

    private Double macd;

}