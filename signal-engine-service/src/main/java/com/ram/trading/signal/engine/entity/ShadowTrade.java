package com.ram.trading.signal.engine.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A setup that a filter rejected, followed as if it had been traded (same
 * stop/target plan, no position, no charges) so each filter can be judged by
 * what it actually saved or cost. Never affects real or paper positions.
 */
@Entity
@Table(name = "shadow_trade", indexes = {
        @Index(name = "idx_shadow_trade_status", columnList = "status"),
        @Index(name = "idx_shadow_trade_entry_time", columnList = "entry_time")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShadowTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;

    /** JUDAS, ORB or MIDDAY. */
    @Column(name = "setup_strategy")
    private String strategy;

    /** BUY or SELL. */
    private String direction;

    /** Filter that rejected the setup: REGIME_GUARD, FRESH_REGIME_FLIP, MIDDAY_SIDEWAYS, EXTENDED. */
    @Column(name = "rejected_by", length = 32)
    private String rejectedBy;

    @Column(name = "market_regime", length = 16)
    private String marketRegime;

    @Column(name = "entry_time")
    private LocalDateTime entryTime;

    private Double entryPrice;

    private Double stopLoss;

    private Double targetPrice;

    /** OPEN, TARGET, STOP or MARKET_CLOSE. */
    @Column(length = 16)
    private String status;

    private Double exitPrice;

    private LocalDateTime exitTime;

    /** Gross result in R (before charges, which are ~0.2R per trade). */
    @Column(name = "r_multiple")
    private Double rMultiple;

    @Column(name = "mfe_r")
    private Double mfeR;

    @Column(name = "mae_r")
    private Double maeR;
}
