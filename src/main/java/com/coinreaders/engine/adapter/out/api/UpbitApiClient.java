package com.coinreaders.engine.adapter.out.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpbitApiClient {

    private final WebClient webClient;
    private static final String UPBIT_API_URL = "https://api.upbit.com/v1";

    @Value("${upbit.api.access-key}")
    private String accessKey;

    @Value("${upbit.api.secret-key}")
    private String secretKey;

    /**
     * Upbit에서 일봉(Day Candle) 데이터를 가져옵니다.
     * @param market (예: "KRW-ETH")
     * @param count (가져올 개수, 최대 200)
     * @param to (조회 마지막 캔들 시각. e.g., "2025-11-03T09:00:00". null이면 현재시각)
     * @return
     */
    public Flux<UpbitDayCandleDto> fetchDayCandles(String market, int count, String to) {
        String fullUrl = UPBIT_API_URL + "/candles/days";

        return webClient.get()
            .uri(fullUrl, uriBuilder -> {
                uriBuilder.queryParam("market", market)
                    .queryParam("count", count);

                // 'to' 파라미터가 있으면 쿼리에 추가
                if (to != null && !to.isEmpty()) {
                    uriBuilder.queryParam("to", to);
                }
                return uriBuilder.build();
            })
            .retrieve()
            .bodyToFlux(UpbitDayCandleDto.class);
    }

    /**
     * 업비트 계좌 잔고 조회 (인증 필요)
     *
     * @return 계좌 잔고 정보 (KRW, ETH 등)
     */
    public Flux<UpbitAccountDto> fetchAccounts() {
        String fullUrl = UPBIT_API_URL + "/accounts";

        // JWT 토큰 생성 (Query Parameters 없음)
        String token = UpbitAuthUtil.generateToken(accessKey, secretKey);

        log.info("업비트 계좌 잔고 조회 시작");

        return webClient.get()
                .uri(fullUrl)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToFlux(UpbitAccountDto.class)
                .doOnNext(account -> log.info("잔고 조회 성공: currency={}, balance={}, locked={}",
                        account.getCurrency(), account.getBalance(), account.getLocked()))
                .doOnError(error -> log.error("잔고 조회 실패: {}", error.getMessage()));
    }

    // ==================== DTO 클래스 ====================

    // API 응답을 매핑할 DTO (Data Transfer Object)
    @Getter
    @ToString
    public static class UpbitDayCandleDto {
        @JsonProperty("market")
        private String market; // 마켓 코드
        @JsonProperty("candle_date_time_kst")
        private String candleDateTimeKst; // 캔들 시간 (KST)
        @JsonProperty("opening_price")
        private BigDecimal openingPrice; // 시가
        @JsonProperty("high_price")
        private BigDecimal highPrice; // 고가
        @JsonProperty("low_price")
        private BigDecimal lowPrice; // 저가
        @JsonProperty("trade_price")
        private BigDecimal tradePrice; // 종가
        @JsonProperty("candle_acc_trade_volume")
        private BigDecimal candleAccTradeVolume; // 누적 거래량
    }

    /**
     * 업비트 계좌 잔고 조회 응답 DTO
     */
    @Getter
    @ToString
    public static class UpbitAccountDto {
        @JsonProperty("currency")
        private String currency; // 화폐 (KRW, ETH, BTC 등)

        @JsonProperty("balance")
        private BigDecimal balance; // 보유 수량 (가용 금액)

        @JsonProperty("locked")
        private BigDecimal locked; // 주문 중 묶여있는 수량

        @JsonProperty("avg_buy_price")
        private BigDecimal avgBuyPrice; // 평균 매수가

        @JsonProperty("avg_buy_price_modified")
        private Boolean avgBuyPriceModified; // 평균 매수가 수정 여부

        @JsonProperty("unit_currency")
        private String unitCurrency; // 평단가 기준 화폐 (보통 KRW)
    }
}