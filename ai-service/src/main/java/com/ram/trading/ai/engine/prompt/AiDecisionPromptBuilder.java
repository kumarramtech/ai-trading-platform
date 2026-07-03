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

        builder.append("\nPortfolio Summary : ")
                .append(request.getPortfolioSummary());

        return builder.toString();
    }

}