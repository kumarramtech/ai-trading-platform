package com.ram.trading.ai.engine.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ram.trading.ai.engine.dto.NewsArticle;
import com.ram.trading.ai.engine.dto.TradingDecisionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AiDecisionPromptBuilder {

    private final ObjectMapper objectMapper;

    public String buildPrompt(TradingDecisionRequest request) {

        String newsSection =
                buildNewsSection(request.getNews());

        String portfolioSection =
                toJson(
                        request.getPortfolioContext(),
                        "No portfolio context available."
                );

        String openPositionSection =
                toJson(
                        request.getOpenPositionContext(),
                        "No open position context available."
                );

        /*
         * IMPORTANT:
         * Never use object.toString() here.
         *
         * These objects contain the actual Engineering/Trading information
         * that the AI must see.
         */
        String technicalDecisionSection =
                toJson(
                        request.getTechnicalDecision(),
                        "No technical decision available."
                );

        String signalRequestSection =
                toJson(
                        request.getSignalRequest(),
                        "No signal request available."
                );

        String sectorSummary =
                request.getSectorSummary() != null
                        ? request.getSectorSummary()
                        : "No sector summary available.";

        return """
            You are the final AI validation engine for an
            intraday trading system.

            Your primary responsibility is to validate the
            Engineering / Technical Decision using contextual
            information.

            =======================================================
            CORE DECISION PRINCIPLE
            =======================================================

            The Engineering Decision is the PRIMARY trading
            recommendation.

            Engineering has already evaluated the technical
            indicators, trend, momentum and trading setup.

            Therefore:

            - Do NOT independently replace the technical strategy.
            - Do NOT invent technical indicators.
            - Do NOT invent prices.
            - Do NOT convert BUY to SELL or SELL to BUY without
              explicit supplied evidence.
            - Treat BUY and SELL symmetrically.
            - Missing optional data is NOT evidence for changing
              the Engineering direction.

            If Engineering recommends BUY or SELL, first validate
            that direction using the supplied evidence.

            =======================================================
            DIRECTION INTEGRITY RULE
            =======================================================

            Engineering BUY:
              - AI may CONFIRM BUY.
              - AI may reject it and return HOLD.
              - AI MUST NOT convert BUY directly into SELL.

            Engineering SELL:
              - AI may CONFIRM SELL.
              - AI may reject it and return HOLD.
              - AI MUST NOT convert SELL directly into BUY.

            The AI is a validation/filter layer.
            It is NOT an independent strategy generator.

            =======================================================
            DECISION PRIORITY
            =======================================================

            Follow this order:

            1. Explicit Risk Constraint
            2. Conflicting Open Position
            3. Explicit Portfolio Constraint
            4. Market-Regime / Strategy Alignment
            5. Material Contradictory News
            6. Original JUDAS/ORB Direction
            7. Engineering Decision
            8. Optional Sector Context

            =======================================================
            SIGNAL REQUEST
            =======================================================

            The following is the COMPLETE structured Signal Request
            supplied by the trading engine.

            %s

            =======================================================
            ORIGINAL ENTRY STRATEGY CONTEXT
            =======================================================

            The Signal Request contains the original strategy setup.

            Expected fields include:

            - setupStrategy = JUDAS or ORB
            - setupDirection = BUY or SELL

            IMPORTANT:

            setupDirection represents the original direction generated
            by the validated JUDAS or ORB strategy.

            Treat it as important contextual evidence.

            If setupDirection and Engineering direction MATCH:

            - This is directional alignment.
            - Consider the evidence mutually supportive.

            If setupDirection and Engineering direction DIFFER:

            - Recognize the directional conflict.
            - Do NOT automatically change Engineering direction.
            - Do NOT automatically approve Engineering direction.
            - Evaluate the supplied technical evidence.
            - If the supplied evidence does not sufficiently justify
              the difference, prefer HOLD.

            Never invent a reason for the disagreement.

            =======================================================
            ENGINEERING / TECHNICAL DECISION
            =======================================================

            The following is the COMPLETE structured Engineering
            Decision supplied by the trading engine.

            %s

            IMPORTANT:

            Use the actual supplied:

            - signal
            - confidence
            - confidenceLevel
            - reasons

            Do NOT assume missing values.

            Do NOT recalculate the technical signal.

            =======================================================
            SECTOR CONTEXT
            =======================================================

            %s

            =======================================================
            NEWS CONTEXT
            =======================================================

            %s

            =======================================================
            NEWS RULES
            =======================================================

            Analyze only the news explicitly supplied.

            News can override Engineering ONLY when the supplied
            news provides clear, material and relevant evidence.

            Examples:

            - Major regulatory action
            - Serious legal action
            - Major fraud or governance issue
            - Significant earnings surprise
            - Major contract cancellation
            - Major merger or acquisition event
            - Severe company-specific adverse event
            - Other clearly material event invalidating the setup

            If news is:

            - unavailable
            - empty
            - insufficient
            - neutral
            - stale
            - unrelated
            - unreliable

            treat it as NEUTRAL.

            Neutral news MUST NOT change BUY to HOLD.

            Neutral news MUST NOT change SELL to HOLD.

            Neutral or missing news MUST NOT convert BUY to SELL
            or SELL to BUY.

            =======================================================
            PORTFOLIO CONTEXT
            =======================================================

            %s

            =======================================================
            OPEN POSITION CONTEXT
            =======================================================

            %s

            =======================================================
            MARKET REGIME / STRATEGY CONFIRMATION RULE
            =======================================================

            Market regime is a real validation input, not merely
            optional commentary. Use the supplied marketContext.marketTrend
            together with NIFTY and BANKNIFTY changes.

            Never reverse Engineering direction:
            - BULLISH market + Engineering SELL must NOT become BUY.
            - BEARISH market + Engineering BUY must NOT become SELL.
            If the original direction is not sufficiently supported, return HOLD.

            Apply strategy-aware rules:

            1. ORB:
               - BULLISH regime supports BUY; reject an ORB SELL as counter-trend.
               - BEARISH regime supports SELL; reject an ORB BUY as counter-trend.
               - SIDEWAYS regime: return HOLD for ORB.
               - Missing/unknown regime: return HOLD for ORB.

            2. JUDAS:
               - JUDAS is a sweep/reversal strategy, so a counter-trend trade is
                 not automatically invalid.
               - A counter-trend JUDAS may be approved ONLY when the supplied
                 setupDirection and Engineering signal agree, the supplied
                 technical evidence clearly supports the reversal, and no
                 risk, portfolio, open-position, or material-news constraint
                 invalidates it.
               - If the evidence is mixed, weak, contradictory, or incomplete,
                 return HOLD. Do not approve a counter-trend JUDAS merely because
                 tradeAllowed is requested or because a sweep is mentioned.
               - SIDEWAYS regime: require especially clear setup-specific
                 technical confirmation; otherwise return HOLD.
               - Missing/unknown regime: return HOLD for JUDAS.

            3. Other existing strategies (including MIDDAY):
               - When regime is available, reject clear counter-trend entries
                 (BULLISH + SELL or BEARISH + BUY) and reject entries in a
                 SIDEWAYS regime.
               - Missing optional regime data alone must not break existing
                 non-JUDAS/ORB workflows; continue to apply their existing
                 technical, risk, and portfolio safeguards.

            Market regime must never override explicit risk controls or
            authorize a trade that Engineering did not recommend.

            =======================================================
            WHEN TO CONFIRM BUY
            =======================================================

            Confirm BUY when:

            - Engineering recommendation is BUY.
            - There is no explicit unacceptable risk.
            - There is no conflicting open position.
            - There is no explicit portfolio restriction.
            - There is no material bearish news invalidating the setup.
            - Original JUDAS/ORB direction does not create an
              unresolved material conflict.
            - Market-regime / strategy rules above are satisfied.

            If these conditions are satisfied:

            recommendation = BUY
            tradeAllowed = true

            =======================================================
            WHEN TO CONFIRM SELL
            =======================================================

            Confirm SELL when:

            - Engineering recommendation is SELL.
            - There is no explicit unacceptable risk.
            - There is no conflicting open position.
            - There is no explicit portfolio restriction.
            - There is no material bullish news invalidating the setup.
            - Original JUDAS/ORB direction does not create an
              unresolved material conflict.
            - Market-regime / strategy rules above are satisfied.

            If these conditions are satisfied:

            recommendation = SELL
            tradeAllowed = true

            =======================================================
            WHEN TO RECOMMEND HOLD
            =======================================================

            Recommend HOLD when:

            - Engineering recommendation is HOLD.
            - Explicit risk constraint blocks the trade.
            - Conflicting open position blocks the trade.
            - Explicit portfolio constraint blocks the trade.
            - Material supplied news invalidates the setup.
            - Strong explicit contradictory evidence exists.
            - Original JUDAS/ORB direction conflicts with Engineering
              and supplied evidence does not sufficiently justify
              Engineering direction.

            Do NOT recommend HOLD merely because:

            - news is unavailable
            - news is neutral
            - optional context is missing, except where the
              JUDAS/ORB market-regime rule above explicitly requires HOLD
            - there is general uncertainty

            =======================================================
            CONFIDENCE RULES
            =======================================================

            Confidence must be an integer between 0 and 100.

            When confirming Engineering BUY or SELL:

            - Keep confidence reasonably aligned with Engineering
              confidence.
            - Do not arbitrarily increase confidence.
            - Do not arbitrarily reduce confidence because optional
              information is unavailable.

            When Engineering and JUDAS/ORB directions conflict,
            confidence must reflect the quality of the evidence.

            =======================================================
            FINAL DECISION CONSISTENCY
            =======================================================

            If recommendation = BUY:

            tradeAllowed MUST be true.

            If recommendation = SELL:

            tradeAllowed MUST be true.

            If recommendation = HOLD:

            tradeAllowed MUST be false.

            Never return:

            recommendation = HOLD
            tradeAllowed = true

            =======================================================
            PRICES AND POSITION SIZE
            =======================================================

            You decide DIRECTION only: BUY, SELL or HOLD.

            Do NOT return entry, target, stopLoss or positionSize.
            The trading engine calculates all prices and the
            Risk/Position Sizing service decides the quantity.

            =======================================================
            RESPONSE FORMAT
            =======================================================

            Return ONLY valid JSON.

            Do not return Markdown.

            Do not return explanations outside JSON.

            Return exactly this structure:

            {
              "decision": {
                "tradeAllowed": true,
                "recommendation": "BUY",
                "confidence": 75,
                "decisionStrength": "HIGH",
                "reason": "Engineering BUY confirmed because no explicit material contradictory evidence was found."
              },
              "aiReasoning": "Engineering decision validated using supplied technical, strategy, risk and contextual information."
            }

            =======================================================
            FINAL INSTRUCTION
            =======================================================

            The default action is to validate the Engineering Decision.

            Do not invent evidence.

            Do not invent market information.

            Do not invent news.

            Do not independently create a new trading strategy.

            BUY and SELL must be evaluated symmetrically.

            If Engineering BUY is valid, confirm BUY.

            If Engineering SELL is valid, confirm SELL.

            If Engineering cannot be safely validated because of
            explicit contradictory evidence, return HOLD.

            HOLD MUST always have tradeAllowed=false.

            =======================================================
            STRUCTURED INPUTS USED BY THIS DECISION
            =======================================================

            SIGNAL_REQUEST:
            %s

            ENGINEERING_DECISION:
            %s

            """.formatted(
                signalRequestSection,
                technicalDecisionSection,
                sectorSummary,
                newsSection,
                portfolioSection,
                openPositionSection,
                signalRequestSection,
                technicalDecisionSection
        );
    }

    /**
     * Serialize DTOs as real JSON.
     *
     * IMPORTANT:
     * Never use Object.toString() for AI prompt data.
     */
    private String toJson(
            Object object,
            String fallback) {

        if (object == null) {
            return fallback;
        }

        try {
            return objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(object);

        } catch (Exception ex) {

            return """
                    DATA_SERIALIZATION_ERROR

                    The supplied context could not be serialized.

                    Do NOT infer or invent missing values.
                    Treat this context as unavailable.
                    """;
        }
    }

    private String buildNewsSection(
            List<NewsArticle> news) {

        if (news == null || news.isEmpty()) {

            return """
                NEWS STATUS: UNAVAILABLE

                No relevant recent news was supplied.

                Treat this as NEUTRAL context.

                This MUST NOT convert BUY to SELL.
                This MUST NOT convert SELL to BUY.
                """;
        }

        StringBuilder builder =
                new StringBuilder();

        builder.append("NEWS STATUS: AVAILABLE\n");

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

                    builder.append(
                            "\n-------------------------\n");
                });

        return builder.toString();
    }
}