package com.coinreaders.engine.adapter.out.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class UpbitApiClient {

    private final WebClient webClient;
    private static final String UPBIT_API_URL = "https://api.upbit.com/v1";

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
}