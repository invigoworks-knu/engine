package com.coinreaders.engine.domain.repository;

import com.coinreaders.engine.domain.entity.TradeOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeOrderRepository extends JpaRepository<TradeOrder, Long> {

}