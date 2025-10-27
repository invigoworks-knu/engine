package com.coinreaders.engine.domain.entity;

import com.coinreaders.engine.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "trade_fill")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TradeFill extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "fill_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private TradeOrder tradeOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal price;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal amount;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal fee;

}