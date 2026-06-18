package com.ram.trading.signal.engine.repo;

import com.ram.trading.signal.engine.entity.Opportunity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OpportunityRepository
        extends JpaRepository<Opportunity, Long> {

    List<Opportunity> findAllByOrderByCreatedAtDesc();

    List<Opportunity> findBySelectedTrue();

    List<Opportunity>
    findTop3ByOrderByConfidenceDesc();

}