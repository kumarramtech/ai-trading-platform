package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.service.replay.EtfTrendStudy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** "ETF Trend" study (separate strategy, research only; no other strategy is touched). */
@RestController
@RequiredArgsConstructor
public class EtfTrendController {

    private final EtfTrendStudy study;

    /** Starts the pre-registered backtest (outside market hours only). */
    @PostMapping("/analytics/etf-trend/backtest")
    public ResponseEntity<String> start() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime t = now.toLocalTime();
        if (now.getDayOfWeek().getValue() <= 5 && !t.isBefore(LocalTime.of(9, 0)) && t.isBefore(LocalTime.of(15, 35))) {
            return ResponseEntity.status(409).body("Studies are not run during market hours (09:00-15:35 IST).");
        }
        return study.start()
                ? ResponseEntity.accepted().body("ETF trend backtest started. GET /analytics/etf-trend/backtest for the report.")
                : ResponseEntity.status(409).body("The study is already running.");
    }

    @GetMapping(value = "/analytics/etf-trend/backtest", produces = "text/plain")
    public String report() {
        return study.status();
    }
}
