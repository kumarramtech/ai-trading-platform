package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.service.replay.LowVolMomentumStudy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** "Momentum + Low Vol" study (separate strategy, research only; the live momentum book is not touched). */
@RestController
@RequiredArgsConstructor
public class LowVolMomentumController {

    private final LowVolMomentumStudy study;

    /** Starts the pre-registered backtest, both periods (outside market hours only). */
    @PostMapping("/analytics/lowvol-momentum/backtest")
    public ResponseEntity<String> start() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime t = now.toLocalTime();
        if (now.getDayOfWeek().getValue() <= 5 && !t.isBefore(LocalTime.of(9, 0)) && t.isBefore(LocalTime.of(15, 35))) {
            return ResponseEntity.status(409).body("Studies are not run during market hours (09:00-15:35 IST).");
        }
        return study.start()
                ? ResponseEntity.accepted().body("Momentum + low vol backtest started (2 periods). "
                + "GET /analytics/lowvol-momentum/backtest for the report.")
                : ResponseEntity.status(409).body("The study is already running.");
    }

    @GetMapping(value = "/analytics/lowvol-momentum/backtest", produces = "text/plain")
    public String report() {
        return study.status();
    }
}
