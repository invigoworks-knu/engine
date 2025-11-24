package com.coinreaders.engine.adapter.in.web;

import com.coinreaders.engine.adapter.out.api.UpbitApiClient.UpbitTickerDto;
import com.coinreaders.engine.application.MarketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 시장 데이터 조회 REST API 컨트롤러
 *
 * 업비트 현재가, 호가 등 시장 데이터를 제공합니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/market")
@RequiredArgsConstructor
public class MarketController {

    private final MarketService marketService;

    /**
     * 특정 마켓의 현재가 조회
     *
     * GET /api/v1/market/ticker/{market}
     *
     * @param market 마켓 코드 (예: KRW-ETH)
     * @return 현재가 정보
     */
    @GetMapping("/ticker/{market}")
    public ResponseEntity<UpbitTickerDto> getTicker(@PathVariable String market) {
        log.info("[API] {} 현재가 조회 요청", market);

        UpbitTickerDto ticker = marketService.getCurrentPrice(market);

        log.info("[API] {} 현재가 조회 성공: {} KRW", market, ticker.getTradePrice());
        return ResponseEntity.ok(ticker);
    }

    /**
     * 이더리움(KRW-ETH) 현재가 조회 (기본값)
     *
     * GET /api/v1/market/ticker
     *
     * @return 이더리움 현재가 정보
     */
    @GetMapping("/ticker")
    public ResponseEntity<UpbitTickerDto> getEthereumTicker() {
        log.info("[API] 이더리움 현재가 조회 요청 (기본값)");

        UpbitTickerDto ticker = marketService.getEthereumPrice();

        log.info("[API] 이더리움 현재가 조회 성공: {} KRW", ticker.getTradePrice());
        return ResponseEntity.ok(ticker);
    }

    /**
     * 여러 마켓의 현재가 조회
     *
     * GET /api/v1/market/ticker?markets=KRW-ETH,KRW-BTC
     *
     * @param markets 마켓 코드 (쉼표로 구분, 예: "KRW-ETH,KRW-BTC")
     * @return 현재가 정보 리스트
     */
    @GetMapping("/tickers")
    public ResponseEntity<List<UpbitTickerDto>> getTickers(
            @RequestParam(defaultValue = "KRW-ETH") String markets) {

        log.info("[API] 여러 마켓 현재가 조회 요청: {}", markets);

        List<UpbitTickerDto> tickers = marketService.getCurrentPrices(markets)
                .collectList()
                .block(); // 동기 처리

        log.info("[API] 여러 마켓 현재가 조회 성공: {} 개 마켓", tickers != null ? tickers.size() : 0);
        return ResponseEntity.ok(tickers);
    }

    /**
     * 이더리움 현재가 간단 요약
     *
     * GET /api/v1/market/ethereum/summary
     *
     * @return {"market": "KRW-ETH", "price": 현재가, "change_rate": 변화율}
     */
    @GetMapping("/ethereum/summary")
    public ResponseEntity<Map<String, Object>> getEthereumSummary() {
        log.info("[API] 이더리움 현재가 요약 조회 요청");

        UpbitTickerDto ticker = marketService.getEthereumPrice();

        Map<String, Object> summary = new HashMap<>();
        summary.put("market", ticker.getMarket());
        summary.put("price", ticker.getTradePrice());
        summary.put("opening_price", ticker.getOpeningPrice());
        summary.put("high_price", ticker.getHighPrice());
        summary.put("low_price", ticker.getLowPrice());
        summary.put("change", ticker.getChange());
        summary.put("change_rate", ticker.getChangeRate());
        summary.put("signed_change_rate", ticker.getSignedChangeRate());

        log.info("[API] 이더리움 현재가 요약 조회 성공: {} KRW ({}%)",
                ticker.getTradePrice(), ticker.getSignedChangeRate());

        return ResponseEntity.ok(summary);
    }

    /**
     * 업비트 API 연결 테스트 (시장 데이터)
     *
     * GET /api/v1/market/test
     *
     * @return {"status": "ok", "message": "시장 데이터 조회 성공"}
     */
    @GetMapping("/test")
    public ResponseEntity<Map<String, String>> testMarketApi() {
        log.info("[API] 시장 데이터 API 연결 테스트");

        try {
            // 이더리움 현재가 조회 시도
            marketService.getEthereumPrice();

            Map<String, String> response = new HashMap<>();
            response.put("status", "ok");
            response.put("message", "시장 데이터 조회 성공");

            log.info("[API] 시장 데이터 API 연결 테스트 성공");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[API] 시장 데이터 API 연결 실패: {}", e.getMessage());

            Map<String, String> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", "시장 데이터 조회 실패: " + e.getMessage());

            return ResponseEntity.status(500).body(response);
        }
    }
}
