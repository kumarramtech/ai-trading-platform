package com.ram.trading.portfolio.controller;

import com.ram.trading.portfolio.dto.*;
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

    private final PortfolioService portfolioService;

    private final StockServiceClient stockServiceClient;

    @PostMapping
    public Portfolio save(@RequestBody Portfolio portfolio) {
        return portfolioService.save(portfolio);
    }

    @GetMapping
    public List<Portfolio> getAll() {
        return portfolioService.findAll();
    }

    @GetMapping("/{symbol}")
    public Portfolio getBySymbol(
            @PathVariable String symbol) {

        return portfolioService.findBySymbol(symbol);
    }

    @GetMapping("/pnl/{symbol}")
    public Mono<PortfolioPnL> getPnL(
            @PathVariable String symbol) {

        Portfolio portfolio =
                portfolioService.findBySymbol(symbol);

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

        return portfolioService.getSummary();
    }

    @GetMapping("/allocation")
    public List<PortfolioAllocation>
    getAllocation() {
        return portfolioService
                .getAllocation();
    }

    @GetMapping("/risk")
    public PortfolioRisk getRiskAnalysis() {

        return portfolioService.getRiskAnalysis();
    }

    @GetMapping("/dashboard")
    public PortfolioDashboard getDashboard() {

        return portfolioService.getDashboard();
    }

    @GetMapping("/recommendation")
    public List<PortfolioRecommendation>
    getRecommendation() {

        return portfolioService
                .getRecommendation();
    }

    @GetMapping("/health")
    public PortfolioHealth getHealthScore() {

        return portfolioService.getHealthScore();
    }
}
