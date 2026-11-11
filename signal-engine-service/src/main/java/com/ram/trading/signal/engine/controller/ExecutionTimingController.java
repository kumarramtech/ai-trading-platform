package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.service.replay.ExecutionTimingStudy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Execution timing study (research only, separate from every strategy). */
@RestController
@RequiredArgsConstructor
public class ExecutionTimingController {

    private final ExecutionTimingStudy study;

    /** Starts the study over the last {@code months} months (outside market hours only). */
    @PostMapping("/analytics/execution-timing")
    public ResponseEntity<String> start(@RequestParam(defaultValue = "12") int months) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime t = now.toLocalTime();
        if (now.getDayOfWeek().getValue() <= 5 && !t.isBefore(LocalTime.of(9, 0)) && t.isBefore(LocalTime.of(15, 35))) {
            return ResponseEntity.status(409).body("Studies are not run during market hours (09:00-15:35 IST).");
        }
        int m = Math.max(2, Math.min(months, 18));
        return study.start(m)
                ? ResponseEntity.accepted().body("Execution timing study started: " + m
                + " months. GET /analytics/execution-timing for the report.")
                : ResponseEntity.status(409).body("The study is already running.");
    }

    @GetMapping(value = "/analytics/execution-timing", produces = "text/plain")
    public String report() {
        return study.status();
    }
}
