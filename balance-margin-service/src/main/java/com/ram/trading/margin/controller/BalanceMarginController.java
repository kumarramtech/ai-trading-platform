package com.ram.trading.margin.controller;


import com.ram.trading.margin.dto.*;
import com.ram.trading.margin.service.BalanceMarginService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/balance")
@RequiredArgsConstructor
public class BalanceMarginController {

    private final BalanceMarginService balanceMarginService;

    @GetMapping
    public Mono<BalanceMarginResponse> getBalance() {

        return balanceMarginService.getBalance();
    }

    @PostMapping("/calculate-margin")
    public Mono<MarginCalculationResponse> calculateMargin(
            @RequestBody UpstoxMarginRequest request) {

        return balanceMarginService.calculateMargin(request);
    }
}