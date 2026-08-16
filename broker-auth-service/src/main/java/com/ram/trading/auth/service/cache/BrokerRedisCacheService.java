package com.ram.trading.auth.service.cache;

import java.time.Duration;

public interface BrokerRedisCacheService {

    void put(String key, String value, Duration ttl);

    String get(String key);

    void evict(String key);
}