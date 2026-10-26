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

    /** Entry setup that produced the trade: JUDAS, ORB or MIDDAY. */
    @Column(name = "setup_strategy")
    private String strategy;

    /** Setup quality 0-100 at entry (SetupQualityScorer); reported by band to check it predicts results. */
    @Column(name = "quality_score")
    private Integer qualityScore;

    /**
     * Furthest the price went for (MFE) and against (MAE) the trade while it was
     * open, in R. Losers with a high MFE say the target or trailing was wrong;
     * winners with a deep MAE say the stop was too tight to be safe.
     */
    @Column(name = "mfe_r")
    private Double mfeR;

    @Column(name = "mae_r")
    private Double maeR;

    /** Market price the fill was based on, before slippage (entry and exit). */
    @Column(name = "reference_entry_price")
    private Double referenceEntryPrice;

    @Column(name = "reference_exit_price")
    private Double referenceExitPrice;

    /** Planned loss at the initial stop: |entry - initial stop| x quantity (1R). */
    @Column(name = "planned_risk")
    private Double plannedRisk;

    /** P&L before charges. profitLoss is net of charges, as a live account would show. */
    @Column(name = "gross_profit_loss")
    private Double grossProfitLoss;

    /** Brokerage + STT + exchange + SEBI + stamp duty + GST for the round trip. */
    @Column(name = "charges")
    private Double charges;

    /** Net P&L in units of planned risk (profitLoss / plannedRisk). */
    @Column(name = "r_multiple")
    private Double rMultiple;
}