package com.ram.trading.auth.service.cache;

public final class BrokerRedisKeys {

    private BrokerRedisKeys() {
    }

    public static String accessToken(String broker) {
        return "broker:access-token:" + broker;
    }
}