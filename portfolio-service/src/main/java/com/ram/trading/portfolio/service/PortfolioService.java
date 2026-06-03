package com.ram.trading.portfolio.service;

import com.ram.trading.portfolio.dto.PortfolioAllocation;
import com.ram.trading.portfolio.dto.PortfolioSummary;
import com.ram.trading.portfolio.dto.StockResponse;
import com.ram.trading.portfolio.entity.Portfolio;
import com.ram.trading.portfolio.repo.PortfolioRepository;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@AllArgsConstructor
public class PortfolioService {

    PortfolioRepository portfolioRepository;

    StockServiceClient stockServiceClient;

    public Portfolio save(Portfolio portfolio) {
        return portfolioRepository.save(portfolio);
    }

    public List<Portfolio> findAll() {
        return portfolioRepository.findAll();
    }

    public Portfolio findBySymbol(String symbol) {

        return portfolioRepository.findBySymbol(symbol)
                .orElseThrow(
                        () -> new RuntimeException(
                                "Portfolio not found"));
    }

    public PortfolioSummary getSummary() {
        List<Portfolio> portfolios = portfolioRepository.findAll();

        double totalInvested = 0.0;
        double currentValue = 0.0;
        for (Portfolio portfolio : portfolios) {

            StockResponse stockPrice = stockServiceClient
                    .getStockPrice(portfolio.getSymbol())
                    .block();

            totalInvested +=
                    portfolio.getQuantity() * portfolio.getAveragePrice();

            currentValue +=
                    portfolio.getQuantity() * stockPrice.getPrice();
        }

        double totalProfitLoss = currentValue - totalInvested;

        return new PortfolioSummary(
                totalInvested,
                currentValue,
                totalProfitLoss
        );

    }

    public List<PortfolioAllocation> getAllocation() {

        List<Portfolio> portfolios =
                portfolioRepository.findAll();

        double totalPortfolioValue = 0.0;

        Map<String, Double> stockValues =
                new HashMap<>();

        for (Portfolio portfolio : portfolios) {

            StockResponse stock =
                    stockServiceClient
                            .getStockPrice(
                                    portfolio.getSymbol())
                            .block();

            double currentValue =
                    portfolio.getQuantity()
                            * stock.getPrice();

            stockValues.put(
                    portfolio.getSymbol(),
                    currentValue);

            totalPortfolioValue += currentValue;
        }

        List<PortfolioAllocation> result =
                new ArrayList<>();

        for (Map.Entry<String, Double> entry
                : stockValues.entrySet()) {

            double allocationPercentage =
                    (entry.getValue() / totalPortfolioValue) * 100;

            allocationPercentage =
                    Math.round(allocationPercentage * 100.0) / 100.0;

            result.add(
                    new PortfolioAllocation(
                            entry.getKey(),
                            entry.getValue(),
                            allocationPercentage
                    )
            );
        }

        return result;
    }
}
