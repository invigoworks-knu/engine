package com.coinreaders.engine.adapter.in.web;

import com.coinreaders.engine.adapter.out.api.UpbitApiClient.UpbitAccountDto;
import com.coinreaders.engine.application.AccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 계좌 관리 REST API 컨트롤러
 *
 * 업비트 계좌 잔고 조회 기능을 제공합니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    /**
     * 업비트 계좌 전체 잔고 조회
     *
     * GET /api/v1/account/balance
     *
     * @return 모든 통화의 잔고 정보 (KRW, ETH, BTC 등)
     */
    @GetMapping("/balance")
    public ResponseEntity<List<UpbitAccountDto>> getBalance() {
        log.info("[API] 전체 잔고 조회 요청");

        List<UpbitAccountDto> accounts = accountService.getUpbitBalance()
                .collectList()
                .block(); // 동기 처리

        log.info("[API] 전체 잔고 조회 성공: {} 개 통화", accounts != null ? accounts.size() : 0);
        return ResponseEntity.ok(accounts);
    }

    /**
     * KRW와 ETH 잔고만 조회 (간단한 요약)
     *
     * GET /api/v1/account/balance/summary
     *
     * @return {"KRW": 잔고, "ETH": 잔고}
     */
    @GetMapping("/balance/summary")
    public ResponseEntity<Map<String, BigDecimal>> getBalanceSummary() {
        log.info("[API] KRW/ETH 잔고 요약 조회 요청");

        Map<String, BigDecimal> balances = accountService.getKrwAndEthBalance();

        log.info("[API] 잔고 요약 조회 성공: KRW={}, ETH={}",
                balances.get("KRW"), balances.get("ETH"));

        return ResponseEntity.ok(balances);
    }

    /**
     * 특정 통화의 잔고 조회
     *
     * GET /api/v1/account/balance/{currency}
     *
     * @param currency 통화 코드 (예: KRW, ETH)
     * @return {"currency": "KRW", "balance": 잔고}
     */
    @GetMapping("/balance/{currency}")
    public ResponseEntity<Map<String, Object>> getBalanceByCurrency(
            @PathVariable String currency) {

        log.info("[API] {} 잔고 조회 요청", currency);

        BigDecimal balance = accountService.getBalanceByCurrency(currency.toUpperCase());

        Map<String, Object> response = new HashMap<>();
        response.put("currency", currency.toUpperCase());
        response.put("balance", balance);

        log.info("[API] {} 잔고 조회 성공: {}", currency, balance);
        return ResponseEntity.ok(response);
    }

    /**
     * 업비트 API 연결 테스트
     *
     * GET /api/v1/account/test
     *
     * @return {"status": "ok", "message": "업비트 API 연결 성공"}
     */
    @GetMapping("/test")
    public ResponseEntity<Map<String, String>> testConnection() {
        log.info("[API] 업비트 API 연결 테스트");

        try {
            // 잔고 조회 시도
            accountService.getUpbitBalance()
                    .blockFirst(); // 첫 번째 값만 가져옴

            Map<String, String> response = new HashMap<>();
            response.put("status", "ok");
            response.put("message", "업비트 API 연결 성공");

            log.info("[API] 업비트 API 연결 테스트 성공");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[API] 업비트 API 연결 실패: {}", e.getMessage());

            Map<String, String> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", "업비트 API 연결 실패: " + e.getMessage());

            return ResponseEntity.status(500).body(response);
        }
    }
}
