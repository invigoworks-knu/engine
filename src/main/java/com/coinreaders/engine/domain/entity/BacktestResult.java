package com.coinreaders.engine.domain.entity;

import com.coinreaders.engine.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "backtest_result")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BacktestResult extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "result_id")
    private Long id;

    @Column(nullable = false, length = 32)
    private String strategyCode;

    @Column(nullable = false, length = 32)
    private String strategyVersion;

    @Column(nullable = false, length = 32)
    private String aiModelVersion;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal initialCapital;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal finalBalance;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal totalProfitPercent;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal maxDrawdown;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal winRate;

    @Column(nullable = false)
    private Integer tradeCount;

}