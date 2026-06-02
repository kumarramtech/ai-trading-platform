package com.ram.trading.portfolio.service;

import com.ram.trading.portfolio.entity.Portfolio;
import com.ram.trading.portfolio.repo.PortfolioRepository;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
public class PortfolioService {

    PortfolioRepository portfolioRepository;

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
}
