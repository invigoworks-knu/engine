package com.coinreaders.engine.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "historical_ohlcv", uniqueConstraints = {
    @UniqueConstraint(name = "uk_market_time", columnNames = {"market", "candleDateTimeKst"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HistoricalOhlcv {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String market;

    @Column(nullable = false)
    private LocalDateTime candleDateTimeKst;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal openingPrice;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal highPrice;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal lowPrice;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal tradePrice;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal candleAccTradeVolume;
}