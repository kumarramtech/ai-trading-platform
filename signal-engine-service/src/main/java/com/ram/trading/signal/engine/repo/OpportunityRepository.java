package com.ram.trading.signal.engine.repo;

import com.ram.trading.signal.engine.entity.Opportunity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OpportunityRepository
        extends JpaRepository<Opportunity, Long> {

    List<Opportunity> findAllByOrderByCreatedAtDesc();

    List<Opportunity> findBySelectedTrue();

    @Modifying
    @Query("update Opportunity o set o.selected=false")
    void resetSelections();

    Optional<Opportunity> findBySignalId(Long signalId);

}