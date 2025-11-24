package com.coinreaders.engine.adapter.in.web;

import com.coinreaders.engine.adapter.out.api.UpbitApiClient.UpbitOrderResponseDto;
import com.coinreaders.engine.application.TradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 거래 REST API 컨트롤러
 *
 * 시장가 매수/매도 주문 기능을 제공합니다.
 *
 * ⚠️ 주의: 실제 자금이 이동하는 API입니다!
 *
 * 안전장치:
 * - 최소 주문금액: 5,000원
 * - 최대 주문금액: 10,000원 (테스트용)
 * - 일일 거래 횟수 제한: 10회
 * - KRW-ETH만 허용
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/trading")
@RequiredArgsConstructor
public class TradingController {

    private final TradingService tradingService;

    /**
     * 시장가 매수 (KRW-ETH)
     *
     * POST /api/v1/trading/market-buy
     *
     * @param request {"market": "KRW-ETH", "amount": 5000}
     * @return 주문 결과
     */
    @PostMapping("/market-buy")
    public ResponseEntity<?> marketBuy(@RequestBody MarketBuyRequest request) {
        log.info("[API] 시장가 매수 요청: market={}, amount={} KRW",
                request.getMarket(), request.getAmount());

        try {
            UpbitOrderResponseDto response = tradingService.marketBuy(
                    request.getMarket(),
                    request.getAmount()
            );

            log.info("[API] 시장가 매수 성공: uuid={}", response.getUuid());
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.error("[API] 시장가 매수 실패 (검증 오류): {}", e.getMessage());

            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());

            return ResponseEntity.badRequest().body(error);

        } catch (Exception e) {
            log.error("[API] 시장가 매수 실패 (시스템 오류): {}", e.getMessage());

            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "주문 실패: " + e.getMessage());

            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 시장가 매도 (KRW-ETH)
     *
     * POST /api/v1/trading/market-sell
     *
     * @param request {"market": "KRW-ETH", "volume": 0.001}
     * @return 주문 결과
     */
    @PostMapping("/market-sell")
    public ResponseEntity<?> marketSell(@RequestBody MarketSellRequest request) {
        log.info("[API] 시장가 매도 요청: market={}, volume={} ETH",
                request.getMarket(), request.getVolume());

        try {
            UpbitOrderResponseDto response = tradingService.marketSell(
                    request.getMarket(),
                    request.getVolume()
            );

            log.info("[API] 시장가 매도 성공: uuid={}", response.getUuid());
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.error("[API] 시장가 매도 실패 (검증 오류): {}", e.getMessage());

            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());

            return ResponseEntity.badRequest().body(error);

        } catch (Exception e) {
            log.error("[API] 시장가 매도 실패 (시스템 오류): {}", e.getMessage());

            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "주문 실패: " + e.getMessage());

            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 거래 가능 여부 확인
     *
     * GET /api/v1/trading/check
     *
     * @return 거래 가능 여부 및 제한 정보
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkTradingStatus() {
        log.info("[API] 거래 가능 여부 확인 요청");

        Map<String, Object> status = new HashMap<>();
        status.put("status", "ok");
        status.put("min_order_amount", 5000);
        status.put("max_order_amount", 10000);
        status.put("max_daily_trades", 10);
        status.put("allowed_market", "KRW-ETH");
        status.put("message", "거래 가능 (안전장치 활성화)");

        return ResponseEntity.ok(status);
    }

    /**
     * 안전장치 정보 조회
     *
     * GET /api/v1/trading/limits
     *
     * @return 안전장치 정보
     */
    @GetMapping("/limits")
    public ResponseEntity<Map<String, Object>> getTradingLimits() {
        log.info("[API] 안전장치 정보 조회");

        Map<String, Object> limits = new HashMap<>();
        limits.put("min_order_amount_krw", 5000);
        limits.put("max_order_amount_krw", 10000);
        limits.put("max_daily_trades", 10);
        limits.put("allowed_markets", new String[]{"KRW-ETH"});
        limits.put("description", "테스트용 안전장치가 활성화되어 있습니다.");

        return ResponseEntity.ok(limits);
    }

    // ==================== Request DTO ====================

    /**
     * 시장가 매수 요청 DTO
     */
    public static class MarketBuyRequest {
        private String market;
        private BigDecimal amount;

        public String getMarket() {
            return market != null ? market : "KRW-ETH"; // 기본값
        }

        public void setMarket(String market) {
            this.market = market;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }
    }

    /**
     * 시장가 매도 요청 DTO
     */
    public static class MarketSellRequest {
        private String market;
        private BigDecimal volume;

        public String getMarket() {
            return market != null ? market : "KRW-ETH"; // 기본값
        }

        public void setMarket(String market) {
            this.market = market;
        }

        public BigDecimal getVolume() {
            return volume;
        }

        public void setVolume(BigDecimal volume) {
            this.volume = volume;
        }
    }
}
