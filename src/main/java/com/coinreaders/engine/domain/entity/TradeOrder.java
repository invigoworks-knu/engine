package com.coinreaders.engine.domain.entity;

import com.coinreaders.engine.domain.common.BaseTimeEntity;
import com.coinreaders.engine.domain.constant.OrderStatus;
import com.coinreaders.engine.domain.constant.OrderType;
import com.coinreaders.engine.domain.constant.Side;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "trade_order",
    indexes = {
        @Index(name = "idx_trade_order_account_created", columnList = "account_id, created_at"),
        @Index(name = "idx_trade_order_uuid", columnList = "upbit_order_uuid")
    }
)
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

    @Column(length = 64)
    private String upbitOrderUuid; // 업비트 주문 UUID

    @Column(nullable = false, length = 32)
    private String market; // 마켓 코드 (예: KRW-ETH)

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

    @Builder
    public TradeOrder(Account account, String upbitOrderUuid, String market,
                      OrderType orderType, Side side, BigDecimal price,
                      BigDecimal amount, OrderStatus status) {
        this.account = account;
        this.upbitOrderUuid = upbitOrderUuid;
        this.market = market;
        this.orderType = orderType;
        this.side = side;
        this.price = price;
        this.amount = amount;
        this.status = status;
    }

    /**
     * 주문 상태 업데이트
     */
    public void updateStatus(OrderStatus newStatus) {
        this.status = newStatus;
    }

    /**
     * 업비트 주문 UUID 설정
     */
    public void setUpbitOrderUuid(String uuid) {
        this.upbitOrderUuid = uuid;
    }
}