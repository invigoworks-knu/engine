package com.coinreaders.engine.domain.entity;

import com.coinreaders.engine.domain.common.BaseTimeEntity;
import com.coinreaders.engine.domain.constant.OrderStatus;
import com.coinreaders.engine.domain.constant.OrderType;
import com.coinreaders.engine.domain.constant.Side;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "trade_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TradeOrder extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Side side;

    @Column(precision = 30, scale = 8)
    private BigDecimal price;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

}