package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.dto.watchlist.MiddayRevalidationResult;
import com.ram.trading.signal.engine.service.MiddayRevalidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/midday")
@RequiredArgsConstructor
public class MiddayRevalidationController {

    private final MiddayRevalidationService middayRevalidationService;

    /**
     * TEMPORARY TEST ENDPOINT
     *
     * Revalidates the candidates discovered at 11:30 AM.
     *
     * This endpoint is only for V2.2 testing.
     *
     * It does NOT:
     * - generate BUY/SELL
     * - call AI
     * - call Risk Guard
     * - create paper trades
     */
    @GetMapping("/revalidate")
    public Mono<List<MiddayRevalidationResult>> revalidate() {

        return middayRevalidationService.revalidateCandidates();
    }
}