package com.coinreaders.engine.domain.entity;

import com.coinreaders.engine.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal balanceKrw;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal balanceEth;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal avgBuyPrice;

    @Builder
    public Account(User user, BigDecimal balanceKrw, BigDecimal balanceEth, BigDecimal avgBuyPrice) {
        this.user = user;
        this.balanceKrw = balanceKrw != null ? balanceKrw : BigDecimal.ZERO;
        this.balanceEth = balanceEth != null ? balanceEth : BigDecimal.ZERO;
        this.avgBuyPrice = avgBuyPrice != null ? avgBuyPrice : BigDecimal.ZERO;
    }

    /**
     * KRW 잔고 증가 (매도 시)
     */
    public void addKrw(BigDecimal amount) {
        this.balanceKrw = this.balanceKrw.add(amount);
    }

    /**
     * KRW 잔고 감소 (매수 시)
     */
    public void subtractKrw(BigDecimal amount) {
        this.balanceKrw = this.balanceKrw.subtract(amount);
    }

    /**
     * ETH 잔고 증가 (매수 시)
     */
    public void addEth(BigDecimal amount, BigDecimal buyPrice) {
        // 가중평균 매수가 계산
        BigDecimal totalValue = this.balanceEth.multiply(this.avgBuyPrice)
                .add(amount.multiply(buyPrice));
        BigDecimal totalVolume = this.balanceEth.add(amount);

        if (totalVolume.compareTo(BigDecimal.ZERO) > 0) {
            this.avgBuyPrice = totalValue.divide(totalVolume, 8, RoundingMode.HALF_UP);
        }

        this.balanceEth = totalVolume;
    }

    /**
     * ETH 잔고 감소 (매도 시)
     */
    public void subtractEth(BigDecimal amount) {
        this.balanceEth = this.balanceEth.subtract(amount);

        // 전량 매도 시 평균 매수가 초기화
        if (this.balanceEth.compareTo(BigDecimal.ZERO) == 0) {
            this.avgBuyPrice = BigDecimal.ZERO;
        }
    }

    /**
     * 잔고 동기화 (업비트 API 결과로 업데이트)
     */
    public void syncBalances(BigDecimal krw, BigDecimal eth, BigDecimal avgPrice) {
        this.balanceKrw = krw;
        this.balanceEth = eth;
        this.avgBuyPrice = avgPrice;
    }
}