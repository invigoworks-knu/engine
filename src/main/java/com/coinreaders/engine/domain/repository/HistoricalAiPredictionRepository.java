package com.coinreaders.engine.domain.repository;

import com.coinreaders.engine.domain.entity.HistoricalAiPrediction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HistoricalAiPredictionRepository extends JpaRepository<HistoricalAiPrediction, Long> {

    /**
     * 특정 시장의 특정 Fold 데이터를 조회합니다.
     * @param market "KRW-ETH"
     * @param foldNumber 1~8
     * @return 예측 데이터 리스트
     */
    List<HistoricalAiPrediction> findByMarketAndFoldNumberOrderByPredictionDateAsc(String market, Integer foldNumber);

    /**
     * 특정 시장의 특정 Fold 데이터를 삭제합니다.
     * @param market "KRW-ETH"
     * @param foldNumber 1~8
     */
    void deleteByMarketAndFoldNumber(String market, Integer foldNumber);

    /**
     * 특정 시장의 특정 Fold, 특정 모델 데이터를 삭제합니다.
     * @param market "KRW-ETH"
     * @param foldNumber 1~8
     * @param modelName "GRU", "LSTM", "XGBoost", etc.
     */
    void deleteByMarketAndFoldNumberAndModelName(String market, Integer foldNumber, String modelName);

    /**
     * 특정 시장의 특정 Fold, 특정 모델 데이터를 조회합니다.
     * @param market "KRW-ETH"
     * @param foldNumber 1~8
     * @param modelName "GRU", "LSTM", "XGBoost", etc.
     * @return 예측 데이터 리스트
     */
    List<HistoricalAiPrediction> findByMarketAndFoldNumberAndModelNameOrderByPredictionDateAsc(
        String market, Integer foldNumber, String modelName);

    /**
     * 특정 시장의 데이터 개수를 조회합니다.
     * @param market "KRW-ETH"
     * @return 데이터 개수
     */
    long countByMarket(String market);

    /**
     * 특정 시장의 특정 모델 데이터 개수를 조회합니다.
     * @param market "KRW-ETH"
     * @param modelName "GRU", "LSTM", "XGBoost", etc.
     * @return 데이터 개수
     */
    long countByMarketAndModelName(String market, String modelName);

    /**
     * 특정 시장의 특정 모델 전체 데이터를 조회합니다 (모든 Fold).
     * @param market "KRW-ETH"
     * @param modelName "GRU", "LSTM", "XGBoost", etc.
     * @return 예측 데이터 리스트
     */
    List<HistoricalAiPrediction> findByMarketAndModelNameOrderByPredictionDateAsc(String market, String modelName);
}