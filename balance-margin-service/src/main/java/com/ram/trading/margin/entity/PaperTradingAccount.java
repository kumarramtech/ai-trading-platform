package com.ram.trading.margin.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "paper_trading_account")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperTradingAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Double initialBalance;

    private Double availableBalance;

    private Double usedMargin;

    private Double realizedPnl;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}