package com.ram.trading.signal.engine.repo;

import com.ram.trading.signal.engine.entity.FadePaperTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface FadePaperTradeRepository extends JpaRepository<FadePaperTrade, Long> {

    List<FadePaperTrade> findByTradeDate(LocalDate day);

    List<FadePaperTrade> findByTradeDateAndStatus(LocalDate day, String status);

    List<FadePaperTrade> findAllByOrderByTradeDateAscIdAsc();
}
