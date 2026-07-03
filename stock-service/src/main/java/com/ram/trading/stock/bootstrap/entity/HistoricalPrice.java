package com.ram.trading.stock.bootstrap.entity;

import com.ram.trading.stock.bootstrap.CandleInterval;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "historical_price",
       indexes = {
           @Index(name = "idx_symbol_date", columnList = "symbol,tradeDate"),
           @Index(name = "idx_trade_date", columnList = "tradeDate")
       },
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {
                   "symbol",
                   "tradeDate",
                   "intervalType"
           })
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;

    private LocalDate tradeDate;

    private BigDecimal openPrice;

    private BigDecimal highPrice;

    private BigDecimal lowPrice;

    private BigDecimal closePrice;

    private Long volume;

    @Enumerated(EnumType.STRING)
    private CandleInterval intervalType;

    private String exchange;

    private LocalDateTime createdAt;
    private Long openInterest;

}