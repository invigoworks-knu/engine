package com.coinreaders.engine.domain.repository;

import com.coinreaders.engine.domain.entity.BacktestResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BacktestResultRepository extends JpaRepository<BacktestResult, Long> {

}