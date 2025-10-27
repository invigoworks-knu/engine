package com.coinreaders.engine.domain.repository;

import com.coinreaders.engine.domain.entity.TradeFill;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeFillRepository extends JpaRepository<TradeFill, Long> {

}