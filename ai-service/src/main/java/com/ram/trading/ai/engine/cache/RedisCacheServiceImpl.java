package com.ram.trading.ai.engine.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisCacheServiceImpl implements RedisCacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public <T> T get(String key, Class<T> clazz) {

        Object value = redisTemplate.opsForValue().get(key);

        if (value == null) {
            log.info("[REDIS] Cache MISS : {}", key);
            return null;
        }

        log.info("[REDIS] Cache HIT : {}", key);

        return clazz.cast(value);
    }

    @Override
    public void put(String key,
                    Object value,
                    Duration ttl) {

        redisTemplate.opsForValue()
                .set(key, value, ttl);

        log.info("[REDIS] Cache Stored : {}", key);
    }

    @Override
    public void evict(String key) {

        redisTemplate.delete(key);

        log.info("[REDIS] Cache Removed : {}", key);
    }

    @Override
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

}