package com.coinreaders.engine.domain.repository;

import com.coinreaders.engine.domain.entity.TradingSettings;
import com.coinreaders.engine.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TradingSettingsRepository extends JpaRepository<TradingSettings, Long> {

    /**
     * 사용자별 안전장치 설정 조회
     */
    Optional<TradingSettings> findByUser(User user);
}
