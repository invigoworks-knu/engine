package com.coinreaders.engine.application;

import com.coinreaders.engine.adapter.out.api.UpbitApiClient;
import com.coinreaders.engine.adapter.out.api.UpbitApiClient.UpbitAccountDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 계좌 관리 서비스
 *
 * 업비트 API를 통해 실시간 잔고를 조회하고 관리합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final UpbitApiClient upbitApiClient;

    /**
     * 업비트 계좌 잔고 조회
     *
     * @return 통화별 잔고 정보 (KRW, ETH 등)
     */
    public Flux<UpbitAccountDto> getUpbitBalance() {
        log.info("업비트 잔고 조회 요청");
        return upbitApiClient.fetchAccounts()
                .doOnComplete(() -> log.info("업비트 잔고 조회 완료"));
    }

    /**
     * KRW와 ETH 잔고만 추출하여 Map으로 반환
     *
     * @return Map<String, BigDecimal> - "KRW", "ETH" 키로 잔고 조회
     */
    public Map<String, BigDecimal> getKrwAndEthBalance() {
        Map<String, BigDecimal> balances = new HashMap<>();

        upbitApiClient.fetchAccounts()
                .doOnNext(account -> {
                    String currency = account.getCurrency();
                    if ("KRW".equals(currency) || "ETH".equals(currency)) {
                        balances.put(currency, account.getBalance());
                        log.info("{} 잔고: {}", currency, account.getBalance());
                    }
                })
                .blockLast(); // 동기 처리 (모든 응답을 기다림)

        return balances;
    }

    /**
     * 특정 통화의 잔고 조회
     *
     * @param currency 통화 코드 (예: "KRW", "ETH")
     * @return 해당 통화의 잔고 (없으면 BigDecimal.ZERO)
     */
    public BigDecimal getBalanceByCurrency(String currency) {
        log.info("{} 잔고 조회 시작", currency);

        return upbitApiClient.fetchAccounts()
                .filter(account -> currency.equals(account.getCurrency()))
                .map(UpbitAccountDto::getBalance)
                .next() // 첫 번째 값만 가져옴
                .defaultIfEmpty(BigDecimal.ZERO) // 값이 없으면 0 반환
                .block(); // 동기 처리
    }
}
