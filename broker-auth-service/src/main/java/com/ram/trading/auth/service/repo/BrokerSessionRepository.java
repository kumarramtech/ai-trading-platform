package com.ram.trading.auth.service.repo;

import com.ram.trading.auth.service.entity.BrokerSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BrokerSessionRepository extends JpaRepository<BrokerSession, Long> {

    Optional<BrokerSession> findByBroker(String broker);

}