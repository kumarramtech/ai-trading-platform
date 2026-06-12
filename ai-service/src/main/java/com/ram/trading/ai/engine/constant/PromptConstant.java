package com.ram.trading.ai.engine.constant;

public interface PromptConstant {

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
}
