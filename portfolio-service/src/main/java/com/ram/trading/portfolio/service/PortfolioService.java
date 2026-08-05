package com.ram.trading.portfolio.service;

import com.ram.trading.portfolio.client.StockServiceClient;
import com.ram.trading.portfolio.contant.PortfolioHealthStatus;
import com.ram.trading.portfolio.contant.RecommendationActionEnum;
import com.ram.trading.portfolio.contant.RiskLevel;
import com.ram.trading.portfolio.dto.*;
import com.ram.trading.portfolio.entity.Portfolio;
import com.ram.trading.portfolio.repo.PortfolioRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@AllArgsConstructor
@Slf4j
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
                        () -> new IllegalArgumentException(
                                "Portfolio not found for symbol : " + symbol));
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

    public PortfolioRisk getRiskAnalysis() {

        List<PortfolioAllocation> allocations = getAllocation();

        return calculateRisk(allocations);
    }

    public PortfolioDashboard getDashboard() {

        PortfolioSummary summary = getSummary();

        PortfolioRisk risk = getRiskAnalysis();

        List<PortfolioAllocation> allocations =
                getAllocation();

        return new PortfolioDashboard(
                summary,
                risk,
                allocations
        );
    }

    public List<PortfolioRecommendation> getRecommendation() {

        List<PortfolioAllocation> allocations = getAllocation();

        return calculateRecommendation(allocations);
    }

    public PortfolioHealth getHealthScore() {

        PortfolioSummary summary = getSummary();

        List<PortfolioAllocation> allocations = getAllocation();

        PortfolioRisk risk = calculateRisk(allocations);

        return calculateHealth(summary, risk, allocations);
    }

    public PortfolioContextResponse getContext() {

        PortfolioSnapshot snapshot =
                buildSnapshot();

        PortfolioSummary summary =
                getSummary(snapshot);

        List<PortfolioAllocation> allocations =
                getAllocation(snapshot);

        PortfolioRisk risk =
                calculateRisk(allocations);

        PortfolioHealth health =
                calculateHealth(
                        summary,
                        risk,
                        allocations);

        List<PortfolioRecommendation> recommendations =
                calculateRecommendation(
                        allocations);

        return PortfolioContextResponse.builder()
                .summary(summary)
                .risk(risk)
                .health(health)
                .allocations(allocations)
                .recommendations(recommendations)
                .build();
    }

    public Portfolio openPosition(
            OpenPositionRequest request) {

        Portfolio portfolio =
                portfolioRepository
                        .findBySymbol(request.getSymbol())
                        .orElse(null);
        log.info("Opening Portfolio Position : {}", request.getSymbol());

        if (portfolio == null) {

            portfolio = new Portfolio();

            portfolio.setSymbol(request.getSymbol());
            portfolio.setQuantity(request.getQuantity());
            portfolio.setAveragePrice(request.getEntryPrice());

            return portfolioRepository.save(portfolio);
        }

        int newQuantity =
                portfolio.getQuantity() + request.getQuantity();

        double investedValue =
                (portfolio.getQuantity() * portfolio.getAveragePrice())
                        +
                        (request.getQuantity() * request.getEntryPrice());

        portfolio.setQuantity(newQuantity);

        double averagePrice =
                Math.round((investedValue / newQuantity) * 100.0) / 100.0;

        portfolio.setAveragePrice(averagePrice);

        return portfolioRepository.save(portfolio);
    }

    public Portfolio closePosition(ClosePositionRequest request) {

        Optional<Portfolio> optional =
                portfolioRepository.findBySymbol(request.getSymbol());

        log.info("Closing Portfolio Position : {}", request.getSymbol());
        if (optional.isEmpty()) {
            throw new IllegalArgumentException(
                    "Portfolio not found for symbol : "
                            + request.getSymbol());
        }

        Portfolio portfolio = optional.get();
        int remaining = portfolio.getQuantity() - request.getQuantity();

        if (remaining <= 0) {
            portfolioRepository.delete(portfolio);
            return portfolio;
        }

        portfolio.setQuantity(remaining);
        return portfolioRepository.save(portfolio);
    }


    private PortfolioSnapshot buildSnapshot() {

        List<Portfolio> portfolios = portfolioRepository.findAll();

        Map<String, StockResponse> stockPrices = new HashMap<>();

        for (Portfolio portfolio : portfolios) {

            StockResponse stockResponse =
                    stockServiceClient
                            .getStockPrice(portfolio.getSymbol())
                            .block();

            if (stockResponse != null) {
                stockPrices.put(
                        portfolio.getSymbol(),
                        stockResponse);
            }
        }

        return PortfolioSnapshot.builder()
                .portfolios(portfolios)
                .stockPrices(stockPrices)
                .build();
    }

    private PortfolioSummary getSummary(
            PortfolioSnapshot snapshot) {

        double totalInvested = 0.0;

        double currentValue = 0.0;

        for (Portfolio portfolio : snapshot.getPortfolios()) {

            StockResponse stockPrice =
                    snapshot.getStockPrices()
                            .get(portfolio.getSymbol());

            if (stockPrice == null) {
                continue;
            }

            totalInvested +=
                    portfolio.getQuantity()
                            * portfolio.getAveragePrice();

            currentValue +=
                    portfolio.getQuantity()
                            * stockPrice.getPrice();
        }

        return new PortfolioSummary(
                totalInvested,
                currentValue,
                currentValue - totalInvested);
    }

    private List<PortfolioAllocation> getAllocation(
            PortfolioSnapshot snapshot) {

        double totalPortfolioValue = 0.0;

        Map<String, Double> stockValues =
                new HashMap<>();

        for (Portfolio portfolio : snapshot.getPortfolios()) {

            StockResponse stock =
                    snapshot.getStockPrices()
                            .get(portfolio.getSymbol());

            if (stock == null) {
                continue;
            }

            double currentValue =
                    portfolio.getQuantity()
                            * stock.getPrice();

            stockValues.put(
                    portfolio.getSymbol(),
                    currentValue);

            totalPortfolioValue += currentValue;
        }

        List<PortfolioAllocation> allocations =
                new ArrayList<>();

        if (totalPortfolioValue == 0) {
            return allocations;
        }

        for (Map.Entry<String, Double> entry
                : stockValues.entrySet()) {

            double allocationPercentage =
                    (entry.getValue() / totalPortfolioValue) * 100;

            allocationPercentage =
                    Math.round(allocationPercentage * 100.0) / 100.0;

            allocations.add(
                    new PortfolioAllocation(
                            entry.getKey(),
                            entry.getValue(),
                            allocationPercentage));
        }

        return allocations;
    }

    private PortfolioHealth calculateHealth(
            PortfolioSummary summary,
            PortfolioRisk risk,
            List<PortfolioAllocation> allocations) {

        int score = 100;

        if (risk.getRiskLevel() == RiskLevel.HIGH) {
            score -= 30;
        } else if (risk.getRiskLevel() == RiskLevel.MEDIUM) {
            score -= 15;
        }

        if (summary.getTotalProfitLoss() < 0) {
            score -= 20;
        }

        boolean diversified =
                allocations.stream()
                        .noneMatch(a -> a.getAllocationPercentage() > 50);

        if (diversified) {
            score += 10;
        }

        score = Math.max(0, Math.min(score, 100));

        PortfolioHealthStatus status;

        if (score >= 80) {
            status = PortfolioHealthStatus.EXCELLENT;
        } else if (score >= 60) {
            status = PortfolioHealthStatus.GOOD;
        } else if (score >= 40) {
            status = PortfolioHealthStatus.AVERAGE;
        } else {
            status = PortfolioHealthStatus.POOR;
        }

        return new PortfolioHealth(score, status);
    }


    private PortfolioRisk calculateRisk(List<PortfolioAllocation> allocations) {

        for (PortfolioAllocation allocation : allocations) {

            if (allocation.getAllocationPercentage() > 50) {
                return new PortfolioRisk(
                        RiskLevel.HIGH,
                        allocation.getSymbol() + " allocation exceeds 50%");
            }

            if (allocation.getAllocationPercentage() > 30) {
                return new PortfolioRisk(
                        RiskLevel.MEDIUM,
                        allocation.getSymbol() + " allocation exceeds 30%");
            }
        }

        return new PortfolioRisk(
                RiskLevel.LOW,
                "Portfolio is well diversified");
    }

    private List<PortfolioRecommendation> calculateRecommendation(
            List<PortfolioAllocation> allocations) {

        List<PortfolioRecommendation> recommendations =
                new ArrayList<>();

        for (PortfolioAllocation allocation : allocations) {

            if (allocation.getAllocationPercentage() > 50.0) {

                recommendations.add(
                        new PortfolioRecommendation(
                                allocation.getSymbol(),
                                RecommendationActionEnum.REDUCE,
                                "Allocation exceeds 50%"));

            } else if (allocation.getAllocationPercentage() < 10.0) {

                recommendations.add(
                        new PortfolioRecommendation(
                                allocation.getSymbol(),
                                RecommendationActionEnum.INCREASE,
                                "Allocation below 10%"));

            } else {

                recommendations.add(
                        new PortfolioRecommendation(
                                allocation.getSymbol(),
                                RecommendationActionEnum.HOLD,
                                "Allocation is balanced"));
            }
        }

        return recommendations;
    }
}
