package com.coinreaders.engine.domain.entity;

import com.coinreaders.engine.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * AI 예측 데이터 엔티티 (백테스팅용)
 * CSV 파일의 전체 필드를 포함하여 DB에 저장
 */
@Entity
@Table(name = "historical_ai_predictions", uniqueConstraints = {
    @UniqueConstraint(name = "uk_market_date_fold", columnNames = {"market", "predictionDate", "foldNumber"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HistoricalAiPrediction extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "prediction_id")
    private Long id;

    @Column(nullable = false, length = 32)
    private String market;

    @Column(nullable = false)
    private LocalDate predictionDate;

    @Column(nullable = false)
    private Integer foldNumber;

    // 실제 결과
    @Column(nullable = false)
    private Integer actualDirection; // 0: DOWN, 1: UP

    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal actualReturn; // 실제 수익률

    // AI 예측
    @Column(nullable = false)
    private Integer predDirection; // 0: DOWN, 1: UP

    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal predProbaUp; // 상승 확률 (0~1)

    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal predProbaDown; // 하락 확률 (0~1)

    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal maxProba; // max(pred_proba_up, pred_proba_down)

    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal confidence; // abs(pred_proba_up - 0.5)

    @Column(nullable = false)
    private Integer correct; // 예측 정확도 (0: 틀림, 1: 맞음)

    private HistoricalAiPrediction(String market, LocalDate predictionDate, Integer foldNumber,
                                   Integer actualDirection, BigDecimal actualReturn,
                                   Integer predDirection, BigDecimal predProbaUp, BigDecimal predProbaDown,
                                   BigDecimal maxProba, BigDecimal confidence, Integer correct) {
        this.market = market;
        this.predictionDate = predictionDate;
        this.foldNumber = foldNumber;
        this.actualDirection = actualDirection;
        this.actualReturn = actualReturn;
        this.predDirection = predDirection;
        this.predProbaUp = predProbaUp;
        this.predProbaDown = predProbaDown;
        this.maxProba = maxProba;
        this.confidence = confidence;
        this.correct = correct;
    }

    public static HistoricalAiPrediction of(String market, LocalDate predictionDate, Integer foldNumber,
                                            Integer actualDirection, BigDecimal actualReturn,
                                            Integer predDirection, BigDecimal predProbaUp, BigDecimal predProbaDown,
                                            BigDecimal maxProba, BigDecimal confidence, Integer correct) {
        return new HistoricalAiPrediction(market, predictionDate, foldNumber, actualDirection, actualReturn,
            predDirection, predProbaUp, predProbaDown, maxProba, confidence, correct);
    }
}