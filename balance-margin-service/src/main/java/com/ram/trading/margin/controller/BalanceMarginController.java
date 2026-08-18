package com.ram.trading.margin.controller;


import com.ram.trading.margin.dto.*;
import com.ram.trading.margin.service.BalanceMarginService;
import com.ram.trading.margin.service.PaperTradingAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/balance")
@RequiredArgsConstructor
public class BalanceMarginController {

    private final BalanceMarginService balanceMarginService;

    private final PaperTradingAccountService paperTradingAccountService;

    @GetMapping
    public Mono<BalanceMarginResponse> getBalance() {

        return balanceMarginService.getBalance();
    }

    @PostMapping("/calculate-margin")
    public Mono<MarginCalculationResponse> calculateMargin(
            @RequestBody UpstoxMarginRequest request) {

        return balanceMarginService.calculateMargin(request);
    }

    @PostMapping("/reserve-margin")
    public Mono<Void> reserveMargin(
            @RequestBody ReserveMarginRequest request) {

        return paperTradingAccountService
                .reserveMargin(request.getRequiredMargin());
    }

    @PostMapping("/release-margin")
    public Mono<Void> releaseMargin(
            @RequestBody ReleaseMarginRequest request) {

        return paperTradingAccountService
                .releaseMargin(
                        request.getRequiredMargin(),
                        request.getProfitLoss());
    }
}