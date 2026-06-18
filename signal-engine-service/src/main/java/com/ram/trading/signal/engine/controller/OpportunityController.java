package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.entity.Opportunity;
import com.ram.trading.signal.engine.service.OpportunityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}