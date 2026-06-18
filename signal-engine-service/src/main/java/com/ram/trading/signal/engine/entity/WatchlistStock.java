package com.ram.trading.signal.engine.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "watchlist_stock")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchlistStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;

    private Boolean active;

    private LocalDateTime createdAt;
}