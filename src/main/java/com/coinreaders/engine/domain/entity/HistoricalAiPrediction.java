package com.coinreaders.engine.domain.entity;

import com.coinreaders.engine.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "historical_ai_predictions", uniqueConstraints = {
    @UniqueConstraint(name = "uk_market_time_model", columnNames = {"market", "candleDateTimeKst", "aiModelVersion"})
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
    private LocalDateTime candleDateTimeKst;

    @Column(nullable = false, length = 32)
    private String aiModelVersion;

    @Column(nullable = false, length = 16)
    private String predictedDirection; // "UP", "DOWN", "HOLD"

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal predictedProbability;

    @Column(precision = 10, scale = 4)
    private BigDecimal predictedChangePercent;

}