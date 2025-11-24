package com.coinreaders.engine.domain.entity;

import com.coinreaders.engine.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 거래 안전장치 설정
 *
 * 사용자별로 거래 제한을 설정합니다.
 */
@Entity
@Table(name = "trading_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TradingSettings extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "settings_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = true, unique = true)
    private User user;

    @Column(nullable = false, precision = 30, scale = 2)
    private BigDecimal minOrderAmount; // 최소 주문 금액 (KRW)

    @Column(nullable = false, precision = 30, scale = 2)
    private BigDecimal maxOrderAmount; // 최대 주문 금액 (KRW)

    @Column(nullable = false)
    private Integer maxDailyTrades; // 일일 최대 거래 횟수

    @Column(nullable = false, length = 32)
    private String allowedMarket; // 허용된 마켓 (예: KRW-ETH)

    @Column(nullable = false)
    private Boolean isEnabled; // 안전장치 활성화 여부

    @Builder
    public TradingSettings(User user, BigDecimal minOrderAmount, BigDecimal maxOrderAmount,
                           Integer maxDailyTrades, String allowedMarket, Boolean isEnabled) {
        this.user = user;
        this.minOrderAmount = minOrderAmount;
        this.maxOrderAmount = maxOrderAmount;
        this.maxDailyTrades = maxDailyTrades;
        this.allowedMarket = allowedMarket;
        this.isEnabled = isEnabled != null ? isEnabled : true;
    }

    /**
     * 안전장치 설정 업데이트
     */
    public void updateSettings(BigDecimal minOrderAmount, BigDecimal maxOrderAmount,
                               Integer maxDailyTrades, String allowedMarket) {
        this.minOrderAmount = minOrderAmount;
        this.maxOrderAmount = maxOrderAmount;
        this.maxDailyTrades = maxDailyTrades;
        this.allowedMarket = allowedMarket;
    }

    /**
     * 안전장치 활성화/비활성화
     */
    public void setEnabled(Boolean enabled) {
        this.isEnabled = enabled;
    }

    /**
     * 기본 설정 생성
     */
    public static TradingSettings createDefault(User user) {
        return TradingSettings.builder()
                .user(user)
                .minOrderAmount(new BigDecimal("5000"))
                .maxOrderAmount(new BigDecimal("10000"))
                .maxDailyTrades(10)
                .allowedMarket("KRW-ETH")
                .isEnabled(true)
                .build();
    }
}
