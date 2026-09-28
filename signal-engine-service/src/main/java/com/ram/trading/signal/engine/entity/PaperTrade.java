package com.ram.trading.signal.engine.entity;

import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.dto.market.ExitReason;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "paper_trade")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long signalId;

    private String symbol;

    @Column(name = "paper_signal")
    private String signal;

    private Double entryPrice;

    private Integer quantity;

    private Double investedAmount;

    private Double requiredMargin;

    private Double leverage;

    private Double exitPrice;

    private Double profitLoss;

    @Enumerated(EnumType.STRING)
    private SignalStatus status;

    private LocalDateTime entryTime;

    private LocalDateTime exitTime;

    private Double rsi;

    private Double ema20;

    private Double ema50;

    private Double macd;

    private Integer confidence;

    private Double targetPrice;

    private Double stopLoss;

    private Double initialStopLoss;

    private Double currentStopLoss;

    private Integer trailingStep;

    private LocalDateTime lastTrailingUpdate;

    @Enumerated(EnumType.STRING)
    private ExitReason exitReason;

    @Column(name = "target_extended")
    private Boolean targetExtended = false;

    @Column(name = "original_target_price")
    private Double originalTargetPrice;

    @Column(name = "target_extended_time")
    private LocalDateTime targetExtendedTime;

    @Column(name = "target_extension_reason")
    private String targetExtensionReason;
}