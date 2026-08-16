package com.ram.trading.auth.service.cache;

import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrokerRedisCacheServiceImpl implements BrokerRedisCacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public void put(String key, String value, Duration ttl) {

        redisTemplate.opsForValue()
                .set(key, value, ttl);

        log.info("[REDIS] Broker token cached successfully. key={}, ttl={}",
                key, ttl);
    }

    @Override
    public String get(String key) {

        Object value = redisTemplate.opsForValue().get(key);

        if (value == null) {
            log.info("[REDIS] Cache MISS : {}", key);
            return null;
        }

        log.info("[REDIS] Cache HIT : {}", key);

        return value.toString();
    }

    @Override
    public void evict(String key) {

        redisTemplate.delete(key);

        log.info("[REDIS] Broker token removed. key={}", key);
    }
}