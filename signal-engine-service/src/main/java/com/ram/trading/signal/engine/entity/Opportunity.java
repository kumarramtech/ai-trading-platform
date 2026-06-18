package com.ram.trading.signal.engine.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "opportunity")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class Opportunity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;

    private String opportunitySignal;

    private Integer confidence;

    private Integer newsScore;

    private String sentiment;

    private Boolean selected;

    private LocalDateTime createdAt;
}