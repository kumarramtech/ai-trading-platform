package com.ram.trading.signal.engine.entity;

import com.ram.trading.signal.engine.contant.SignalStatus;
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

    private Double exitPrice;

    private Double profitLoss;

    @Enumerated(EnumType.STRING)
    private SignalStatus status;

    private LocalDateTime entryTime;

    private LocalDateTime exitTime;
}