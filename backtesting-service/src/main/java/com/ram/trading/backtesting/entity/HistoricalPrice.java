package com.ram.trading.backtesting.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "historical_price")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;

    private Double price;

    private LocalDate tradeDate;
}