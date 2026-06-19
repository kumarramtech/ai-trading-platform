package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.dto.TradeLifecycleDto;
import com.ram.trading.signal.engine.entity.Opportunity;
import com.ram.trading.signal.engine.service.OpportunityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/opportunities")
@RequiredArgsConstructor
public class OpportunityController {

    private final OpportunityService service;

    @GetMapping
    public List<Opportunity> getAll() {

        return service.getAll();
    }

    @GetMapping("/top")
    public List<Opportunity> top() {
        return service.getTopOpportunities();
    }

    @GetMapping("/latestopportunity")
    public List<Opportunity> latestOpportunity() {
        return service.latestOpportunities();
    }

   /* @PostMapping("/select")
    public String selectTopOpportunities() {
        service.markTopOpportunities();
        return "Top opportunities selected";
    }*/

    @PostMapping("/execute")
    public Mono<String> execute() {
        service.markTopOpportunities();
        return service.executeSelectedOpportunities()
                .thenReturn("Execution Started");
    }

    @GetMapping("/trade-lifecycle")
    public List<TradeLifecycleDto> getTradeLifecycle() {
        return service.getTradeLifecycle();
    }
}