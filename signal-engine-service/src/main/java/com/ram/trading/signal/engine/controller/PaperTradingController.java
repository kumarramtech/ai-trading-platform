package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.dto.PaperTradeSummary;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.service.PaperTradingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/paper-trades")
public class PaperTradingController {

    private final PaperTradingService service;

    @GetMapping("/status/{status}")
    public List<PaperTrade> getByStatus(
            @PathVariable SignalStatus status) {

        return service.getByStatus(status);
    }

    @GetMapping
    public List<PaperTrade> getAll() {

        return service.getAll();
    }

    @GetMapping("/summary")
    public PaperTradeSummary getSummary() {

        return service.getSummary();
    }

}