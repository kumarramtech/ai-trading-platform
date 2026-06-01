package com.ram.trading.signal.engine.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "trading_signal")
@Data
@NoArgsConstructor
@AllArgsConstructor
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
}