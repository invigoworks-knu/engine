package com.coinreaders.engine.adapter.in.web;

import com.coinreaders.engine.application.TradingSettingsService;
import com.coinreaders.engine.domain.entity.TradingSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 거래 안전장치 설정 REST API 컨트롤러
 *
 * 사용자별 거래 제한 설정을 관리합니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class TradingSettingsController {

    private final TradingSettingsService tradingSettingsService;

    /**
     * 현재 안전장치 설정 조회
     *
     * GET /api/v1/settings
     *
     * @return 현재 설정
     */
    @GetMapping
    public ResponseEntity<TradingSettings> getSettings() {
        log.info("[API] 안전장치 설정 조회 요청");

        try {
            TradingSettings settings = tradingSettingsService.getDefaultSettings();
            log.info("[API] 안전장치 설정 조회 성공");
            return ResponseEntity.ok(settings);

        } catch (Exception e) {
            log.error("[API] 안전장치 설정 조회 실패: {}", e.getMessage());
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * 안전장치 설정 업데이트
     *
     * PUT /api/v1/settings
     *
     * @param request 업데이트할 설정
     * @return 업데이트된 설정
     */
    @PutMapping
    public ResponseEntity<?> updateSettings(@RequestBody UpdateSettingsRequest request) {
        log.info("[API] 안전장치 설정 업데이트 요청: min={}, max={}, maxDaily={}, market={}",
                request.getMinOrderAmount(), request.getMaxOrderAmount(),
                request.getMaxDailyTrades(), request.getAllowedMarket());

        try {
            TradingSettings settings = tradingSettingsService.updateSettings(
                    request.getMinOrderAmount(),
                    request.getMaxOrderAmount(),
                    request.getMaxDailyTrades(),
                    request.getAllowedMarket()
            );

            log.info("[API] 안전장치 설정 업데이트 성공");
            return ResponseEntity.ok(settings);

        } catch (Exception e) {
            log.error("[API] 안전장치 설정 업데이트 실패: {}", e.getMessage());

            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "설정 업데이트 실패: " + e.getMessage());

            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * 안전장치 활성화/비활성화
     *
     * POST /api/v1/settings/toggle
     *
     * @param request 활성화 여부
     * @return 업데이트된 설정
     */
    @PostMapping("/toggle")
    public ResponseEntity<?> toggleEnabled(@RequestBody ToggleRequest request) {
        log.info("[API] 안전장치 토글 요청: enabled={}", request.getEnabled());

        try {
            TradingSettings settings = tradingSettingsService.toggleEnabled(request.getEnabled());
            log.info("[API] 안전장치 토글 성공: enabled={}", settings.getIsEnabled());
            return ResponseEntity.ok(settings);

        } catch (Exception e) {
            log.error("[API] 안전장치 토글 실패: {}", e.getMessage());

            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "설정 토글 실패: " + e.getMessage());

            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * 기본 설정으로 초기화
     *
     * POST /api/v1/settings/reset
     *
     * @return 초기화된 설정
     */
    @PostMapping("/reset")
    public ResponseEntity<?> resetToDefault() {
        log.info("[API] 안전장치 설정 초기화 요청");

        try {
            TradingSettings settings = tradingSettingsService.updateSettings(
                    new BigDecimal("5000"),
                    new BigDecimal("10000"),
                    10,
                    "KRW-ETH"
            );

            log.info("[API] 안전장치 설정 초기화 성공");
            return ResponseEntity.ok(settings);

        } catch (Exception e) {
            log.error("[API] 안전장치 설정 초기화 실패: {}", e.getMessage());

            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "설정 초기화 실패: " + e.getMessage());

            return ResponseEntity.status(500).body(error);
        }
    }

    // ==================== Request DTO ====================

    /**
     * 설정 업데이트 요청 DTO
     */
    public static class UpdateSettingsRequest {
        private BigDecimal minOrderAmount;
        private BigDecimal maxOrderAmount;
        private Integer maxDailyTrades;
        private String allowedMarket;

        public BigDecimal getMinOrderAmount() {
            return minOrderAmount;
        }

        public void setMinOrderAmount(BigDecimal minOrderAmount) {
            this.minOrderAmount = minOrderAmount;
        }

        public BigDecimal getMaxOrderAmount() {
            return maxOrderAmount;
        }

        public void setMaxOrderAmount(BigDecimal maxOrderAmount) {
            this.maxOrderAmount = maxOrderAmount;
        }

        public Integer getMaxDailyTrades() {
            return maxDailyTrades;
        }

        public void setMaxDailyTrades(Integer maxDailyTrades) {
            this.maxDailyTrades = maxDailyTrades;
        }

        public String getAllowedMarket() {
            return allowedMarket;
        }

        public void setAllowedMarket(String allowedMarket) {
            this.allowedMarket = allowedMarket;
        }
    }

    /**
     * 토글 요청 DTO
     */
    public static class ToggleRequest {
        private Boolean enabled;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }
}
