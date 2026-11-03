package com.ram.trading.signal.engine.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One short in the FADE paper book: a turnover rank 501-600 stock up >= 2% vs NIFTY at
 * 09:45, shorted at the 09:45 close and held to 15:00 unless its stop or the book's
 * daily stop closes it. Paper only: separate from paper_trade, the live engine and the
 * balance-margin service.
 */
@Entity
@Table(name = "fade_paper_trade", indexes = {
        @Index(name = "idx_fade_paper_trade_day", columnList = "trade_date"),
        @Index(name = "idx_fade_paper_trade_status", columnList = "status")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FadePaperTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false, length = 64)
    private String symbol;

    /** 1-based rank among the liquid stocks by 20-session turnover. */
    private Integer turnoverRank;

    /** % move from the 09:15 open less NIFTY's, at 09:45. */
    private Double relMovePct;

    private Double niftyMovePct;

    private Integer quantity;

    /** The 09:45 close less slippage (a short sells lower). */
    private Double entryPrice;

    private LocalDateTime entryTime;

    private Double stopLoss;

    /** Close of the last minute checked: the mark for the open P&L. */
    private Double lastPrice;

    /** Start of the last minute candle processed (restart-safe progress). */
    private LocalDateTime lastMinute;

    private Double exitPrice;

    private LocalDateTime exitTime;

    /** STOP, DAY_STOP, TAKE_PROFIT or TIME. */
    @Column(length = 16)
    private String exitReason;

    private Double charges;

    /** After slippage and charges. */
    private Double netPnl;

    /** OPEN or CLOSED. */
    @Column(length = 8)
    private String status;

    /** The book settings the trade ran under, e.g. "5x40000 stop 2% day 1500". */
    @Column(length = 64)
    private String book;
}
