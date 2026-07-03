package com.ram.trading.stock.bootstrap.dto;

import com.ram.trading.stock.bootstrap.BootstrapStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class BootstrapResult {

    private BootstrapStatus status;

    private BootstrapStage currentStage;

    private Instant startedAt;

    private Instant completedAt;

    private long durationInMillis;

    private List<String> messages;

}