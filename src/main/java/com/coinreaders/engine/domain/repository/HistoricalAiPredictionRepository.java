package com.coinreaders.engine.domain.repository;

import com.coinreaders.engine.domain.entity.HistoricalAiPrediction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HistoricalAiPredictionRepository extends JpaRepository<HistoricalAiPrediction, Long> {
    void deleteByAiModelVersion(String aiModelVersion);
}