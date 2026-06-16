package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.service.MarketScannerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/scanner")
@RequiredArgsConstructor
public class MarketScannerController {

    private final MarketScannerService marketScannerService;

    @GetMapping("/run")
    public String runScanner() {

        marketScannerService.scanMarket();

        return "Market Scanner Triggered Successfully";
    }
}