package com.coinreaders.engine.application;

import com.coinreaders.engine.domain.entity.TradingSettings;
import com.coinreaders.engine.domain.repository.TradingSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradingSettingsService {

    private final TradingSettingsRepository tradingSettingsRepository;

    /**
     * 기본 설정 조회 (User가 없으므로 ID 1로 가정)
     */
    public TradingSettings getDefaultSettings() {
        return tradingSettingsRepository.findById(1L)
                .orElse(createDefaultSettings());
    }

    /**
     * 기본 설정 생성
     */
    @Transactional
    public TradingSettings createDefaultSettings() {
        TradingSettings settings = TradingSettings.builder()
                .user(null) // User 없이 테스트
                .minOrderAmount(new BigDecimal("5000"))
                .maxOrderAmount(new BigDecimal("10000"))
                .maxDailyTrades(10)
                .allowedMarket("KRW-ETH")
                .isEnabled(true)
                .build();

        return tradingSettingsRepository.save(settings);
    }

    /**
     * 설정 업데이트
     */
    @Transactional
    public TradingSettings updateSettings(BigDecimal minOrderAmount, BigDecimal maxOrderAmount,
                                          Integer maxDailyTrades, String allowedMarket) {
        TradingSettings settings = getDefaultSettings();
        settings.updateSettings(minOrderAmount, maxOrderAmount, maxDailyTrades, allowedMarket);
        return tradingSettingsRepository.save(settings);
    }

    /**
     * 안전장치 활성화/비활성화
     */
    @Transactional
    public TradingSettings toggleEnabled(Boolean enabled) {
        TradingSettings settings = getDefaultSettings();
        settings.setEnabled(enabled);
        return tradingSettingsRepository.save(settings);
    }
}
