package com.ram.trading.portfolio.controller;

import com.ram.trading.portfolio.dto.PortfolioAllocation;
import com.ram.trading.portfolio.dto.PortfolioPnL;
import com.ram.trading.portfolio.dto.PortfolioSummary;
import com.ram.trading.portfolio.entity.Portfolio;
import com.ram.trading.portfolio.service.PortfolioService;
import com.ram.trading.portfolio.service.StockServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioService service;

    private final StockServiceClient stockServiceClient;

    @PostMapping
    public Portfolio save(@RequestBody Portfolio portfolio) {
        return service.save(portfolio);
    }

    @GetMapping
    public List<Portfolio> getAll() {
        return service.findAll();
    }

    @GetMapping("/{symbol}")
    public Portfolio getBySymbol(
            @PathVariable String symbol) {

        return service.findBySymbol(symbol);
    }

    @GetMapping("/pnl/{symbol}")
    public Mono<PortfolioPnL> getPnL(
            @PathVariable String symbol) {

        Portfolio portfolio =
                service.findBySymbol(symbol);

        return stockServiceClient
                .getStockPrice(symbol)
                .map(stock -> {

                    double pnl =
                            (stock.getPrice() -
                                    portfolio.getAveragePrice())
                                    * portfolio.getQuantity();

                    return new PortfolioPnL(
                            portfolio.getSymbol(),
                            portfolio.getQuantity(),
                            portfolio.getAveragePrice(),
                            stock.getPrice(),
                            pnl
                    );
                });
    }

    @GetMapping("/summary")
    public PortfolioSummary getSummary() {

        return service.getSummary();
    }

    @GetMapping("/allocation")
    public List<PortfolioAllocation>
    getAllocation() {
        return service
                .getAllocation();
    }
}
