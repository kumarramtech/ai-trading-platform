package com.ram.trading.stock.controller;

import com.ram.trading.stock.bootstrap.BootstrapService;
import com.ram.trading.stock.bootstrap.InstrumentBootstrapService;
import com.ram.trading.stock.bootstrap.dto.BootstrapResult;
import com.ram.trading.stock.bootstrap.history.HistoricalDataBootstrapService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/bootstrap")
public class BootstrapController {

    private final BootstrapService bootstrapService;
    private final InstrumentBootstrapService instrumentBootstrapService;
    private final HistoricalDataBootstrapService historicalDataBootstrapService;

    /**
     * Download Instrument Master
     */
    @PostMapping("/instruments")
    public ResponseEntity<String> downloadInstruments() {

        log.info("Manual Instrument Bootstrap Triggered");

        instrumentBootstrapService.bootstrap();

        return ResponseEntity.ok(
                "Instrument Bootstrap Completed Successfully");

    }

    /**
     * Download Historical Data
     */
    @PostMapping("/history")
    public ResponseEntity<String> downloadHistoricalData() {

        log.info("Manual Historical Bootstrap Triggered");

        historicalDataBootstrapService.bootstrap();

        return ResponseEntity.ok(
                "Historical Bootstrap Completed Successfully");

    }

    /**
     * Complete Bootstrap
     */
    @PostMapping("/full")
    public ResponseEntity<String> fullBootstrap() {

        log.info("Manual Full Bootstrap Triggered");
        bootstrapService.bootstrap();
        return ResponseEntity.ok("Full Bootstrap Completed Successfully");

    }

}