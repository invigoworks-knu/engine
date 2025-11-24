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
     * 업비트 현재가 조회 (공개 API - 인증 불필요)
     *
     * @param markets 마켓 코드 (예: "KRW-ETH", 복수 가능: "KRW-ETH,KRW-BTC")
     * @return 현재가 정보
     */
    public Flux<UpbitTickerDto> fetchTicker(String markets) {
        String fullUrl = UPBIT_API_URL + "/ticker";

        log.info("업비트 현재가 조회 시작: markets={}", markets);

        return webClient.get()
                .uri(fullUrl, uriBuilder -> {
                    uriBuilder.queryParam("markets", markets);
                    return uriBuilder.build();
                })
                .retrieve()
                .bodyToFlux(UpbitTickerDto.class)
                .doOnNext(ticker -> log.info("현재가 조회 성공: market={}, price={}, change={}%",
                        ticker.getMarket(), ticker.getTradePrice(), ticker.getChangeRate()))
                .doOnError(error -> log.error("현재가 조회 실패: {}", error.getMessage()));
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

    /**
     * 업비트 주문하기 (인증 필요)
     *
     * @param market 마켓 코드 (예: "KRW-ETH")
     * @param side 주문 종류 (bid: 매수, ask: 매도)
     * @param ordType 주문 타입 (price: 시장가 매수, market: 시장가 매도)
     * @param price 주문 금액 (시장가 매수 시 사용, KRW)
     * @param volume 주문 수량 (시장가 매도 시 사용, ETH)
     * @return 주문 결과
     */
    public UpbitOrderResponseDto placeOrder(String market, String side, String ordType,
                                             BigDecimal price, BigDecimal volume) {
        String fullUrl = UPBIT_API_URL + "/orders";

        // Query Parameters 구성
        java.util.Map<String, String> queryParams = new java.util.HashMap<>();
        queryParams.put("market", market);
        queryParams.put("side", side);
        queryParams.put("ord_type", ordType);

        if (price != null) {
            queryParams.put("price", price.toPlainString());
        }
        if (volume != null) {
            queryParams.put("volume", volume.toPlainString());
        }

        // JWT 토큰 생성 (Query Parameters 포함)
        String token = UpbitAuthUtil.generateToken(accessKey, secretKey, queryParams);

        log.info("업비트 주문 요청: market={}, side={}, ordType={}, price={}, volume={}",
                market, side, ordType, price, volume);

        return webClient.post()
                .uri(uriBuilder -> {
                    uriBuilder.scheme("https")
                            .host("api.upbit.com")
                            .path("/v1/orders")
                            .queryParam("market", market)
                            .queryParam("side", side)
                            .queryParam("ord_type", ordType);

                    if (price != null) {
                        uriBuilder.queryParam("price", price.toPlainString());
                    }
                    if (volume != null) {
                        uriBuilder.queryParam("volume", volume.toPlainString());
                    }

                    return uriBuilder.build();
                })
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(UpbitOrderResponseDto.class)
                .doOnSuccess(order -> log.info("주문 성공: uuid={}, side={}, price={}, volume={}",
                        order.getUuid(), order.getSide(), order.getPrice(), order.getVolume()))
                .doOnError(error -> log.error("주문 실패: {}", error.getMessage()))
                .block(); // 동기 처리
    }

    /**
     * 업비트 개별 주문 조회 (인증 필요)
     *
     * @param uuid 주문 UUID
     * @return 주문 상세 정보
     */
    public UpbitOrderResponseDto fetchOrder(String uuid) {
        String fullUrl = UPBIT_API_URL + "/order";

        // Query Parameters 구성
        java.util.Map<String, String> queryParams = new java.util.HashMap<>();
        queryParams.put("uuid", uuid);

        // JWT 토큰 생성 (Query Parameters 포함)
        String token = UpbitAuthUtil.generateToken(accessKey, secretKey, queryParams);

        log.info("업비트 주문 조회 시작: uuid={}", uuid);

        return webClient.get()
                .uri(fullUrl, uriBuilder -> {
                    uriBuilder.queryParam("uuid", uuid);
                    return uriBuilder.build();
                })
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(UpbitOrderResponseDto.class)
                .doOnSuccess(order -> log.info("주문 조회 성공: uuid={}, state={}, volume={}",
                        order.getUuid(), order.getState(), order.getVolume()))
                .doOnError(error -> log.error("주문 조회 실패: {}", error.getMessage()))
                .block(); // 동기 처리
    }

    /**
     * 업비트 주문 목록 조회 (인증 필요)
     *
     * @param state 주문 상태 (wait: 대기, done: 완료, cancel: 취소)
     * @param page 페이지 번호 (선택사항, 기본값 1)
     * @return 주문 목록
     */
    public Flux<UpbitOrderResponseDto> fetchOrders(String state, Integer page) {
        String fullUrl = UPBIT_API_URL + "/orders";

        // Query Parameters 구성
        java.util.Map<String, String> queryParams = new java.util.HashMap<>();
        queryParams.put("state", state);
        if (page != null) {
            queryParams.put("page", page.toString());
        }

        // JWT 토큰 생성 (Query Parameters 포함)
        String token = UpbitAuthUtil.generateToken(accessKey, secretKey, queryParams);

        log.info("업비트 주문 목록 조회 시작: state={}, page={}", state, page);

        return webClient.get()
                .uri(fullUrl, uriBuilder -> {
                    uriBuilder.queryParam("state", state);
                    if (page != null) {
                        uriBuilder.queryParam("page", page);
                    }
                    return uriBuilder.build();
                })
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToFlux(UpbitOrderResponseDto.class)
                .doOnComplete(() -> log.info("주문 목록 조회 완료"))
                .doOnError(error -> log.error("주문 목록 조회 실패: {}", error.getMessage()));
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

    /**
     * 업비트 주문 응답 DTO
     */
    @Getter
    @ToString
    public static class UpbitOrderResponseDto {
        @JsonProperty("uuid")
        private String uuid; // 주문 고유 아이디

        @JsonProperty("side")
        private String side; // 주문 종류 (bid: 매수, ask: 매도)

        @JsonProperty("ord_type")
        private String ordType; // 주문 타입 (limit: 지정가, price: 시장가 매수, market: 시장가 매도)

        @JsonProperty("price")
        private BigDecimal price; // 주문 금액 (지정가, 시장가 매수 시)

        @JsonProperty("avg_price")
        private BigDecimal avgPrice; // 평균 체결 가격

        @JsonProperty("state")
        private String state; // 주문 상태 (wait: 대기, done: 체결 완료, cancel: 취소)

        @JsonProperty("market")
        private String market; // 마켓 코드 (KRW-ETH)

        @JsonProperty("created_at")
        private String createdAt; // 주문 생성 시각

        @JsonProperty("volume")
        private BigDecimal volume; // 주문 수량

        @JsonProperty("remaining_volume")
        private BigDecimal remainingVolume; // 체결 후 남은 수량

        @JsonProperty("reserved_fee")
        private BigDecimal reservedFee; // 수수료로 예약된 금액

        @JsonProperty("remaining_fee")
        private BigDecimal remainingFee; // 남은 수수료

        @JsonProperty("paid_fee")
        private BigDecimal paidFee; // 사용된 수수료

        @JsonProperty("locked")
        private BigDecimal locked; // 거래에 사용 중인 금액

        @JsonProperty("executed_volume")
        private BigDecimal executedVolume; // 체결된 수량

        @JsonProperty("trades_count")
        private Integer tradesCount; // 체결 건수
    }

    /**
     * 업비트 현재가 조회 응답 DTO
     */
    @Getter
    @ToString
    public static class UpbitTickerDto {
        @JsonProperty("market")
        private String market; // 마켓 코드 (KRW-ETH)

        @JsonProperty("trade_date")
        private String tradeDate; // 최근 거래 일자 (UTC, yyyyMMdd)

        @JsonProperty("trade_time")
        private String tradeTime; // 최근 거래 시각 (UTC, HHmmss)

        @JsonProperty("trade_date_kst")
        private String tradeDateKst; // 최근 거래 일자 (KST, yyyyMMdd)

        @JsonProperty("trade_time_kst")
        private String tradeTimeKst; // 최근 거래 시각 (KST, HHmmss)

        @JsonProperty("trade_timestamp")
        private Long tradeTimestamp; // 체결 타임스탬프 (밀리초)

        @JsonProperty("opening_price")
        private BigDecimal openingPrice; // 시가

        @JsonProperty("high_price")
        private BigDecimal highPrice; // 고가

        @JsonProperty("low_price")
        private BigDecimal lowPrice; // 저가

        @JsonProperty("trade_price")
        private BigDecimal tradePrice; // 현재가

        @JsonProperty("prev_closing_price")
        private BigDecimal prevClosingPrice; // 전일 종가

        @JsonProperty("change")
        private String change; // 전일 대비 (RISE: 상승, FALL: 하락, EVEN: 보합)

        @JsonProperty("change_price")
        private BigDecimal changePrice; // 변화액 절대값

        @JsonProperty("change_rate")
        private BigDecimal changeRate; // 변화율 절대값 (0.05 = 5%)

        @JsonProperty("signed_change_price")
        private BigDecimal signedChangePrice; // 부호가 있는 변화액

        @JsonProperty("signed_change_rate")
        private BigDecimal signedChangeRate; // 부호가 있는 변화율

        @JsonProperty("trade_volume")
        private BigDecimal tradeVolume; // 가장 최근 거래량

        @JsonProperty("acc_trade_price")
        private BigDecimal accTradePrice; // 누적 거래 대금 (UTC 0시 기준)

        @JsonProperty("acc_trade_price_24h")
        private BigDecimal accTradePrice24h; // 24시간 누적 거래 대금

        @JsonProperty("acc_trade_volume")
        private BigDecimal accTradeVolume; // 누적 거래량 (UTC 0시 기준)

        @JsonProperty("acc_trade_volume_24h")
        private BigDecimal accTradeVolume24h; // 24시간 누적 거래량

        @JsonProperty("highest_52_week_price")
        private BigDecimal highest52WeekPrice; // 52주 최고가

        @JsonProperty("highest_52_week_date")
        private String highest52WeekDate; // 52주 최고가 달성일

        @JsonProperty("lowest_52_week_price")
        private BigDecimal lowest52WeekPrice; // 52주 최저가

        @JsonProperty("lowest_52_week_date")
        private String lowest52WeekDate; // 52주 최저가 달성일

        @JsonProperty("timestamp")
        private Long timestamp; // 타임스탬프 (밀리초)
    }
}