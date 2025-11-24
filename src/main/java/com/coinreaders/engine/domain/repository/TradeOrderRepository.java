package com.coinreaders.engine.domain.repository;

import com.coinreaders.engine.domain.entity.Account;
import com.coinreaders.engine.domain.entity.TradeOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TradeOrderRepository extends JpaRepository<TradeOrder, Long> {

    /**
     * 특정 계좌의 특정 기간 동안 주문 횟수 조회
     */
    @Query("SELECT COUNT(o) FROM TradeOrder o WHERE o.account = :account AND o.createdAt >= :startTime")
    long countByAccountAndCreatedAtAfter(@Param("account") Account account, @Param("startTime") LocalDateTime startTime);

    /**
     * 특정 계좌의 모든 주문 조회 (최신순)
     */
    List<TradeOrder> findByAccountOrderByCreatedAtDesc(Account account);

    /**
     * 업비트 주문 UUID로 조회
     */
    TradeOrder findByUpbitOrderUuid(String upbitOrderUuid);
}