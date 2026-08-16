package com.ram.trading.auth.service.service;

import com.ram.trading.auth.service.cache.BrokerRedisCacheService;
import com.ram.trading.auth.service.cache.BrokerRedisKeys;
import com.ram.trading.auth.service.entity.BrokerSession;
import com.ram.trading.auth.service.exception.BrokerSessionNotFoundException;
import com.ram.trading.auth.service.repo.BrokerSessionRepository;
import com.ram.trading.auth.service.upstox.UpstoxTokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class BrokerSessionServiceImpl implements BrokerSessionService {

    private final BrokerSessionRepository repository;

    private final BrokerRedisCacheService redisCacheService;

    @Override
    @Transactional
    public void saveBrokerSession(
            String broker,
            UpstoxTokenResponse token) {

        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));

        BrokerSession session =
                repository.findByBroker(broker)
                        .orElse(new BrokerSession());

        session.setBroker(broker);
        session.setAccessToken(token.getAccessToken());

        if (token.getRefreshToken() != null) {
            session.setRefreshToken(token.getRefreshToken());
        }

        if (token.getTokenType() != null) {
            session.setTokenType(token.getTokenType());
        }

        LocalDateTime expiresAt;

        if (token.getExpiresIn() != null
                && token.getExpiresIn() > 0) {

            expiresAt = now.plusSeconds(token.getExpiresIn());

        } else {

            expiresAt = calculateUpstoxTokenExpiry();
        }

        session.setExpiresAt(expiresAt);
        session.setUpdatedAt(now);

        if (session.getCreatedAt() == null) {
            session.setCreatedAt(now);
        }

        repository.save(session);

        // Cache access token in Redis until the actual Upstox expiry time
        if (token.getAccessToken() != null
                && !token.getAccessToken().isBlank()) {

            Duration ttl = Duration.between(
                    now,
                    expiresAt);

            if (!ttl.isNegative() && !ttl.isZero()) {

                redisCacheService.put(
                        BrokerRedisKeys.accessToken(broker),
                        token.getAccessToken(),
                        ttl);

                log.info(
                        "Upstox access token cached in Redis. broker={}, expiresAt={}, ttlSeconds={}",
                        broker,
                        expiresAt,
                        ttl.getSeconds());
            }
        }
    }

    private LocalDateTime calculateUpstoxTokenExpiry() {

        ZoneId zoneId = ZoneId.of("Asia/Kolkata");

        ZonedDateTime now =
                ZonedDateTime.now(zoneId);

        ZonedDateTime expiry =
                now.withHour(3)
                        .withMinute(30)
                        .withSecond(0)
                        .withNano(0);

        if (!expiry.isAfter(now)) {
            expiry = expiry.plusDays(1);
        }

        return expiry.toLocalDateTime();
    }

    @Override
    @Transactional(readOnly = true)
    public BrokerSession getBrokerSession(String broker) {

        return repository.findByBroker(broker)
                .orElseThrow(() ->
                        new BrokerSessionNotFoundException(broker));
    }

    @Override
    @Transactional(readOnly = true)
    public String getAccessToken(String broker) {

        String key = BrokerRedisKeys.accessToken(broker);

        String cachedToken = redisCacheService.get(key);

        if (cachedToken != null && !cachedToken.isBlank()) {
            return cachedToken;
        }

        BrokerSession session = repository.findByBroker(broker)
                .orElseThrow(() ->
                        new BrokerSessionNotFoundException(broker));

        if (session.getExpiresAt() != null
                && !session.getExpiresAt().isAfter(LocalDateTime.now())) {

            throw new BrokerSessionNotFoundException(
                    "Broker token expired: " + broker);
        }

        if (session.getExpiresAt() != null) {

            Duration ttl = Duration.between(
                    LocalDateTime.now(),
                    session.getExpiresAt());

            if (!ttl.isNegative() && !ttl.isZero()) {
                redisCacheService.put(
                        key,
                        session.getAccessToken(),
                        ttl);
            }
        }

        return session.getAccessToken();
    }
}