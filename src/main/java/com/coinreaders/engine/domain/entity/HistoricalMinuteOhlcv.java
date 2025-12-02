package com.coinreaders.engine.domain.entity;

import com.coinreaders.engine.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 1분봉 OHLCV 데이터 엔티티 (백테스팅용)
 * 업비트 API에서 가져온 과거 1분봉 데이터를 저장
 */
@Entity
@Table(name = "historical_minute_ohlcv",
    indexes = {
        @Index(name = "idx_market_datetime", columnNames = {"market", "candleDateTimeKst"}),
        @Index(name = "idx_market_date_range", columnNames = {"market", "candleDateTimeKst"})
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_market_datetime", columnNames = {"market", "candleDateTimeKst"})
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HistoricalMinuteOhlcv extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "minute_ohlcv_id")
    private Long id;

    @Column(nullable = false, length = 32)
    private String market; // "KRW-ETH"

    @Column(nullable = false)
    private LocalDateTime candleDateTimeKst; // 1분봉 시각 (KST)

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal openingPrice; // 시가

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal highPrice; // 고가

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal lowPrice; // 저가

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal tradePrice; // 종가

    @Column(precision = 30, scale = 8)
    private BigDecimal candleAccTradeVolume; // 거래량

    private HistoricalMinuteOhlcv(String market, LocalDateTime candleDateTimeKst,
                                  BigDecimal openingPrice, BigDecimal highPrice,
                                  BigDecimal lowPrice, BigDecimal tradePrice,
                                  BigDecimal candleAccTradeVolume) {
        this.market = market;
        this.candleDateTimeKst = candleDateTimeKst;
        this.openingPrice = openingPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.tradePrice = tradePrice;
        this.candleAccTradeVolume = candleAccTradeVolume;
    }

    /**
     * 정적 팩토리 메서드
     */
    public static HistoricalMinuteOhlcv of(String market, LocalDateTime candleDateTimeKst,
                                           BigDecimal openingPrice, BigDecimal highPrice,
                                           BigDecimal lowPrice, BigDecimal tradePrice,
                                           BigDecimal candleAccTradeVolume) {
        return new HistoricalMinuteOhlcv(market, candleDateTimeKst, openingPrice, highPrice,
            lowPrice, tradePrice, candleAccTradeVolume);
    }
}
