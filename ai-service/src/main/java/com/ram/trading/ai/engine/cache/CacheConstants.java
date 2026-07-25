package com.ram.trading.ai.engine.cache;

import java.time.Duration;

public final class CacheConstants {

    private CacheConstants(){}

    public static final String AI_DECISION = "AI_DECISION";

    public static final Duration AI_DECISION_TTL = Duration.ofMinutes(5);

    public static final Duration NEWS_TTL = Duration.ofMinutes(10);

    public static final Duration PORTFOLIO_TTL = Duration.ofMinutes(1);

}