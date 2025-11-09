package com.coinreaders.engine.domain.entity;

import com.coinreaders.engine.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "backtest_trade")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class BacktestTrade extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "trade_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "result_id", nullable = false)
    private BacktestResult backtestResult;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal entryPrice;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal exitPrice;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal returnPct;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal profit;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal capitalAfterTrade;

    @Column(nullable = false)
    private Integer predDirection; // 0 or 1

    @Column(nullable = false, precision = 10, scale = 8)
    private BigDecimal confidence;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal positionSize;

    public void setBacktestResult(BacktestResult backtestResult) {
        this.backtestResult = backtestResult;
    }
}
