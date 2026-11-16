package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.service.pickstudy.PickStudyService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/** Pick study: intraday picks vs stocks not picked (measure only; changes no strategy). */
@RestController
@RequiredArgsConstructor
public class PickStudyController {

    private final PickStudyService study;

    @GetMapping(value = "/analytics/pick-study", produces = "text/plain")
    public String report() {
        return study.report();
    }

    @GetMapping(value = "/analytics/pick-study/summary", produces = "application/json")
    public List<?> summary() {
        return study.summary();
    }

    /** Re-measures one day's recorded picks (outside market hours only). */
    @PostMapping("/analytics/pick-study/measure")
    public ResponseEntity<String> measure(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime t = now.toLocalTime();
        if (now.getDayOfWeek().getValue() <= 5 && !t.isBefore(LocalTime.of(9, 0)) && t.isBefore(LocalTime.of(15, 35))) {
            return ResponseEntity.status(409).body("Not during market hours (09:00-15:35 IST).");
        }
        return ResponseEntity.ok(study.measure(day) + " picks measured for " + day);
    }
}
