package com.ram.trading.signal.engine.dto;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "paper_trade")
public class PaperTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;

    private String signal;

    private Double entryPrice;

    private Integer quantity;

    private Double investedAmount;

    private Double exitPrice;

    private Double profitLoss;

    private String status;

    private LocalDateTime entryTime;

    private LocalDateTime exitTime;
}