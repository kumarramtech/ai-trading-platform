package com.ram.trading.stock.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "instrument_master",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_broker_symbol",
                        columnNames = {"broker", "trading_symbol"}
                )
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Instrument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String broker;

    @Column(nullable = false)
    private String exchange;

    @Column(nullable = false)
    private String segment;

    @Column(name = "trading_symbol", nullable = false)
    private String tradingSymbol;

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "instrument_key", nullable = false, unique = true)
    private String instrumentKey;

    @Column(name = "exchange_token")
    private String exchangeToken;

    private String isin;

    @Column(name = "instrument_type")
    private String instrumentType;

    @Column(name = "tick_size")
    private BigDecimal tickSize;

    @Column(name = "lot_size")
    private Integer lotSize;

    @Column(name = "freeze_quantity")
    private Integer freezeQuantity;

    private LocalDate expiry;

    @Column(name = "strike_price")
    private BigDecimal strikePrice;

    @Column(name = "option_type")
    private String optionType;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}