package com.coinreaders.engine.application.backtest;

import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestRequest;
import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestResponse;
import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestResponse.TradeDetail;
import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestResponse.ExitEvent;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Take Profit / Stop Loss 백테스팅 서비스
 * - 1분봉 데이터를 활용한 정밀 매매 시뮬레이션
 * - Kelly Criterion × Confidence 포지션 사이징
 * - 분할 청산 전략 (Profit Ladder + Time-Decay)
 * - 8일 보유 기간 동안 TP/SL 추적
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TakeProfitStopLossBacktestService {

    private final HistoricalAiPredictionRepository aiPredictionRepository;
    private final HistoricalMinuteOhlcvRepository minuteOhlcvRepository;

    private static final String MARKET = "KRW-ETH";
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005"); // 0.05% (편도)
    private static final int SCALE = 8; // 계산 정밀도

    // Profit Ladder 레벨 (수익률 기준)
    private static final BigDecimal PROFIT_LEVEL_1 = new BigDecimal("0.05"); // 5%
    private static final BigDecimal PROFIT_LEVEL_2 = new BigDecimal("0.10"); // 10%
    private static final BigDecimal PROFIT_LEVEL_3 = new BigDecimal("0.20"); // 20%

    // Profit Ladder 청산 비율
    private static final BigDecimal EXIT_RATIO_1 = new BigDecimal("0.30"); // 30%
    private static final BigDecimal EXIT_RATIO_2 = new BigDecimal("0.30"); // 30%
    private static final BigDecimal EXIT_RATIO_3 = new BigDecimal("0.40"); // 40%

    // Time-Decay 청산 (보유 일수 기준)
    private static final int TIME_DECAY_DAY_1 = 6; // 6일차
    private static final int TIME_DECAY_DAY_2 = 7; // 7일차
    private static final BigDecimal TIME_DECAY_RATIO_1 = new BigDecimal("0.20"); // 20%
    private static final BigDecimal TIME_DECAY_RATIO_2 = new BigDecimal("0.40"); // 40%

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
                    prediction, capital, tradeNumber, request.getHoldingPeriodDays()
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
     * 개별 거래 시뮬레이션 (분할 청산 지원)
     */
    private Optional<TradeDetail> simulateTrade(
        HistoricalAiPrediction prediction,
        BigDecimal currentCapital,
        int tradeNumber,
        int holdingPeriodDays
    ) {
        LocalDate entryDate = prediction.getPredictionDate();

        // 1. 진입 시각: 당일 오전 9시 첫 1분봉
        LocalDateTime entryTime = entryDate.atTime(9, 0);
        Optional<HistoricalMinuteOhlcv> entryCandle = minuteOhlcvRepository
            .findFirstByMarketAndCandleDateTimeKstGreaterThanEqualOrderByCandleDateTimeKstAsc(MARKET, entryTime);

        if (entryCandle.isEmpty()) {
            log.warn("진입 시각 1분봉 없음: date={}", entryDate);
            return Optional.empty();
        }

        BigDecimal entryPrice = entryCandle.get().getOpeningPrice();
        LocalDateTime actualEntryTime = entryCandle.get().getCandleDateTimeKst();

        // 2. 포지션 사이징 (Kelly Criterion × Confidence)
        BigDecimal takeProfitPrice = prediction.getTakeProfitPrice();
        BigDecimal stopLossPrice = prediction.getStopLossPrice();
        BigDecimal investmentRatio = calculateKellyPosition(
            entryPrice, takeProfitPrice, stopLossPrice,
            prediction.getPredProbaUp(), prediction.getConfidence()
        );

        if (investmentRatio.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("투자 비중 0 이하, 거래 제외: ratio={}", investmentRatio);
            return Optional.empty();
        }

        BigDecimal initialPositionSize = currentCapital.multiply(investmentRatio)
            .setScale(2, RoundingMode.DOWN);

        if (initialPositionSize.compareTo(BigDecimal.ONE) < 0) {
            log.debug("포지션 크기가 너무 작음 (< 1원), 거래 제외");
            return Optional.empty();
        }

        // 3. 진입 수수료 차감
        BigDecimal entryFee = initialPositionSize.multiply(FEE_RATE).setScale(2, RoundingMode.UP);
        BigDecimal entryAmount = initialPositionSize.subtract(entryFee);
        BigDecimal totalQuantity = entryAmount.divide(entryPrice, SCALE, RoundingMode.DOWN);

        // 4. 분할 청산 추적 변수
        List<ExitEvent> exitEvents = new ArrayList<>();
        BigDecimal remainingQuantity = totalQuantity; // 남은 수량
        BigDecimal totalProfit = BigDecimal.ZERO; // 누적 손익
        BigDecimal totalExitAmount = BigDecimal.ZERO; // 누적 청산 금액 (가중 평균 계산용)

        // Profit Ladder 발동 여부 추적
        boolean profitLevel1Triggered = false;
        boolean profitLevel2Triggered = false;
        boolean profitLevel3Triggered = false;

        // Time-Decay 발동 여부 추적
        boolean timeDecay1Triggered = false;
        boolean timeDecay2Triggered = false;

        // 5. 8일 동안 1분봉 추적
        LocalDateTime exitCheckStart = actualEntryTime.plusMinutes(1);
        LocalDateTime exitCheckEnd = actualEntryTime.plusDays(holdingPeriodDays);

        HistoricalMinuteOhlcv lastCandle = null;

        try (Stream<HistoricalMinuteOhlcv> candleStream = minuteOhlcvRepository
                .streamByMarketAndDateTimeRange(MARKET, exitCheckStart, exitCheckEnd)) {

            var iterator = candleStream.iterator();
            boolean hasData = false;

            while (iterator.hasNext() && remainingQuantity.compareTo(BigDecimal.ZERO) > 0) {
                HistoricalMinuteOhlcv candle = iterator.next();
                hasData = true;
                lastCandle = candle;

                BigDecimal currentPrice = candle.getTradePrice();
                long minutesHeld = ChronoUnit.MINUTES.between(actualEntryTime, candle.getCandleDateTimeKst());
                int daysHeld = (int) (minutesHeld / 1440);

                // 현재 수익률 계산 (남은 포지션 기준)
                BigDecimal unrealizedValue = remainingQuantity.multiply(currentPrice);
                BigDecimal remainingInvestment = remainingQuantity.multiply(entryPrice);
                BigDecimal unrealizedReturn = unrealizedValue.subtract(remainingInvestment)
                    .divide(remainingInvestment, SCALE, RoundingMode.HALF_UP);

                // ===== 청산 우선순위 =====

                // 1. Stop Loss 체크 (최우선 - 저가 기준)
                if (candle.getLowPrice().compareTo(stopLossPrice) <= 0) {
                    BigDecimal exitQty = remainingQuantity;
                    ExitEvent exitEvent = createExitEvent(
                        candle.getCandleDateTimeKst(), stopLossPrice, exitQty, totalQuantity,
                        entryPrice, "STOP_LOSS", "SL Hit"
                    );
                    exitEvents.add(exitEvent);
                    totalProfit = totalProfit.add(exitEvent.getProfit());
                    totalExitAmount = totalExitAmount.add(exitEvent.getExitPrice().multiply(exitQty));
                    remainingQuantity = BigDecimal.ZERO;
                    break;
                }

                // 2. Take Profit 체크 (고우선 - 고가 기준)
                if (candle.getHighPrice().compareTo(takeProfitPrice) >= 0) {
                    BigDecimal exitQty = remainingQuantity;
                    ExitEvent exitEvent = createExitEvent(
                        candle.getCandleDateTimeKst(), takeProfitPrice, exitQty, totalQuantity,
                        entryPrice, "TAKE_PROFIT", "TP Hit"
                    );
                    exitEvents.add(exitEvent);
                    totalProfit = totalProfit.add(exitEvent.getProfit());
                    totalExitAmount = totalExitAmount.add(exitEvent.getExitPrice().multiply(exitQty));
                    remainingQuantity = BigDecimal.ZERO;
                    break;
                }

                // 3. Profit Ladder (수익률 기준 - 종가)
                if (!profitLevel3Triggered && unrealizedReturn.compareTo(PROFIT_LEVEL_3) >= 0) {
                    BigDecimal exitRatio = EXIT_RATIO_3; // 40%
                    BigDecimal exitQty = totalQuantity.multiply(exitRatio).setScale(SCALE, RoundingMode.DOWN);
                    if (exitQty.compareTo(remainingQuantity) > 0) exitQty = remainingQuantity;

                    ExitEvent exitEvent = createExitEvent(
                        candle.getCandleDateTimeKst(), currentPrice, exitQty, totalQuantity,
                        entryPrice, "PROFIT_LADDER", "Return >= 20%"
                    );
                    exitEvents.add(exitEvent);
                    totalProfit = totalProfit.add(exitEvent.getProfit());
                    totalExitAmount = totalExitAmount.add(exitEvent.getExitPrice().multiply(exitQty));
                    remainingQuantity = remainingQuantity.subtract(exitQty);
                    profitLevel3Triggered = true;
                    profitLevel2Triggered = true; // 하위 레벨도 자동 발동
                    profitLevel1Triggered = true;
                } else if (!profitLevel2Triggered && unrealizedReturn.compareTo(PROFIT_LEVEL_2) >= 0) {
                    BigDecimal exitRatio = EXIT_RATIO_2; // 30%
                    BigDecimal exitQty = totalQuantity.multiply(exitRatio).setScale(SCALE, RoundingMode.DOWN);
                    if (exitQty.compareTo(remainingQuantity) > 0) exitQty = remainingQuantity;

                    ExitEvent exitEvent = createExitEvent(
                        candle.getCandleDateTimeKst(), currentPrice, exitQty, totalQuantity,
                        entryPrice, "PROFIT_LADDER", "Return >= 10%"
                    );
                    exitEvents.add(exitEvent);
                    totalProfit = totalProfit.add(exitEvent.getProfit());
                    totalExitAmount = totalExitAmount.add(exitEvent.getExitPrice().multiply(exitQty));
                    remainingQuantity = remainingQuantity.subtract(exitQty);
                    profitLevel2Triggered = true;
                    profitLevel1Triggered = true; // 하위 레벨도 자동 발동
                } else if (!profitLevel1Triggered && unrealizedReturn.compareTo(PROFIT_LEVEL_1) >= 0) {
                    BigDecimal exitRatio = EXIT_RATIO_1; // 30%
                    BigDecimal exitQty = totalQuantity.multiply(exitRatio).setScale(SCALE, RoundingMode.DOWN);
                    if (exitQty.compareTo(remainingQuantity) > 0) exitQty = remainingQuantity;

                    ExitEvent exitEvent = createExitEvent(
                        candle.getCandleDateTimeKst(), currentPrice, exitQty, totalQuantity,
                        entryPrice, "PROFIT_LADDER", "Return >= 5%"
                    );
                    exitEvents.add(exitEvent);
                    totalProfit = totalProfit.add(exitEvent.getProfit());
                    totalExitAmount = totalExitAmount.add(exitEvent.getExitPrice().multiply(exitQty));
                    remainingQuantity = remainingQuantity.subtract(exitQty);
                    profitLevel1Triggered = true;
                }

                // 4. Time-Decay (보유 일수 기준 - 종가)
                if (!timeDecay2Triggered && daysHeld >= TIME_DECAY_DAY_2) {
                    BigDecimal exitRatio = TIME_DECAY_RATIO_2; // 40%
                    BigDecimal exitQty = totalQuantity.multiply(exitRatio).setScale(SCALE, RoundingMode.DOWN);
                    if (exitQty.compareTo(remainingQuantity) > 0) exitQty = remainingQuantity;

                    ExitEvent exitEvent = createExitEvent(
                        candle.getCandleDateTimeKst(), currentPrice, exitQty, totalQuantity,
                        entryPrice, "TIME_DECAY", "Day 7"
                    );
                    exitEvents.add(exitEvent);
                    totalProfit = totalProfit.add(exitEvent.getProfit());
                    totalExitAmount = totalExitAmount.add(exitEvent.getExitPrice().multiply(exitQty));
                    remainingQuantity = remainingQuantity.subtract(exitQty);
                    timeDecay2Triggered = true;
                    timeDecay1Triggered = true; // 하위 레벨도 자동 발동
                } else if (!timeDecay1Triggered && daysHeld >= TIME_DECAY_DAY_1) {
                    BigDecimal exitRatio = TIME_DECAY_RATIO_1; // 20%
                    BigDecimal exitQty = totalQuantity.multiply(exitRatio).setScale(SCALE, RoundingMode.DOWN);
                    if (exitQty.compareTo(remainingQuantity) > 0) exitQty = remainingQuantity;

                    ExitEvent exitEvent = createExitEvent(
                        candle.getCandleDateTimeKst(), currentPrice, exitQty, totalQuantity,
                        entryPrice, "TIME_DECAY", "Day 6"
                    );
                    exitEvents.add(exitEvent);
                    totalProfit = totalProfit.add(exitEvent.getProfit());
                    totalExitAmount = totalExitAmount.add(exitEvent.getExitPrice().multiply(exitQty));
                    remainingQuantity = remainingQuantity.subtract(exitQty);
                    timeDecay1Triggered = true;
                }
            }

            if (!hasData) {
                log.warn("추적 기간 1분봉 데이터 없음: entry={}", actualEntryTime);
                return Optional.empty();
            }
        }

        // 6. Timeout 처리 (남은 포지션 전량 청산)
        if (remainingQuantity.compareTo(BigDecimal.ZERO) > 0 && lastCandle != null) {
            BigDecimal timeoutPrice = lastCandle.getTradePrice();
            ExitEvent exitEvent = createExitEvent(
                lastCandle.getCandleDateTimeKst(), timeoutPrice, remainingQuantity, totalQuantity,
                entryPrice, "TIMEOUT", String.format("Day %d Expired", holdingPeriodDays)
            );
            exitEvents.add(exitEvent);
            totalProfit = totalProfit.add(exitEvent.getProfit());
            totalExitAmount = totalExitAmount.add(exitEvent.getExitPrice().multiply(remainingQuantity));
        }

        // 7. 최종 통계 계산
        BigDecimal netProfit = totalProfit.subtract(entryFee); // 진입 수수료 차감
        BigDecimal totalReturnPct = netProfit.divide(initialPositionSize, 4, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));
        BigDecimal capitalAfter = currentCapital.add(netProfit);

        // 가중 평균 청산가 계산
        BigDecimal weightedAvgExitPrice = totalExitAmount.divide(totalQuantity, 2, RoundingMode.HALF_UP);

        // 평균 보유 기간 계산
        BigDecimal avgHoldingDays = BigDecimal.ZERO;
        if (!exitEvents.isEmpty()) {
            long totalMinutes = exitEvents.stream()
                .mapToLong(e -> ChronoUnit.MINUTES.between(actualEntryTime, e.getExitDateTime()))
                .sum();
            avgHoldingDays = BigDecimal.valueOf(totalMinutes)
                .divide(new BigDecimal(exitEvents.size()), 2, RoundingMode.HALF_UP)
                .divide(new BigDecimal("1440"), 2, RoundingMode.HALF_UP);
        }

        // 최종 청산 이유 (마지막 청산 이벤트)
        String finalExitReason = exitEvents.isEmpty() ? "UNKNOWN" :
            exitEvents.get(exitEvents.size() - 1).getExitReason();

        // 8. TradeDetail 생성
        TradeDetail trade = TradeDetail.builder()
            .tradeNumber(tradeNumber)
            .entryDate(entryDate)
            .entryDateTime(actualEntryTime)
            .entryPrice(entryPrice)
            .exitDate(exitEvents.isEmpty() ? null : exitEvents.get(exitEvents.size() - 1).getExitDateTime().toLocalDate())
            .exitDateTime(exitEvents.isEmpty() ? null : exitEvents.get(exitEvents.size() - 1).getExitDateTime())
            .exitPrice(weightedAvgExitPrice)
            .takeProfitPrice(takeProfitPrice)
            .stopLossPrice(stopLossPrice)
            .positionSize(initialPositionSize)
            .investmentRatio(investmentRatio)
            .profit(netProfit)
            .returnPct(totalReturnPct)
            .exitReason(finalExitReason)
            .holdingDays(avgHoldingDays)
            .predProbaUp(prediction.getPredProbaUp())
            .confidence(prediction.getConfidence())
            .capitalAfter(capitalAfter)
            .exitEvents(exitEvents)
            .build();

        return Optional.of(trade);
    }

    /**
     * 청산 이벤트 생성 헬퍼 메서드
     */
    private ExitEvent createExitEvent(
        LocalDateTime exitDateTime,
        BigDecimal exitPrice,
        BigDecimal exitQty,
        BigDecimal totalQty,
        BigDecimal entryPrice,
        String exitReason,
        String triggerCondition
    ) {
        BigDecimal exitRatio = exitQty.divide(totalQty, 4, RoundingMode.HALF_UP);
        BigDecimal exitProceeds = exitQty.multiply(exitPrice).setScale(2, RoundingMode.DOWN);
        BigDecimal exitFee = exitProceeds.multiply(FEE_RATE).setScale(2, RoundingMode.UP);
        BigDecimal netProceeds = exitProceeds.subtract(exitFee);
        BigDecimal cost = exitQty.multiply(entryPrice).setScale(2, RoundingMode.DOWN);
        BigDecimal profit = netProceeds.subtract(cost);
        BigDecimal returnPct = profit.divide(cost, 4, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));

        return ExitEvent.builder()
            .exitDateTime(exitDateTime)
            .exitPrice(exitPrice)
            .exitRatio(exitRatio)
            .exitAmount(netProceeds)
            .profit(profit)
            .returnPct(returnPct)
            .exitReason(exitReason)
            .triggerCondition(triggerCondition)
            .build();
    }

    /**
     * Kelly Criterion × Confidence 포지션 사이징
     * Formula: ((R × W - (1-W)) / R) × confidence
     * - R: Risk-Reward Ratio = (TP - entry) / (entry - SL)
     * - W: Win Probability = pred_proba_up
     * - Result: 0 ~ 1 (0% ~ 100%)
     */
    private BigDecimal calculateKellyPosition(
        BigDecimal entryPrice,
        BigDecimal takeProfitPrice,
        BigDecimal stopLossPrice,
        BigDecimal predProbaUp,
        BigDecimal confidence
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

        // Kelly × Confidence
        BigDecimal finalRatio = kellyFraction.multiply(confidence);

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

        // 청산 이유별 집계
        int takeProfitExits = 0;
        int stopLossExits = 0;
        int timeoutExits = 0;
        int profitLadderExits = 0;
        int timeDecayExits = 0;

        for (TradeDetail trade : tradeHistory) {
            if (trade.getExitEvents() != null) {
                for (ExitEvent event : trade.getExitEvents()) {
                    switch (event.getExitReason()) {
                        case "TAKE_PROFIT" -> takeProfitExits++;
                        case "STOP_LOSS" -> stopLossExits++;
                        case "TIMEOUT" -> timeoutExits++;
                        case "PROFIT_LADDER" -> profitLadderExits++;
                        case "TIME_DECAY" -> timeDecayExits++;
                    }
                }
            }
        }

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

        log.info("청산 통계: TP={}, SL={}, Timeout={}, ProfitLadder={}, TimeDecay={}",
            takeProfitExits, stopLossExits, timeoutExits, profitLadderExits, timeDecayExits);

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
