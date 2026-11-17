package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.service.replay.IntradayFixStudy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** Intraday fix study (research only; the frozen intraday rules are not changed). */
@RestController
@RequiredArgsConstructor
public class IntradayFixController {

    private final IntradayFixStudy study;

    @PostMapping("/analytics/intraday-fix/backtest")
    public ResponseEntity<String> start() {
        if (IntradayFixStudy.marketHours(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))) {
            return ResponseEntity.status(409).body("Studies are not run during market hours (09:00-15:35 IST).");
        }
        return study.start()
                ? ResponseEntity.accepted().body("Intraday fix study started. GET /analytics/intraday-fix/backtest for the report.")
                : ResponseEntity.status(409).body("The study is already running.");
    }

    @GetMapping(value = "/analytics/intraday-fix/backtest", produces = "text/plain")
    public String report() {
        return study.status();
    }
}
