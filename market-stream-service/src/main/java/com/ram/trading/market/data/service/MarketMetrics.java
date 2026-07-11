package com.ram.trading.market.data.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MarketMetrics {

    private final AtomicLong ticksProcessed = new AtomicLong();

    private final AtomicLong reconnectCount = new AtomicLong();

    private final AtomicLong subscriptionCount = new AtomicLong();

    private volatile long lastTickTimestamp;

    private final long startupTime = System.currentTimeMillis();

    public void incrementTick() {
        ticksProcessed.incrementAndGet();
    }

    public void incrementReconnect() {
        reconnectCount.incrementAndGet();
    }

    public void incrementSubscription() {
        subscriptionCount.incrementAndGet();
    }

    public long getTicksProcessed() {
        return ticksProcessed.get();
    }

    public long getReconnectCount() {
        return reconnectCount.get();
    }

    public long getSubscriptionCount() {
        return subscriptionCount.get();
    }

    public long getUptime() {
        return System.currentTimeMillis() - startupTime;
    }

    public void updateLastTick() {
        lastTickTimestamp = System.currentTimeMillis();
    }

    public String getLastTickTime() {

        if (lastTickTimestamp == 0) {
            return "N/A";
        }

        return LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(lastTickTimestamp),
                        ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    public String getFormattedUptime() {

        long uptime = getUptime();
        long hours = uptime / (1000 * 60 * 60);
        long minutes = (uptime % (1000 * 60 * 60)) / (1000 * 60);
        long seconds = (uptime % (1000 * 60)) / 1000;
        return String.format("%02d:%02d:%02d",
                hours,
                minutes,
                seconds);
    }
}