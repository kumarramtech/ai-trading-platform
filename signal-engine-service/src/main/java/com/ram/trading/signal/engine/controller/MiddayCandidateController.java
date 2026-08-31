package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.dto.watchlist.TrendingStockResponse;
import com.ram.trading.signal.engine.service.MiddayCandidateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/midday")
@RequiredArgsConstructor
public class MiddayCandidateController {

    private final MiddayCandidateService middayCandidateService;

    @GetMapping("/candidates")
    public Mono<List<TrendingStockResponse>> getCandidates() {

        return middayCandidateService.discoverCandidates();
    }
}