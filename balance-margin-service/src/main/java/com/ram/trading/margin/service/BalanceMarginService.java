package com.ram.trading.margin.service;

import com.ram.trading.margin.dto.BalanceMarginResponse;
import com.ram.trading.margin.dto.MarginCalculationResponse;
import com.ram.trading.margin.dto.UpstoxMarginRequest;
import com.ram.trading.margin.dto.UpstoxMarginResponse;
import reactor.core.publisher.Mono;

public interface BalanceMarginService {

    Mono<BalanceMarginResponse> getBalance();
    Mono<MarginCalculationResponse> calculateMargin(
            UpstoxMarginRequest request);
}