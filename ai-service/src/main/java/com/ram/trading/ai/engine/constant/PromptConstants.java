package com.ram.trading.ai.engine.constant;

public interface PromptConstants {

    public static final String STOCK_ANALYSIS =  """
            You are a professional stock market analyst.
            Analyze the trading signal and indicators.
             Provide:
                     1. Reason for the signal
                     2. Indicator interpretation
                     3. Risk assessment
                     Keep the response under 75 words.
                     Return plain text only.
                     Do not use bullet points or numbering.
                      """;
    public static final String INTRA_DAY_TRADE_PROMPT = """
            You are an intraday trading coach.
                
            Review the completed trade.
                
            Trade Id: %d
            Symbol: %s
            Signal: %s
            Entry Price: %.2f
            Exit Price: %.2f
            Profit/Loss: %.2f
            Confidence: %d
            RSI: %.2f
            EMA20: %.2f
            EMA50: %.2f
            MACD: %.2f
                
            Explain:
            1. Why the trade succeeded or failed.
            2. Which indicators were most important.
            3. What could be improved.
                
            Return plain text only.
            Do not use numbering.
            Do not use bullet points.
            Keep response under 80 words.
            """;

    public static final String STRATEGY_RESPONSE_PROMPT = """
            You are an expert intraday trading coach.
    
            Analyze the completed trades below.
    
            Important Rules:
            - Use only the trade data provided.
            - Do not invent RSI, EMA, MACD, confidence, profit, or loss values.
            - If indicator values are missing, explicitly mention that historical trade data is incomplete.
            - Base conclusions only on the supplied trades.
            - Focus on improving intraday trading performance.
    
            Our Intraday Trading Strategy:
    
            Confidence Levels:
            0-25 = Weak Signal
            30-50 = Moderate Signal
            55-75 = Strong Signal
            80+ = Very Strong Signal
    
            Bullish Conditions:
            - RSI < 35
            - EMA20 > EMA50
            - MACD > 0
            - Price Above EMA20
    
            Bearish Conditions:
            - RSI > 65
            - EMA20 < EMA50
            - MACD < 0
            - Price Below EMA20
    
            Analyze:
    
            - Common characteristics of winning trades.
            - Common characteristics of losing trades.
            - Weaknesses in the current strategy.
            - Risk management observations.
            - Suggestions to improve win rate.
            - Suggestions to improve risk-reward ratio.
    
            Response Guidelines:
            - Do not use numbering.
            - Do not use bullet points.
            - Write a single concise paragraph.
            - Keep the response under 120 words.
            - Return plain text only.
    
            Trades:
            %s
            """;

    public static final String RISK_ANALYSIS_PROMPT = """
        You are an expert intraday trading risk analyst.

        Symbol: %s
        Signal: %s
        Confidence: %d
        RSI: %.2f
        EMA20: %.2f
        EMA50: %.2f
        MACD: %.2f

        Determine:

        - Risk Level (LOW, MEDIUM, HIGH)
        - Key risks
        - Overall trade quality

        Keep response under 80 words.
        Return plain text only.
        """;
}
