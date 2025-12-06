package com.coinreaders.engine.application.backtest;

import com.coinreaders.engine.application.backtest.dto.BuyAndHoldBacktestRequest;
import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestResponse;
import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestResponse.TradeDetail;
import com.coinreaders.engine.domain.entity.HistoricalAiPrediction;
import com.coinreaders.engine.domain.entity.HistoricalMinuteOhlcv;
import com.coinreaders.engine.domain.repository.HistoricalAiPredictionRepository;
import com.coinreaders.engine.domain.repository.HistoricalMinuteOhlcvRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Buy & Hold 백테스팅 서비스
 * - Fold 시작 시점에 전액 매수
 * - Fold 종료 시점에 전액 매도
 * - 단순 보유 전략으로 AI 모델 성능 벤치마크 제공
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyAndHoldBacktestService {

    private final HistoricalAiPredictionRepository aiPredictionRepository;
    private final HistoricalMinuteOhlcvRepository minuteOhlcvRepository;

    private static final String MARKET = "KRW-ETH";
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005"); // 0.05% (편도)
    private static final int SCALE = 8; // 계산 정밀도

    /**
     * Buy & Hold 백테스팅 실행
     */
    @Transactional(readOnly = true)
    public TakeProfitStopLossBacktestResponse runBacktest(BuyAndHoldBacktestRequest request) {
        log.info("=== Buy & Hold 백테스팅 시작: Fold={} ===", request.getFoldNumber());

        // 1. Fold 기간 파악 (AI 예측 데이터 기준)
        List<HistoricalAiPrediction> predictions = aiPredictionRepository
            .findByMarketAndFoldNumberOrderByPredictionDateAsc(
                MARKET, request.getFoldNumber()
            );

        if (predictions.isEmpty()) {
            log.warn("예측 데이터가 없습니다: Fold={}", request.getFoldNumber());
            return createEmptyResponse(request);
        }

        LocalDate startDate = predictions.get(0).getPredictionDate();
        LocalDate endDate = predictions.get(predictions.size() - 1).getPredictionDate();
        String regime = determineRegime(request.getFoldNumber());

        log.info("Fold {} 기간: {} ~ {} ({}일)", request.getFoldNumber(), startDate, endDate,
            java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate));

        // 2. 매수 시점: 시작일 09:00 가격
        LocalDateTime entryTime = startDate.atTime(9, 0);
        HistoricalMinuteOhlcv entryCandle = minuteOhlcvRepository
            .findFirstByMarketAndCandleDateTimeKstGreaterThanEqualOrderByCandleDateTimeKstAsc(MARKET, entryTime)
            .orElse(null);

        if (entryCandle == null) {
            log.warn("매수 시점 1분봉 데이터 없음: {}", entryTime);
            return createEmptyResponse(request);
        }

        BigDecimal entryPrice = entryCandle.getOpeningPrice();

        // 3. 매도 시점: 종료일 23:59 가격 (가장 가까운 시간)
        LocalDateTime exitTime = endDate.atTime(23, 59);
        HistoricalMinuteOhlcv exitCandle = findNearestCandle(endDate);

        if (exitCandle == null) {
            log.warn("매도 시점 1분봉 데이터 없음: {}", exitTime);
            return createEmptyResponse(request);
        }

        BigDecimal exitPrice = exitCandle.getTradePrice();
        LocalDateTime actualExitTime = exitCandle.getCandleDateTimeKst();

        // 4. 손익 계산
        BigDecimal investmentAmount = request.getInitialCapital();

        // 매수 시 수수료 차감
        BigDecimal buyFee = investmentAmount.multiply(FEE_RATE).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal netInvestment = investmentAmount.subtract(buyFee);

        // 매수 수량 (원 단위)
        BigDecimal purchaseAmount = netInvestment.divide(entryPrice, SCALE, RoundingMode.HALF_UP)
            .multiply(entryPrice).setScale(SCALE, RoundingMode.HALF_UP);

        // 매도 시 수익
        BigDecimal saleProceeds = purchaseAmount.multiply(exitPrice).divide(entryPrice, SCALE, RoundingMode.HALF_UP);
        BigDecimal sellFee = saleProceeds.multiply(FEE_RATE).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal netProceeds = saleProceeds.subtract(sellFee);

        // 최종 자본 및 수익
        BigDecimal finalCapital = netProceeds;
        BigDecimal profit = finalCapital.subtract(investmentAmount);
        BigDecimal returnPct = profit.divide(investmentAmount, 4, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));

        log.info("매수: {} 원 @ {} (수수료: {} 원)", investmentAmount, entryPrice, buyFee);
        log.info("매도: {} 원 @ {} (수수료: {} 원)", saleProceeds, exitPrice, sellFee);
        log.info("최종 자본: {} 원 (수익률: {}%)", finalCapital, returnPct);

        // 5. 거래 내역 생성 (단일 거래)
        TradeDetail trade = TradeDetail.builder()
            .tradeNumber(1)
            .entryDate(startDate)
            .entryDateTime(entryTime)
            .entryPrice(entryPrice)
            .exitDate(endDate)
            .exitDateTime(actualExitTime)
            .exitPrice(exitPrice)
            .positionSize(investmentAmount)
            .investmentRatio(BigDecimal.ONE)
            .profit(profit)
            .returnPct(returnPct)
            .exitReason("BUY_AND_HOLD")
            .holdingDays(BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate)))
            .predProbaUp(BigDecimal.ZERO)
            .confidence(BigDecimal.ZERO)
            .capitalAfter(finalCapital)
            .exitEvents(new ArrayList<>())
            .build();

        List<TradeDetail> tradeHistory = List.of(trade);

        // 6. 응답 생성
        return TakeProfitStopLossBacktestResponse.builder()
            .modelName("Buy & Hold")
            .foldNumber(request.getFoldNumber())
            .regime(regime)
            .startDate(startDate)
            .endDate(endDate)
            .initialCapital(request.getInitialCapital())
            .finalCapital(finalCapital)
            .totalReturnPct(returnPct)
            .totalTrades(1)
            .takeProfitExits(returnPct.compareTo(BigDecimal.ZERO) > 0 ? 1 : 0)
            .stopLossExits(returnPct.compareTo(BigDecimal.ZERO) < 0 ? 1 : 0)
            .timeoutExits(0)
            .winRate(returnPct.compareTo(BigDecimal.ZERO) > 0 ? new BigDecimal("100") : BigDecimal.ZERO)
            .avgHoldingDays(BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate)))
            .maxDrawdown(BigDecimal.ZERO) // Buy & Hold는 MDD 계산 안함
            .sharpeRatio(BigDecimal.ZERO)  // Buy & Hold는 Sharpe 계산 안함
            .avgWin(returnPct.compareTo(BigDecimal.ZERO) > 0 ? returnPct : BigDecimal.ZERO)
            .avgLoss(returnPct.compareTo(BigDecimal.ZERO) < 0 ? returnPct : BigDecimal.ZERO)
            .winLossRatio(BigDecimal.ZERO)
            .tradeHistory(tradeHistory)
            .build();
    }

    /**
     * 특정 날짜에 가장 가까운 1분봉 캔들 조회 (가장 마지막 시간부터 역순 검색)
     */
    private HistoricalMinuteOhlcv findNearestCandle(LocalDate date) {
        // 다음날 00:00 직전까지 검색 (역순)
        LocalDateTime endTime = date.plusDays(1).atStartOfDay().minusMinutes(1);
        LocalDateTime startTime = date.atTime(22, 0);

        // 가장 가까운 1분봉 조회 (역순으로 가장 최근 것)
        var candles = minuteOhlcvRepository.findByMarketAndCandleDateTimeKstBetweenOrderByCandleDateTimeKstAsc(
            MARKET, startTime, endTime.plusMinutes(1)
        );

        if (!candles.isEmpty()) {
            return candles.get(candles.size() - 1); // 가장 마지막 캔들 반환
        }

        return null;
    }

    /**
     * Fold 번호에 따른 시장 체제 추정
     */
    private String determineRegime(Integer foldNumber) {
        return switch (foldNumber) {
            case 1, 2 -> "BEAR";
            case 3, 4 -> "SIDEWAYS";
            case 5, 6, 7 -> "BULL";
            case 8 -> "MIXED";
            default -> "UNKNOWN";
        };
    }

    /**
     * 빈 응답 생성 (데이터 없을 때)
     */
    private TakeProfitStopLossBacktestResponse createEmptyResponse(BuyAndHoldBacktestRequest request) {
        return TakeProfitStopLossBacktestResponse.builder()
            .modelName("Buy & Hold")
            .foldNumber(request.getFoldNumber())
            .regime("UNKNOWN")
            .startDate(LocalDate.now())
            .endDate(LocalDate.now())
            .initialCapital(request.getInitialCapital())
            .finalCapital(request.getInitialCapital())
            .totalReturnPct(BigDecimal.ZERO)
            .totalTrades(0)
            .takeProfitExits(0)
            .stopLossExits(0)
            .timeoutExits(0)
            .winRate(BigDecimal.ZERO)
            .avgHoldingDays(BigDecimal.ZERO)
            .maxDrawdown(BigDecimal.ZERO)
            .sharpeRatio(BigDecimal.ZERO)
            .avgWin(BigDecimal.ZERO)
            .avgLoss(BigDecimal.ZERO)
            .winLossRatio(BigDecimal.ZERO)
            .tradeHistory(new ArrayList<>())
            .build();
    }
}
