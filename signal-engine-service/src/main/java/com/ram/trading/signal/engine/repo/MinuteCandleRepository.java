package com.ram.trading.signal.engine.repo;

import com.ram.trading.signal.engine.entity.MinuteCandleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MinuteCandleRepository extends JpaRepository<MinuteCandleEntity, Long> {

    List<MinuteCandleEntity> findByMinuteGreaterThanEqualAndMinuteLessThan(
            LocalDateTime from, LocalDateTime to);

    Optional<MinuteCandleEntity> findBySymbolAndMinute(String symbol, LocalDateTime minute);
}
