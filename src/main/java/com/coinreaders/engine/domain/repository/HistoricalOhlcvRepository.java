package com.coinreaders.engine.domain.repository;

import com.coinreaders.engine.domain.entity.HistoricalOhlcv;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HistoricalOhlcvRepository extends JpaRepository<HistoricalOhlcv, Long> {

}