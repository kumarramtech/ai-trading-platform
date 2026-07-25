package com.ram.trading.ai.engine.cache;

import java.time.Duration;

public interface RedisCacheService {

    <T> T get(String key, Class<T> clazz);

    void put(String key, Object value, Duration ttl);

    void evict(String key);

    boolean exists(String key);

}