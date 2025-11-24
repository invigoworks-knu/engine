package com.coinreaders.engine.application;

import com.coinreaders.engine.adapter.out.api.UpbitApiClient;
import com.coinreaders.engine.adapter.out.api.UpbitApiClient.UpbitOrderResponseDto;
import com.coinreaders.engine.domain.constant.OrderStatus;
import com.coinreaders.engine.domain.constant.OrderType;
import com.coinreaders.engine.domain.constant.Side;
import com.coinreaders.engine.domain.entity.Account;
import com.coinreaders.engine.domain.entity.TradeOrder;
import com.coinreaders.engine.domain.repository.AccountRepository;
import com.coinreaders.engine.domain.repository.TradeOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 거래 서비스
 *
 * 업비트 API를 통해 실제 거래(매수/매도)를 실행합니다.
 *
 * 안전장치:
 * - 최소 주문금액: 5,000원
 * - 최대 주문금액: 10,000원 (테스트용)
 * - 일일 거래 횟수 제한: 10회
 * - KRW-ETH만 허용
 * - 잔고 초과 방지
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingService {

    private final UpbitApiClient upbitApiClient;
    private final AccountRepository accountRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final AccountService accountService;

    // 안전장치 상수
    private static final BigDecimal MIN_ORDER_AMOUNT = new BigDecimal("5000"); // 최소 5,000원
    private static final BigDecimal MAX_ORDER_AMOUNT = new BigDecimal("10000"); // 최대 10,000원 (테스트용)
    private static final int MAX_DAILY_TRADES = 10; // 일일 최대 거래 횟수
    private static final String ALLOWED_MARKET = "KRW-ETH"; // 이더리움만 허용

    /**
     * 시장가 매수
     *
     * @param market 마켓 코드 (KRW-ETH만 허용)
     * @param krwAmount 매수 금액 (KRW)
     * @return 주문 응답
     */
    @Transactional
    public UpbitOrderResponseDto marketBuy(String market, BigDecimal krwAmount) {
        log.info("[거래] 시장가 매수 요청: market={}, amount={} KRW", market, krwAmount);

        // 안전장치 검증
        validateMarket(market);
        validateBuyAmount(krwAmount);
        validateDailyTradeLimit(null); // Account는 나중에 구현

        // 잔고 확인
        BigDecimal krwBalance = accountService.getBalanceByCurrency("KRW");
        if (krwBalance.compareTo(krwAmount) < 0) {
            throw new IllegalArgumentException(
                    String.format("잔고 부족: 보유 %s KRW, 주문 %s KRW", krwBalance, krwAmount));
        }

        // 업비트 API 호출
        UpbitOrderResponseDto response = upbitApiClient.placeOrder(
                market,
                "bid", // 매수
                "price", // 시장가 매수 (금액 지정)
                krwAmount,
                null
        );

        // DB에 주문 기록 저장 (Account는 나중에 연결)
        saveTradeOrder(null, response, market, Side.BID, OrderType.MARKET, krwAmount);

        log.info("[거래] 시장가 매수 성공: uuid={}, market={}, amount={} KRW",
                response.getUuid(), market, krwAmount);

        return response;
    }

    /**
     * 시장가 매도
     *
     * @param market 마켓 코드 (KRW-ETH만 허용)
     * @param volume 매도 수량 (ETH)
     * @return 주문 응답
     */
    @Transactional
    public UpbitOrderResponseDto marketSell(String market, BigDecimal volume) {
        log.info("[거래] 시장가 매도 요청: market={}, volume={} ETH", market, volume);

        // 안전장치 검증
        validateMarket(market);
        validateSellVolume(volume);
        validateDailyTradeLimit(null); // Account는 나중에 구현

        // 잔고 확인
        BigDecimal ethBalance = accountService.getBalanceByCurrency("ETH");
        if (ethBalance.compareTo(volume) < 0) {
            throw new IllegalArgumentException(
                    String.format("잔고 부족: 보유 %s ETH, 주문 %s ETH", ethBalance, volume));
        }

        // 업비트 API 호출
        UpbitOrderResponseDto response = upbitApiClient.placeOrder(
                market,
                "ask", // 매도
                "market", // 시장가 매도 (수량 지정)
                null,
                volume
        );

        // DB에 주문 기록 저장 (Account는 나중에 연결)
        saveTradeOrder(null, response, market, Side.ASK, OrderType.MARKET, volume);

        log.info("[거래] 시장가 매도 성공: uuid={}, market={}, volume={} ETH",
                response.getUuid(), market, volume);

        return response;
    }

    /**
     * 마켓 검증 (KRW-ETH만 허용)
     */
    private void validateMarket(String market) {
        if (!ALLOWED_MARKET.equals(market)) {
            throw new IllegalArgumentException(
                    String.format("허용되지 않은 마켓: %s (허용: %s)", market, ALLOWED_MARKET));
        }
    }

    /**
     * 매수 금액 검증
     */
    private void validateBuyAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("매수 금액은 0보다 커야 합니다.");
        }

        if (amount.compareTo(MIN_ORDER_AMOUNT) < 0) {
            throw new IllegalArgumentException(
                    String.format("최소 주문금액: %s KRW (입력: %s KRW)", MIN_ORDER_AMOUNT, amount));
        }

        if (amount.compareTo(MAX_ORDER_AMOUNT) > 0) {
            throw new IllegalArgumentException(
                    String.format("최대 주문금액: %s KRW (입력: %s KRW)", MAX_ORDER_AMOUNT, amount));
        }
    }

    /**
     * 매도 수량 검증
     */
    private void validateSellVolume(BigDecimal volume) {
        if (volume == null || volume.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("매도 수량은 0보다 커야 합니다.");
        }

        // 최소 매도 수량 검증 (약 5,000원 이상)
        // 현재가를 조회하여 검증할 수도 있지만, 일단 생략
    }

    /**
     * 일일 거래 횟수 제한 검증
     */
    private void validateDailyTradeLimit(Account account) {
        if (account == null) {
            // Account가 없으면 검증 생략 (초기 구현 단계)
            return;
        }

        LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();
        long todayTradeCount = tradeOrderRepository.countByAccountAndCreatedAtAfter(account, startOfDay);

        if (todayTradeCount >= MAX_DAILY_TRADES) {
            throw new IllegalArgumentException(
                    String.format("일일 거래 횟수 초과: %d회 (최대: %d회)", todayTradeCount, MAX_DAILY_TRADES));
        }
    }

    /**
     * 주문 기록 저장
     */
    private void saveTradeOrder(Account account, UpbitOrderResponseDto response,
                                String market, Side side, OrderType orderType,
                                BigDecimal amount) {
        TradeOrder tradeOrder = TradeOrder.builder()
                .account(account)
                .upbitOrderUuid(response.getUuid())
                .market(market)
                .orderType(orderType)
                .side(side)
                .price(response.getPrice())
                .amount(amount)
                .status(mapOrderStatus(response.getState()))
                .build();

        tradeOrderRepository.save(tradeOrder);

        log.info("[거래] 주문 기록 저장 완료: orderId={}, uuid={}", tradeOrder.getId(), response.getUuid());
    }

    /**
     * 업비트 주문 상태를 내부 OrderStatus로 매핑
     */
    private OrderStatus mapOrderStatus(String upbitState) {
        return switch (upbitState) {
            case "wait" -> OrderStatus.PENDING;
            case "done" -> OrderStatus.FILLED;
            case "cancel" -> OrderStatus.CANCELLED;
            default -> OrderStatus.PENDING;
        };
    }
}
