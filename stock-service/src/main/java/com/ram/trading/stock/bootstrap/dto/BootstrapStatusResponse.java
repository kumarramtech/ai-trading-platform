package com.ram.trading.stock.bootstrap.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BootstrapStatusResponse {

    private boolean ready;

    private boolean running;

    private boolean completed;

    private String message;
}