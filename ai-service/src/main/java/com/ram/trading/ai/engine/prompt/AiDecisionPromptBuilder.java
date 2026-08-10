package com.ram.trading.ai.engine.prompt;

import com.ram.trading.ai.engine.dto.MarketContext;
import com.ram.trading.ai.engine.dto.NewsArticle;
import com.ram.trading.ai.engine.dto.SignalGenerationRequest;
import com.ram.trading.ai.engine.dto.TradingDecisionRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AiDecisionPromptBuilder {

    public String buildPrompt(TradingDecisionRequest request) {

        String newsSection = buildNewsSection(request.getNews());

        String portfolioSection =
                request.getPortfolioContext() != null
                        ? request.getPortfolioContext().toString()
                        : "No portfolio context available.";

        String openPositionSection =
                request.getOpenPositionContext() != null
                        ? request.getOpenPositionContext().toString()
                        : "No open position context available.";

        String technicalDecisionSection =
                request.getTechnicalDecision() != null
                        ? request.getTechnicalDecision().toString()
                        : "No technical decision available.";

        String signalRequestSection =
                request.getSignalRequest() != null
                        ? request.getSignalRequest().toString()
                        : "No signal request available.";

        String sectorSummary =
                request.getSectorSummary() != null
                        ? request.getSectorSummary()
                        : "No sector summary available.";

        return """
            You are the final AI decision engine for an intraday trading system.

            Your responsibility is to validate or override the engineering
            recommendation using:

            • Current Market Context
            • Technical Decision
            • Supplied raw news articles
            • Your own analysis of the news
            • Portfolio Context
            • Existing Open Positions
            • Overall Risk
            • Capital Preservation

            You are responsible for performing the final news analysis yourself.

            IMPORTANT:
            Do NOT rely on any precomputed news sentiment, news score,
            or news summary.

            Analyze the supplied news articles directly.

            =======================================================
            DECISION PRIORITY
            =======================================================

            1. Capital Preservation
            2. Risk Management
            3. Existing Open Position
            4. Engineering Decision
            5. News Analysis
            6. Portfolio Context
            7. Market Context

            Do NOT contradict the engineering decision unless there is
            strong contextual evidence.

            Override the engineering recommendation ONLY when there is
            strong evidence such as:

            • Extremely negative and material company-specific news
            • Extremely positive and material news contradicting a SELL
            • Very high portfolio risk
            • Existing position already satisfies the objective
            • Major market or risk event
            • Strong conflict between technical and fundamental/news evidence

            =======================================================
            SIGNAL REQUEST
            =======================================================

            %s

            =======================================================
            ENGINEERING / TECHNICAL DECISION
            =======================================================

            %s

            Treat the Engineering Decision as the primary technical
            assessment.

            Do not invent technical indicator values.
            Use the values supplied in the request.

            =======================================================
            SECTOR CONTEXT
            =======================================================

            %s

            =======================================================
            LATEST NEWS
            =======================================================

            %s

            =======================================================
            NEWS ANALYSIS RULES
            =======================================================

            Analyze the supplied latest news articles yourself.

            For relevant articles consider:

            • Relevance to the specific company
            • Positive or negative business impact
            • Potential impact on the stock price
            • Whether the news is a material catalyst
            • Freshness of the news
            • Reliability of the source
            • Whether multiple articles describe the same event

            Give higher importance to recent and material company-specific news.

            For intraday decisions, consider the Published At timestamp.

            Give greater weight to recent news that can reasonably affect
            the current trading session.

            Do not treat old news as a fresh trading catalyst unless there
            is evidence that it remains materially relevant.

            Prioritize:

            1. Company-specific material events
            2. Earnings / revenue / guidance
            3. Major orders or contracts
            4. Regulatory or legal developments
            5. Mergers, acquisitions or corporate actions
            6. Management announcements
            7. Sector developments
            8. General market commentary

            Ignore or heavily discount:

            • Duplicate articles
            • Old information with no current relevance
            • Promotional content
            • Low-impact commentary
            • News unrelated to the company
            • Articles that do not provide meaningful trading information

            If multiple articles describe the same event, treat them as
            one underlying event rather than counting them as multiple
            independent positive or negative signals.

            Do not assume that positive news automatically means BUY.

            Do not assume that negative news automatically means SELL.

            News is contextual evidence and must be evaluated together with:

            • Engineering Decision
            • Risk
            • Market Context
            • Portfolio Context
            • Existing Position

            If technical and news signals conflict, evaluate the strength,
            freshness and relevance of the news before overriding the
            Engineering Decision.

            =======================================================
            PORTFOLIO CONTEXT
            =======================================================

            %s

            =======================================================
            OPEN POSITION CONTEXT
            =======================================================

            %s

            =======================================================
            BUY RULES
            =======================================================

            Recommend BUY only if:

            • Engineering recommends BUY.
            • Technical confidence is sufficiently strong.
            • There is no material bearish news catalyst.
            • Positive news may increase confidence when relevant and credible.
            • Portfolio risk is acceptable.
            • No conflicting open position exists.

            =======================================================
            SELL RULES
            =======================================================

            Recommend SELL only if:

            • Engineering recommends SELL.
            • Technical confidence is sufficiently strong.
            • There is no material bullish news catalyst invalidating the setup.
            • Relevant negative news may increase confidence when credible.
            • Portfolio exposure allows selling.
            • No conflicting position exists.

            =======================================================
            HOLD RULES
            =======================================================

            Recommend HOLD if:

            • Technical confidence is weak.
            • Evidence is conflicting.
            • Market uncertainty is high.
            • News contradicts technical analysis.
            • News information is unavailable or insufficient.
            • News is stale or unreliable.
            • Risk is unacceptable.

            =======================================================
            EXIT RULES
            =======================================================

            If an existing position should be closed because of:

            • Stop loss
            • Target reached
            • Strong reversal
            • Material adverse news
            • Risk condition

            recommend EXIT.

            =======================================================
            RISK RULES
            =======================================================

            Capital preservation has the highest priority.

            Never recommend a trade solely because of a positive news
            headline.

            Never recommend a trade solely because of a negative news
            headline.

            Consider:

            • Stop loss
            • Target
            • Risk/reward
            • Existing exposure
            • Position size
            • Market volatility
            • News risk
            • Technical confidence

            =======================================================
            CONFIDENCE RULES
            =======================================================

            confidence MUST be an integer between 0 and 100.

            Use:

            0-20   = Very weak evidence
            21-40  = Weak evidence
            41-59  = Mixed / Neutral evidence
            60-79  = Strong evidence
            80-100 = Very strong evidence

            Do not return confidence 0 unless the available evidence is
            genuinely insufficient to make a meaningful assessment.

            decisionStrength should be one of:

            VERY_LOW
            LOW
            MEDIUM
            HIGH
            VERY_HIGH

            =======================================================
            EXECUTION RULES
            =======================================================

            For BUY:

            • Provide a realistic entry.
            • Provide a protective stop loss.
            • Provide a realistic intraday target.
            • Calculate a sensible position size.
            • Holding period should normally be Intraday.

            For SELL:

            • Provide appropriate entry.
            • Provide stop loss.
            • Provide target.
            • Provide position size.
            • Holding period should normally be Intraday.

            For HOLD:

            • tradeAllowed should normally be false.
            • Execution values may be null.

            For EXIT:

            • tradeAllowed should normally be true.
            • Provide an appropriate exit execution plan.

            =======================================================
            STRICT OUTPUT FORMAT
            =======================================================

            Return ONLY valid JSON.

            Do NOT return:

            • Markdown
            • ```json
            • ``` 
            • Explanations outside JSON
            • Additional fields
            • Comments

            The JSON MUST exactly follow this structure:

            {
              "decision": {
                "tradeAllowed": true,
                "recommendation": "BUY",
                "confidence": 82,
                "decisionStrength": "HIGH",
                "reason": "Technical analysis strongly supports BUY and recent relevant news provides a positive catalyst."
              },

              "technicalAnalysis": {
                "summary": "Technical indicators are strongly bullish.",
                "signal": "BUY",
                "rsi": {
                  "value": 58.4,
                  "interpretation": "Positive momentum without overbought conditions."
                },
                "ema": {
                  "ema20": 3528.2,
                  "ema50": 3495.6,
                  "trend": "Bullish"
                },
                "macd": {
                  "value": 18.75,
                  "signalLine": 15.2,
                  "interpretation": "MACD is above the signal line."
                },
                "volume": {
                  "current": 1850000,
                  "average": 1500000,
                  "interpretation": "Current volume is above average."
                }
              },

              "riskAnalysis": {
                "riskLevel": "Low",
                "riskRewardRatio": "1:1.79",
                "stopLossRequired": true,
                "risks": [
                  "Normal intraday market volatility",
                  "Unexpected negative news"
                ]
              },

              "newsAnalysis": {
                "sentiment": "Positive",
                "score": 80,
                "summary": "Recent company-specific news provides a positive catalyst."
              },

              "portfolioAnalysis": {
                "currentExposure": "0",
                "availableCapital": "100000",
                "recommendation": "New position is acceptable."
              },

              "executionPlan": {
                "entry": 3542.50,
                "stopLoss": 3510.00,
                "target": 3600.00,
                "positionSize": 25,
                "holdingPeriod": "Intraday",
                "exitStrategy": "Target or Stop Loss"
              },

              "aiReasoning": "Technical analysis, news, risk and portfolio context collectively support the final decision."
            }

            IMPORTANT:

            • recommendation MUST be one of the supported AiRecommendation values.
            • signal MUST be one of the supported SignalType values.
            • confidence MUST be an integer from 0 to 100.
            • newsAnalysis.score MUST be an integer from 0 to 100.
            • Do not invent unavailable market or portfolio values.
            • Use null where a value genuinely cannot be determined.
            • Do not change the JSON field names.
            • Do not add extra JSON fields.
            """.formatted(
                signalRequestSection,
                technicalDecisionSection,
                sectorSummary,
                newsSection,
                portfolioSection,
                openPositionSection
        );
    }

    private String buildNewsSection(List<NewsArticle> news) {

        if (news == null || news.isEmpty()) {
            return "No recent market news available.";
        }

        StringBuilder builder = new StringBuilder();

        news.stream()
                .limit(5)
                .forEach(article -> {

                    builder.append("\nTitle: ")
                            .append(article.getTitle());

                    builder.append("\nDescription: ")
                            .append(article.getDescription());

                    builder.append("\nPublished At: ")
                            .append(article.getPublishedAt());

                    builder.append("\nSource: ")
                            .append(article.getSource());

                    builder.append("\n");
                });

        return builder.toString();
    }

}