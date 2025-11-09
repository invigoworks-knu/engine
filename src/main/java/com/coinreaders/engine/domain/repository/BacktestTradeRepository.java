package com.coinreaders.engine.domain.repository;

import com.coinreaders.engine.domain.entity.BacktestTrade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BacktestTradeRepository extends JpaRepository<BacktestTrade, Long> {

    List<BacktestTrade> findByBacktestResultId(Long backtestResultId);
}
