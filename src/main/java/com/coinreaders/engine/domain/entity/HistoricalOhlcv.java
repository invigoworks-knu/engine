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

    private HistoricalOhlcv(String market, LocalDateTime candleDateTimeKst, BigDecimal openingPrice, BigDecimal highPrice, BigDecimal lowPrice, BigDecimal tradePrice, BigDecimal candleAccTradeVolume) {
        this.market = market;
        this.candleDateTimeKst = candleDateTimeKst;
        this.openingPrice = openingPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.tradePrice = tradePrice;
        this.candleAccTradeVolume = candleAccTradeVolume;
    }

    // 엔티티 생성을 위한 public 정적 팩토리 메서드 추가
    public static HistoricalOhlcv of(String market, LocalDateTime candleDateTimeKst, BigDecimal openingPrice, BigDecimal highPrice, BigDecimal lowPrice, BigDecimal tradePrice, BigDecimal candleAccTradeVolume) {
        return new HistoricalOhlcv(market, candleDateTimeKst, openingPrice, highPrice, lowPrice, tradePrice, candleAccTradeVolume);
    }
}