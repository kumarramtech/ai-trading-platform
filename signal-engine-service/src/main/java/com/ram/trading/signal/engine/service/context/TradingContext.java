package com.ram.trading.signal.engine.service.context;

import com.ram.trading.signal.engine.dto.ai.NewsArticle;
import com.ram.trading.signal.engine.dto.ai.portfolio.OpenPositionContextResponse;
import com.ram.trading.signal.engine.dto.portfolio.PortfolioContextResponse;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
public class TradingContext {

    private String newsSummary;

    private String sectorSummary;

    private PortfolioContextResponse portfolioContext;

    private String riskSummary;

    private String openPositionsSummary;

    private String newsSentiment;

    private Integer newsScore;

    private OpenPositionContextResponse openPositionContext;

    private List<NewsArticle> news;
}