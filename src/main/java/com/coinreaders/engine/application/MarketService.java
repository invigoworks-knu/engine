package com.coinreaders.engine.application;

import com.coinreaders.engine.adapter.out.api.UpbitApiClient;
import com.coinreaders.engine.adapter.out.api.UpbitApiClient.UpbitTickerDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 시장 데이터 조회 서비스
 *
 * 업비트 API를 통해 현재가, 호가 등 시장 데이터를 조회합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketService {

    private final UpbitApiClient upbitApiClient;

    /**
     * 특정 마켓의 현재가 조회
     *
     * @param market 마켓 코드 (예: "KRW-ETH")
     * @return 현재가 정보
     */
    public UpbitTickerDto getCurrentPrice(String market) {
        log.info("[MarketService] {} 현재가 조회 시작", market);

        return upbitApiClient.fetchTicker(market)
                .next() // 첫 번째 값만 가져옴
                .doOnSuccess(ticker -> log.info("[MarketService] {} 현재가 조회 성공: {} KRW",
                        market, ticker.getTradePrice()))
                .block(); // 동기 처리
    }

    /**
     * 여러 마켓의 현재가 조회
     *
     * @param markets 마켓 코드 (쉼표로 구분, 예: "KRW-ETH,KRW-BTC")
     * @return 현재가 정보 리스트
     */
    public Flux<UpbitTickerDto> getCurrentPrices(String markets) {
        log.info("[MarketService] 여러 마켓 현재가 조회 시작: {}", markets);

        return upbitApiClient.fetchTicker(markets)
                .doOnComplete(() -> log.info("[MarketService] 여러 마켓 현재가 조회 완료"));
    }

    /**
     * KRW-ETH 현재가 조회 (기본값)
     *
     * @return 이더리움 현재가 정보
     */
    public UpbitTickerDto getEthereumPrice() {
        return getCurrentPrice("KRW-ETH");
    }
}
