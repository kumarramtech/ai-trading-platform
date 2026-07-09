package com.ram.trading.ai.engine.prompt;

import com.ram.trading.ai.engine.dto.MarketContext;
import com.ram.trading.ai.engine.dto.SignalGenerationRequest;
import com.ram.trading.ai.engine.dto.TradingDecisionRequest;
import org.springframework.stereotype.Component;

@Component
public class AiDecisionPromptBuilder {

    public String buildPrompt(
            TradingDecisionRequest request) {

        StringBuilder builder = new StringBuilder();

        builder.append(AiDecisionPrompt.TRADING_DECISION_PROMPT);

        builder.append("\n\n");

        builder.append("Technical Signal : ")
                .append(request.getTechnicalDecision().getSignal());

        builder.append("\nConfidence : ")
                .append(request.getTechnicalDecision().getConfidence());

        builder.append("\nReasons : ")
                .append(request.getTechnicalDecision().getReasons());

        builder.append("\n");

        SignalGenerationRequest signal =
                request.getSignalRequest();

        builder.append("\nSymbol : ")
                .append(signal.getSymbol());

        builder.append("\nCurrent Price : ")
                .append(signal.getCurrentPrice());

        builder.append("\nRSI : ")
                .append(signal.getRsi());

        builder.append("\nEMA20 : ")
                .append(signal.getEma20());

        builder.append("\nEMA50 : ")
                .append(signal.getEma50());

        builder.append("\nMACD : ")
                .append(signal.getMacd());

        builder.append("\nSignal Line : ")
                .append(signal.getSignalLine());

        builder.append("\nATR : ")
                .append(signal.getAtr());

        builder.append("\nVolume : ")
                .append(signal.getVolume());

        MarketContext market =
                signal.getMarketContext();

        if (market != null) {

            builder.append("\nMarket Trend : ")
                    .append(market.getMarketTrend());

            builder.append("\nSector Trend : ")
                    .append(market.getSectorTrend());

            builder.append("\nNews Sentiment : ")
                    .append(market.getNewsSentiment());

            builder.append("\nTrading Session : ")
                    .append(market.getTradingSession());

            builder.append("\nNIFTY : ")
                    .append(market.getNiftyChange());

            builder.append("\nBANK NIFTY : ")
                    .append(market.getBankNiftyChange());

            builder.append("\nVolatility Index : ")
                    .append(market.getVolatilityIndex());

        }

        builder.append("\nNews Summary : ")
                .append(request.getNewsSummary() != null
                        ? request.getNewsSummary()
                        : "Not Available");

        builder.append("\nSector Summary : ")
                .append(request.getSectorSummary());

        if (request.getPortfolioContext() != null) {

            var portfolio = request.getPortfolioContext();

            builder.append("\n\n========== PORTFOLIO CONTEXT ==========");

            if (portfolio.getSummary() != null) {

                builder.append("\nTotal Invested : ")
                        .append(portfolio.getSummary().getTotalInvested());

                builder.append("\nCurrent Value : ")
                        .append(portfolio.getSummary().getCurrentValue());

                builder.append("\nProfit/Loss : ")
                        .append(portfolio.getSummary().getTotalProfitLoss());
            }

            if (portfolio.getRisk() != null) {

                builder.append("\nRisk Level : ")
                        .append(portfolio.getRisk().getRiskLevel());

                builder.append("\nRisk Message : ")
                        .append(portfolio.getRisk().getMessage());
            }

            if (portfolio.getHealth() != null) {

                builder.append("\nHealth Score : ")
                        .append(portfolio.getHealth().getScore());

                builder.append("\nHealth Status : ")
                        .append(portfolio.getHealth().getStatus());
            }

            if (portfolio.getAllocations() != null
                    && !portfolio.getAllocations().isEmpty()) {

                builder.append("\n\nAllocations:");

                portfolio.getAllocations().forEach(a ->
                        builder.append("\n- ")
                                .append(a.getSymbol())
                                .append(" : ")
                                .append(a.getAllocationPercentage())
                                .append("%"));
            }

            if (portfolio.getRecommendations() != null
                    && !portfolio.getRecommendations().isEmpty()) {

                builder.append("\n\nPortfolio Recommendations:");

                portfolio.getRecommendations().forEach(r ->
                        builder.append("\n- ")
                                .append(r.getSymbol())
                                .append(" : ")
                                .append(r.getAction())
                                .append(" (")
                                .append(r.getReason())
                                .append(")"));
            }

            if (request.getOpenPositionContext() != null) {

                var position = request.getOpenPositionContext();

                builder.append("\n\n========== OPEN POSITION ==========");

                builder.append("\nPosition Exists : ")
                        .append(position.isPositionExists());

                if (position.isPositionExists()) {

                    builder.append("\nSignal : ")
                            .append(position.getSignal());

                    builder.append("\nQuantity : ")
                            .append(position.getQuantity());

                    builder.append("\nEntry Price : ")
                            .append(position.getEntryPrice());

                    builder.append("\nCurrent Price : ")
                            .append(position.getCurrentPrice());

                    builder.append("\nCurrent PnL : ")
                            .append(position.getCurrentPnL());

                    builder.append("\nPnL % : ")
                            .append(position.getPnlPercentage());

                    builder.append("\nStop Loss : ")
                            .append(position.getStopLoss());

                    builder.append("\nTarget : ")
                            .append(position.getTargetPrice());

                    builder.append("\nStatus : ")
                            .append(position.getStatus());
                }

                builder.append("\n===================================");
            }

            builder.append("\n======================================");
        }

        return builder.toString();
    }

}