package com.coinreaders.engine.application.backtest;

import com.coinreaders.engine.application.backtest.dto.PositionSizingStrategy;
import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestRequest;
import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestResponse;
import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestResponse.TradeDetail;
import com.coinreaders.engine.domain.entity.HistoricalAiPrediction;
import com.coinreaders.engine.domain.entity.HistoricalMinuteOhlcv;
import com.coinreaders.engine.domain.entity.HistoricalOhlcv;
import com.coinreaders.engine.domain.repository.HistoricalAiPredictionRepository;
import com.coinreaders.engine.domain.repository.HistoricalMinuteOhlcvRepository;
import com.coinreaders.engine.domain.repository.HistoricalOhlcvRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Take Profit / Stop Loss 백테스팅 서비스
 * - 1분봉 데이터를 활용한 정밀 매매 시뮬레이션
 * - Kelly Criterion × Confidence 포지션 사이징
 * - 8일 보유 기간 동안 TP/SL 추적
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TakeProfitStopLossBacktestService {

    private final HistoricalAiPredictionRepository aiPredictionRepository;
    private final HistoricalMinuteOhlcvRepository minuteOhlcvRepository;
    private final HistoricalOhlcvRepository ohlcvRepository;

    private static final String MARKET = "KRW-ETH";
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005"); // 0.05% (편도)
    private static final BigDecimal TOTAL_FEE_RATE = new BigDecimal("0.001"); // 0.1% (왕복)
    private static final int SCALE = 8; // 계산 정밀도

    /**
     * 백테스팅 실행
     */
    @Transactional(readOnly = true)
    public TakeProfitStopLossBacktestResponse runBacktest(TakeProfitStopLossBacktestRequest request) {
        log.info("=== 백테스팅 시작: Model={}, Fold={} ===", request.getModelName(), request.getFoldNumber());

        // 1. AI 예측 데이터 조회 (특정 모델 + Fold)
        List<HistoricalAiPrediction> predictions = aiPredictionRepository
            .findByMarketAndFoldNumberAndModelNameOrderByPredictionDateAsc(
                MARKET, request.getFoldNumber(), request.getModelName()
            );

        if (predictions.isEmpty()) {
            log.warn("예측 데이터가 없습니다: Model={}, Fold={}", request.getModelName(), request.getFoldNumber());
            return createEmptyResponse(request);
        }

        // 2. 임계값 필터링 (pred_proba_up >= threshold)
        List<HistoricalAiPrediction> filteredPredictions = predictions.stream()
            .filter(p -> p.getPredProbaUp().compareTo(request.getPredProbaThreshold()) >= 0)
            .toList();

        log.info("전체 {}건 중 임계값({}) 이상: {}건",
            predictions.size(), request.getPredProbaThreshold(), filteredPredictions.size());

        // 3. 각 예측에 대해 거래 시뮬레이션 (포지션 오버랩 방지)
        List<TradeDetail> tradeHistory = new ArrayList<>();
        BigDecimal capital = request.getInitialCapital();
        int tradeNumber = 1;
        LocalDateTime lastExitTime = null; // 마지막 청산 시간 추적
        int skippedDueToPosition = 0; // 포지션 보유로 인해 건너뛴 거래 수

        for (HistoricalAiPrediction prediction : filteredPredictions) {
            LocalDateTime predictedEntryTime = prediction.getPredictionDate().atTime(9, 0);

            // 아직 포지션이 열려있으면 스킵 (현재 시간이 마지막 청산 시간보다 이전)
            if (lastExitTime != null && predictedEntryTime.isBefore(lastExitTime)) {
                skippedDueToPosition++;
                log.debug("포지션 보유 중이므로 거래 건너뜀: predictedDate={}, lastExitTime={}",
                    prediction.getPredictionDate(), lastExitTime);
                continue;
            }

            try {
                Optional<TradeDetail> tradeOpt = simulateTrade(
                    prediction, capital, tradeNumber, request.getHoldingPeriodDays(),
                    request.getPositionSizingStrategy()
                );

                if (tradeOpt.isPresent()) {
                    TradeDetail trade = tradeOpt.get();
                    tradeHistory.add(trade);
                    capital = trade.getCapitalAfter(); // 자본 업데이트
                    lastExitTime = trade.getExitDateTime(); // 청산 시간 업데이트
                    tradeNumber++;
                }
            } catch (Exception e) {
                log.warn("거래 시뮬레이션 실패: date={}, error={}", prediction.getPredictionDate(), e.getMessage());
            }
        }

        if (skippedDueToPosition > 0) {
            log.info("포지션 보유로 인해 건너뛴 신호: {}건", skippedDueToPosition);
        }

        // 수익률 계산
        BigDecimal totalReturn = capital.subtract(request.getInitialCapital());
        BigDecimal totalReturnPct = totalReturn.divide(request.getInitialCapital(), 4, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));

        log.info("=== 백테스팅 완료: Model={}, Fold={} ===", request.getModelName(), request.getFoldNumber());
        log.info("총 거래: {}건", tradeHistory.size());
        log.info("초기 자본: {}원", request.getInitialCapital());
        log.info("최종 자본: {}원", capital);
        log.info("수익: {}원 ({}%)", totalReturn, totalReturnPct);

        // 4. 통계 계산 및 응답 생성
        return buildResponse(request, predictions, tradeHistory, capital);
    }

    /**
     * 개별 거래 시뮬레이션
     */
    private Optional<TradeDetail> simulateTrade(
        HistoricalAiPrediction prediction,
        BigDecimal currentCapital,
        int tradeNumber,
        int holdingPeriodDays,
        PositionSizingStrategy positionSizingStrategy
    ) {
        LocalDate entryDate = prediction.getPredictionDate();

        // 1. 진입 시각: 당일 오전 9시 첫 1분봉 (또는 그 이후 첫 1분봉)
        LocalDateTime entryTime = entryDate.atTime(9, 0);
        Optional<HistoricalMinuteOhlcv> entryCandle = minuteOhlcvRepository
            .findFirstByMarketAndCandleDateTimeKstGreaterThanEqualOrderByCandleDateTimeKstAsc(MARKET, entryTime);

        if (entryCandle.isEmpty()) {
            log.warn("진입 시각 1분봉 없음: date={}", entryDate);
            return Optional.empty();
        }

        BigDecimal entryPrice = entryCandle.get().getOpeningPrice();
        LocalDateTime actualEntryTime = entryCandle.get().getCandleDateTimeKst();

        // 2. 포지션 사이징 (선택된 전략에 따라)
        BigDecimal takeProfitPrice = prediction.getTakeProfitPrice();
        BigDecimal stopLossPrice = prediction.getStopLossPrice();
        BigDecimal investmentRatio = calculatePositionSize(
            entryPrice, takeProfitPrice, stopLossPrice,
            prediction.getPredProbaUp(), prediction.getConfidence(),
            positionSizingStrategy
        );

        if (investmentRatio.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("투자 비중 0 이하, 거래 제외: ratio={}", investmentRatio);
            return Optional.empty();
        }

        BigDecimal positionSize = currentCapital.multiply(investmentRatio)
            .setScale(2, RoundingMode.DOWN);

        if (positionSize.compareTo(BigDecimal.ONE) < 0) {
            log.debug("포지션 크기가 너무 작음 (< 1원), 거래 제외");
            return Optional.empty();
        }

        // 3. 진입 수수료 차감
        BigDecimal entryFee = positionSize.multiply(FEE_RATE).setScale(2, RoundingMode.UP);
        BigDecimal entryAmount = positionSize.subtract(entryFee);
        BigDecimal quantity = entryAmount.divide(entryPrice, SCALE, RoundingMode.DOWN);

        // 4. 보유 기간 동안 1분봉 추적 (Stream 방식으로 메모리 최적화)
        // 진입 봉부터 체크 (진입 봉에서도 TP/SL 도달 가능)
        LocalDateTime exitCheckStart = actualEntryTime;
        LocalDateTime exitCheckEnd = actualEntryTime.plusDays(holdingPeriodDays);

        // 5. TP/SL 체크 (Stream 사용)
        String exitReason = null;
        BigDecimal exitPrice = null;
        LocalDateTime exitTime = null;
        HistoricalMinuteOhlcv lastCandle = null;

        try (Stream<HistoricalMinuteOhlcv> candleStream = minuteOhlcvRepository
                .streamByMarketAndDateTimeRange(MARKET, exitCheckStart, exitCheckEnd)) {

            // Iterator를 사용하여 순차 처리
            var iterator = candleStream.iterator();
            boolean hasData = false;

            while (iterator.hasNext()) {
                HistoricalMinuteOhlcv candle = iterator.next();
                hasData = true;
                lastCandle = candle;

                boolean tpReached = candle.getHighPrice().compareTo(takeProfitPrice) >= 0;
                boolean slReached = candle.getLowPrice().compareTo(stopLossPrice) <= 0;

                // 진입 봉에서 TP/SL 둘 다 도달한 경우: 종가 기준으로 판단
                boolean isEntryCandle = candle.getCandleDateTimeKst().equals(actualEntryTime);
                if (isEntryCandle && tpReached && slReached) {
                    BigDecimal closePrice = candle.getTradePrice();

                    // 양봉이면 TP 먼저, 음봉이면 SL 먼저 도달 가능성
                    if (closePrice.compareTo(entryPrice) >= 0) {
                        exitReason = "TAKE_PROFIT";
                        exitPrice = takeProfitPrice;
                    } else {
                        exitReason = "STOP_LOSS";
                        exitPrice = stopLossPrice;
                    }
                    exitTime = candle.getCandleDateTimeKst();
                    break;
                }

                // 일반 봉에서 TP/SL 둘 다 도달한 경우
                if (tpReached && slReached) {
                    BigDecimal closePrice = candle.getTradePrice();
                    BigDecimal openPrice = candle.getOpeningPrice();

                    if (closePrice.compareTo(openPrice) >= 0) {
                        exitReason = "TAKE_PROFIT";
                        exitPrice = takeProfitPrice;
                    } else {
                        exitReason = "STOP_LOSS";
                        exitPrice = stopLossPrice;
                    }
                    exitTime = candle.getCandleDateTimeKst();
                    break;
                }

                // TP만 도달
                if (tpReached) {
                    exitReason = "TAKE_PROFIT";
                    exitPrice = takeProfitPrice;
                    exitTime = candle.getCandleDateTimeKst();
                    break;
                }

                // SL만 도달
                if (slReached) {
                    exitReason = "STOP_LOSS";
                    exitPrice = stopLossPrice;
                    exitTime = candle.getCandleDateTimeKst();
                    break;
                }
            }

            if (!hasData) {
                log.warn("추적 기간 1분봉 데이터 없음: entry={}", actualEntryTime);
                return Optional.empty();
            }
        }

        // 6. Timeout 처리 (8일 내 TP/SL 미도달)
        if (exitReason == null && lastCandle != null) {
            exitReason = "TIMEOUT";
            exitPrice = lastCandle.getTradePrice(); // 종가
            exitTime = lastCandle.getCandleDateTimeKst();
        }

        // 7. 매도 및 수익 계산
        BigDecimal exitProceeds = quantity.multiply(exitPrice).setScale(2, RoundingMode.DOWN);
        BigDecimal exitFee = exitProceeds.multiply(FEE_RATE).setScale(2, RoundingMode.UP);
        BigDecimal finalAmount = exitProceeds.subtract(exitFee);
        BigDecimal profit = finalAmount.subtract(positionSize);
        BigDecimal returnPct = profit.divide(positionSize, 4, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));
        BigDecimal capitalAfter = currentCapital.add(profit);

        // 8. 보유 기간 계산
        long holdingMinutes = ChronoUnit.MINUTES.between(actualEntryTime, exitTime);
        BigDecimal holdingDays = BigDecimal.valueOf(holdingMinutes)
            .divide(new BigDecimal("1440"), 2, RoundingMode.HALF_UP); // 1440분 = 1일

        // 9. TradeDetail 생성
        TradeDetail trade = TradeDetail.builder()
            .tradeNumber(tradeNumber)
            .entryDate(entryDate)
            .entryDateTime(actualEntryTime)
            .entryPrice(entryPrice)
            .exitDate(exitTime.toLocalDate())
            .exitDateTime(exitTime)
            .exitPrice(exitPrice)
            .takeProfitPrice(takeProfitPrice)
            .stopLossPrice(stopLossPrice)
            .positionSize(positionSize)
            .investmentRatio(investmentRatio)
            .profit(profit)
            .returnPct(returnPct)
            .exitReason(exitReason)
            .holdingDays(holdingDays)
            .predProbaUp(prediction.getPredProbaUp())
            .confidence(prediction.getConfidence())
            .capitalAfter(capitalAfter)
            .build();

        return Optional.of(trade);
    }

    /**
     * Pure Kelly Criterion 계산 (기본)
     * Formula: (R × W - (1-W)) / R
     * - R: Risk-Reward Ratio = (TP - entry) / (entry - SL)
     * - W: Win Probability = pred_proba_up
     * - Result: 0 ~ 1 (0% ~ 100%)
     */
    private BigDecimal calculatePureKelly(
        BigDecimal entryPrice,
        BigDecimal takeProfitPrice,
        BigDecimal stopLossPrice,
        BigDecimal predProbaUp
    ) {
        // Risk-Reward Ratio
        BigDecimal upside = takeProfitPrice.subtract(entryPrice);
        BigDecimal downside = entryPrice.subtract(stopLossPrice);

        if (downside.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Stop Loss가 진입가보다 높음: entry={}, SL={}", entryPrice, stopLossPrice);
            return BigDecimal.ZERO;
        }

        BigDecimal riskRewardRatio = upside.divide(downside, SCALE, RoundingMode.HALF_UP);

        // Kelly Formula: (R × W - (1-W)) / R
        BigDecimal oneMinus = BigDecimal.ONE.subtract(predProbaUp);
        BigDecimal numerator = riskRewardRatio.multiply(predProbaUp).subtract(oneMinus);
        BigDecimal kellyFraction = numerator.divide(riskRewardRatio, SCALE, RoundingMode.HALF_UP);

        // Clamp to [0, 1]
        if (kellyFraction.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (kellyFraction.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }

        return kellyFraction.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Kelly Criterion × Confidence 포지션 사이징 (기존 방식)
     * Formula: ((R × W - (1-W)) / R) × confidence
     */
    private BigDecimal calculateKellyPosition(
        BigDecimal entryPrice,
        BigDecimal takeProfitPrice,
        BigDecimal stopLossPrice,
        BigDecimal predProbaUp,
        BigDecimal confidence
    ) {
        BigDecimal pureKelly = calculatePureKelly(entryPrice, takeProfitPrice, stopLossPrice, predProbaUp);
        BigDecimal finalRatio = pureKelly.multiply(confidence);

        // Clamp to [0, 1]
        if (finalRatio.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (finalRatio.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }

        return finalRatio.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Conservative Kelly (확률 조정 방식)
     * Formula:
     *   adjusted_proba = pred_proba_up × confidence + 0.5 × (1 - confidence)
     *   position = Kelly(adjusted_proba)
     */
    private BigDecimal calculateConservativeKelly(
        BigDecimal entryPrice,
        BigDecimal takeProfitPrice,
        BigDecimal stopLossPrice,
        BigDecimal predProbaUp,
        BigDecimal confidence
    ) {
        // confidence로 확률 조정 (Bayesian Shrinkage)
        BigDecimal adjustedProba = predProbaUp.multiply(confidence)
            .add(new BigDecimal("0.5").multiply(BigDecimal.ONE.subtract(confidence)));

        // 조정된 확률로 Pure Kelly 계산
        return calculatePureKelly(entryPrice, takeProfitPrice, stopLossPrice, adjustedProba);
    }

    /**
     * Estimation Risk Kelly (Bayesian Variance)
     * Formula:
     *   σ² = pred_proba × (1-pred_proba) / n_effective
     *   n_effective = 1 + (confidence/0.5) × 99
     *   adjustment = 1 - λ × σ²
     *   position = Kelly(pred_proba) × adjustment
     */
    private BigDecimal calculateEstimationRiskKelly(
        BigDecimal entryPrice,
        BigDecimal takeProfitPrice,
        BigDecimal stopLossPrice,
        BigDecimal predProbaUp,
        BigDecimal confidence
    ) {
        BigDecimal MAX_CONFIDENCE = new BigDecimal("0.5");
        BigDecimal RISK_AVERSION = new BigDecimal("2.0"); // λ (람다) 파라미터

        // n_effective 계산 (confidence를 샘플 크기로 해석)
        BigDecimal nEffective = BigDecimal.ONE.add(
            confidence.divide(MAX_CONFIDENCE, SCALE, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("99"))
        );

        // Bayesian variance: σ² = p(1-p) / n
        BigDecimal variance = predProbaUp
            .multiply(BigDecimal.ONE.subtract(predProbaUp))
            .divide(nEffective, SCALE, RoundingMode.HALF_UP);

        // Pure Kelly 계산
        BigDecimal pureKelly = calculatePureKelly(entryPrice, takeProfitPrice, stopLossPrice, predProbaUp);

        // Adjustment factor: 1 - λ × σ²
        BigDecimal adjustment = BigDecimal.ONE.subtract(RISK_AVERSION.multiply(variance));
        adjustment = adjustment.max(BigDecimal.ZERO).min(BigDecimal.ONE);

        // Adjusted Kelly
        BigDecimal finalRatio = pureKelly.multiply(adjustment);

        return finalRatio.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Half Kelly
     * Formula: position = Kelly(pred_proba) × 0.5
     */
    private BigDecimal calculateHalfKelly(
        BigDecimal entryPrice,
        BigDecimal takeProfitPrice,
        BigDecimal stopLossPrice,
        BigDecimal predProbaUp
    ) {
        BigDecimal pureKelly = calculatePureKelly(entryPrice, takeProfitPrice, stopLossPrice, predProbaUp);
        return pureKelly.multiply(new BigDecimal("0.5")).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Fixed 100% (비중 조절 없음)
     * Formula: position = 1.0 (100%)
     */
    private BigDecimal calculateFixed100Percent() {
        return BigDecimal.ONE;
    }

    /**
     * 포지션 사이즈 계산 (전략에 따라 다른 메서드 호출)
     */
    private BigDecimal calculatePositionSize(
        BigDecimal entryPrice,
        BigDecimal takeProfitPrice,
        BigDecimal stopLossPrice,
        BigDecimal predProbaUp,
        BigDecimal confidence,
        PositionSizingStrategy strategy
    ) {
        return switch (strategy) {
            case CONSERVATIVE_KELLY ->
                calculateConservativeKelly(entryPrice, takeProfitPrice, stopLossPrice, predProbaUp, confidence);
            case ESTIMATION_RISK_KELLY ->
                calculateEstimationRiskKelly(entryPrice, takeProfitPrice, stopLossPrice, predProbaUp, confidence);
            case HALF_KELLY ->
                calculateHalfKelly(entryPrice, takeProfitPrice, stopLossPrice, predProbaUp);
            case FIXED_100_PERCENT ->
                calculateFixed100Percent();
            case CURRENT_KELLY_TIMES_CONFIDENCE ->
                calculateKellyPosition(entryPrice, takeProfitPrice, stopLossPrice, predProbaUp, confidence);
        };
    }

    /**
     * 백테스팅 결과 응답 생성
     */
    private TakeProfitStopLossBacktestResponse buildResponse(
        TakeProfitStopLossBacktestRequest request,
        List<HistoricalAiPrediction> allPredictions,
        List<TradeDetail> tradeHistory,
        BigDecimal finalCapital
    ) {
        if (tradeHistory.isEmpty()) {
            return createEmptyResponse(request);
        }

        // 기본 정보
        LocalDate startDate = allPredictions.get(0).getPredictionDate();
        LocalDate endDate = allPredictions.get(allPredictions.size() - 1).getPredictionDate();
        String regime = determineRegime(request.getFoldNumber());

        // 수익률
        BigDecimal totalReturnPct = finalCapital.subtract(request.getInitialCapital())
            .divide(request.getInitialCapital(), 4, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));

        // 거래 통계
        int totalTrades = tradeHistory.size();
        int takeProfitExits = (int) tradeHistory.stream()
            .filter(t -> "TAKE_PROFIT".equals(t.getExitReason())).count();
        int stopLossExits = (int) tradeHistory.stream()
            .filter(t -> "STOP_LOSS".equals(t.getExitReason())).count();
        int timeoutExits = (int) tradeHistory.stream()
            .filter(t -> "TIMEOUT".equals(t.getExitReason())).count();

        long winningTrades = tradeHistory.stream()
            .filter(t -> t.getProfit().compareTo(BigDecimal.ZERO) > 0).count();
        BigDecimal winRate = BigDecimal.valueOf(winningTrades)
            .divide(BigDecimal.valueOf(totalTrades), 4, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));

        BigDecimal avgHoldingDays = tradeHistory.stream()
            .map(TradeDetail::getHoldingDays)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(totalTrades), 2, RoundingMode.HALF_UP);

        // 리스크 지표
        BigDecimal maxDrawdown = calculateMaxDrawdown(tradeHistory, request.getInitialCapital());
        BigDecimal sharpeRatio = calculateSharpeRatio(tradeHistory);
        BigDecimal avgWin = calculateAverageWin(tradeHistory);
        BigDecimal avgLoss = calculateAverageLoss(tradeHistory);
        BigDecimal winLossRatio = avgLoss.compareTo(BigDecimal.ZERO) != 0
            ? avgWin.divide(avgLoss.abs(), 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        return TakeProfitStopLossBacktestResponse.builder()
            .modelName(request.getModelName())
            .foldNumber(request.getFoldNumber())
            .regime(regime)
            .startDate(startDate)
            .endDate(endDate)
            .initialCapital(request.getInitialCapital())
            .finalCapital(finalCapital)
            .totalReturnPct(totalReturnPct)
            .totalTrades(totalTrades)
            .takeProfitExits(takeProfitExits)
            .stopLossExits(stopLossExits)
            .timeoutExits(timeoutExits)
            .winRate(winRate)
            .avgHoldingDays(avgHoldingDays)
            .maxDrawdown(maxDrawdown)
            .sharpeRatio(sharpeRatio)
            .avgWin(avgWin)
            .avgLoss(avgLoss)
            .winLossRatio(winLossRatio)
            .tradeHistory(tradeHistory)
            .build();
    }

    /**
     * MDD (Maximum Drawdown) 계산
     */
    private BigDecimal calculateMaxDrawdown(List<TradeDetail> trades, BigDecimal initialCapital) {
        BigDecimal peak = initialCapital;
        BigDecimal maxDD = BigDecimal.ZERO;

        for (TradeDetail trade : trades) {
            BigDecimal current = trade.getCapitalAfter();
            if (current.compareTo(peak) > 0) {
                peak = current;
            }
            BigDecimal drawdown = peak.subtract(current).divide(peak, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
            if (drawdown.compareTo(maxDD) > 0) {
                maxDD = drawdown;
            }
        }

        return maxDD;
    }

    /**
     * Sharpe Ratio 계산 (간이 버전)
     * = (평균 수익률) / (수익률 표준편차)
     */
    private BigDecimal calculateSharpeRatio(List<TradeDetail> trades) {
        if (trades.size() < 2) {
            return BigDecimal.ZERO;
        }

        // 평균 수익률
        BigDecimal avgReturn = trades.stream()
            .map(TradeDetail::getReturnPct)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(trades.size()), SCALE, RoundingMode.HALF_UP);

        // 표준편차
        BigDecimal variance = trades.stream()
            .map(t -> t.getReturnPct().subtract(avgReturn).pow(2))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(trades.size()), SCALE, RoundingMode.HALF_UP);

        double stdDev = Math.sqrt(variance.doubleValue());
        if (stdDev == 0) {
            return BigDecimal.ZERO;
        }

        return avgReturn.divide(BigDecimal.valueOf(stdDev), 4, RoundingMode.HALF_UP);
    }

    /**
     * 평균 수익 (승리한 거래만)
     */
    private BigDecimal calculateAverageWin(List<TradeDetail> trades) {
        List<BigDecimal> wins = trades.stream()
            .filter(t -> t.getProfit().compareTo(BigDecimal.ZERO) > 0)
            .map(TradeDetail::getProfit)
            .toList();

        if (wins.isEmpty()) {
            return BigDecimal.ZERO;
        }

        return wins.stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(wins.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * 평균 손실 (패배한 거래만)
     */
    private BigDecimal calculateAverageLoss(List<TradeDetail> trades) {
        List<BigDecimal> losses = trades.stream()
            .filter(t -> t.getProfit().compareTo(BigDecimal.ZERO) < 0)
            .map(TradeDetail::getProfit)
            .toList();

        if (losses.isEmpty()) {
            return BigDecimal.ZERO;
        }

        return losses.stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(losses.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * Fold 번호에 따른 시장 체제 추정
     */
    private String determineRegime(Integer foldNumber) {
        // 실제로는 DB에서 조회하거나 날짜 기반으로 판단해야 하지만,
        // 여기서는 간단히 Fold 번호 기반으로 추정
        return switch (foldNumber) {
            case 1, 2 -> "BEAR";
            case 3, 4 -> "SIDEWAYS";
            case 5, 6, 7 -> "BULL";
            case 8 -> "MIXED";
            default -> "UNKNOWN";
        };
    }

    /**
     * 빈 응답 생성 (거래 없음)
     */
    private TakeProfitStopLossBacktestResponse createEmptyResponse(TakeProfitStopLossBacktestRequest request) {
        return TakeProfitStopLossBacktestResponse.builder()
            .modelName(request.getModelName())
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
