package com.ram.trading.auth.service.service;

import com.ram.trading.auth.service.entity.BrokerSession;
import com.ram.trading.auth.service.exception.BrokerSessionNotFoundException;
import com.ram.trading.auth.service.repo.BrokerSessionRepository;
import com.ram.trading.auth.service.upstox.UpstoxTokenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
public class BrokerSessionServiceImpl implements BrokerSessionService {

    private final BrokerSessionRepository repository;

    @Override
    @Transactional
    public void saveBrokerSession(
            String broker,
            UpstoxTokenResponse token) {

        BrokerSession session =
                repository.findByBroker(broker)
                        .orElse(new BrokerSession());

        session.setBroker(broker);

        session.setAccessToken(token.getAccessToken());

        session.setRefreshToken(token.getRefreshToken());
        session.setTokenType(token.getTokenType());

        if (token.getExpiresIn() != null) {
            session.setExpiresAt(
                    LocalDateTime.now()
                            .plusSeconds(token.getExpiresIn()));
        }

        session.setUpdatedAt(LocalDateTime.now());

        if (session.getCreatedAt() == null) {
            session.setCreatedAt(LocalDateTime.now());
        }

        repository.save(session);
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

        return repository.findByBroker(broker)
                .orElseThrow(() ->
                        new BrokerSessionNotFoundException(broker))
                .getAccessToken();
    }
}