package com.ram.trading.stock.controller;

import com.ram.trading.stock.bootstrap.dto.BootstrapStatusResponse;
import com.ram.trading.stock.service.instument.BootstrapStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootstrap")
@RequiredArgsConstructor
public class BootstrapStatusController {

    private final BootstrapStatusService bootstrapStatusService;

    @GetMapping("/status")
        public BootstrapStatusResponse status() {

        return BootstrapStatusResponse.builder()
                .ready(bootstrapStatusService.isReadyForTrading())
                .running(bootstrapStatusService.isBootstrapRunning())
                .completed(bootstrapStatusService.isBootstrapCompleted())
                .build();
    }
}