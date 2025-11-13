package com.coinreaders.engine.application.backtest;

import com.coinreaders.engine.application.backtest.dto.BacktestRequest;
import com.coinreaders.engine.application.backtest.dto.BacktestResponse;
import com.coinreaders.engine.application.backtest.dto.CsvPredictionData;
import com.coinreaders.engine.application.backtest.dto.SequentialBacktestResponse;
import com.coinreaders.engine.application.backtest.dto.ThresholdMode;
import com.coinreaders.engine.application.backtest.dto.ConfidenceColumn;
import com.coinreaders.engine.domain.entity.HistoricalOhlcv;
import com.coinreaders.engine.domain.repository.HistoricalOhlcvRepository;
import com.coinreaders.engine.domain.repository.HistoricalAiPredictionRepository;
import com.coinreaders.engine.domain.entity.HistoricalAiPrediction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestService {

    private final HistoricalOhlcvRepository ohlcvRepository;
    private final HistoricalAiPredictionRepository aiPredictionRepository;

    // 상수 정의 (업비트 기준)
    private static final String MARKET = "KRW-ETH";
    private static final BigDecimal UPBIT_FEE_RATE = new BigDecimal("0.0005"); // 0.05% (편도)
    private static final BigDecimal ROUND_TRIP_FEE = UPBIT_FEE_RATE.multiply(new BigDecimal("2")); // 0.1% (양방향)
    private static final BigDecimal SLIPPAGE = new BigDecimal("0.0005"); // 0.05% (슬리피지)
    private static final BigDecimal TOTAL_COST = ROUND_TRIP_FEE.add(SLIPPAGE); // 0.15%
    private static final int SCALE = 8; // 소수점 자릿수
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /**
     * 백테스팅 실행 (Kelly vs Buy & Hold 비교)
     */
    @Transactional
    public BacktestResponse runBacktest(BacktestRequest request) {
        log.info("백테스팅 시작: Fold={}, InitialCapital={}, ConfidenceThreshold={}",
            request.getFoldNumber(), request.getInitialCapital(), request.getConfidenceThreshold());

        // 1. Fold 설정 조회
        FoldConfig foldConfig = FoldConfig.getFold(request.getFoldNumber());

        // 2. AI 예측 데이터 로드 (DB에서 로드)
        List<CsvPredictionData> allPredictions = loadPredictionsFromDb(request.getFoldNumber());

        // 3. 상승 예측만 먼저 필터링
        List<CsvPredictionData> longOnlyPredictions = allPredictions.stream()
            .filter(p -> p.getPredDirection() == 1)  // 상승 예측만
            .collect(Collectors.toList());

        // 4. 임계값 계산 (컬럼과 모드에 따라 다르게 처리)
        String columnName = request.getConfidenceColumn() == ConfidenceColumn.CONFIDENCE ? "confidence" : "pred_proba_up";
        BigDecimal actualThreshold;

        if (request.getThresholdMode() == ThresholdMode.QUANTILE) {
            // QUANTILE 모드: 백분위수 계산
            actualThreshold = calculateQuantile(longOnlyPredictions, request.getConfidenceThreshold(), request.getConfidenceColumn());
            log.info("QUANTILE 모드 [{}]: {}% 백분위수 = {}", columnName, request.getConfidenceThreshold(), actualThreshold);
        } else {
            // FIXED 모드: 입력값 그대로 사용
            actualThreshold = request.getConfidenceThreshold();
            log.info("FIXED 모드 [{}]: 고정 임계값 = {}", columnName, actualThreshold);
        }

        // 5. 선택한 컬럼으로 필터링
        List<CsvPredictionData> filteredPredictions;
        if (request.getConfidenceColumn() == ConfidenceColumn.CONFIDENCE) {
            filteredPredictions = longOnlyPredictions.stream()
                .filter(p -> p.getConfidence().compareTo(actualThreshold) >= 0)
                .collect(Collectors.toList());
        } else { // PRED_PROBA_UP
            filteredPredictions = longOnlyPredictions.stream()
                .filter(p -> p.getPredProbaUp().compareTo(actualThreshold) >= 0)
                .collect(Collectors.toList());
        }

        log.info("전체 예측: {}건, 상승예측: {}건, 필터링 후 [{}>={}]: {}건",
            allPredictions.size(), longOnlyPredictions.size(), columnName, actualThreshold, filteredPredictions.size());

        if (filteredPredictions.isEmpty()) {
            throw new IllegalStateException("필터링된 예측 데이터가 없습니다. Confidence 기준을 낮춰주세요.");
        }

        // 4. 켈리 비율 계산 (전체 예측 데이터 기반)
        KellyCalculation kellyCalc = calculateKellyFraction(filteredPredictions);
        log.info("켈리 계산 결과: F={}, W={}, R={}", kellyCalc.kellyFraction, kellyCalc.winRate, kellyCalc.winLossRatio);

        // 5. Kelly 전략 백테스팅
        BacktestResponse.KellyStrategyResult kellyResult = runKellyStrategy(
            filteredPredictions,
            request.getInitialCapital(),
            kellyCalc.kellyFraction,
            foldConfig,
            request.getPositionSizePercent()
        );

        // 6. Buy & Hold 백테스팅
        BacktestResponse.BuyHoldResult buyHoldResult = runBuyAndHoldStrategy(
            foldConfig,
            request.getInitialCapital()
        );

        // 7. 결과 비교
        BigDecimal alpha = kellyResult.getTotalReturnPct().subtract(buyHoldResult.getTotalReturnPct());
        String winner = alpha.compareTo(BigDecimal.ZERO) > 0 ? "KELLY" : "BUY_AND_HOLD";

        log.info("백테스팅 완료: Kelly {}%, B&H {}%, Alpha {}%, Winner={}",
            kellyResult.getTotalReturnPct(), buyHoldResult.getTotalReturnPct(), alpha, winner);

        return BacktestResponse.builder()
            .foldNumber(foldConfig.getFoldNumber())
            .regime(foldConfig.getRegime())
            .startDate(foldConfig.getStartDate())
            .endDate(foldConfig.getEndDate())
            .kellyStrategy(kellyResult)
            .buyHoldStrategy(buyHoldResult)
            .alpha(alpha)
            .winner(winner)
            .build();
    }

    /**
     * Fold 1~7 연속 백테스팅
     * 각 fold의 최종 자본이 다음 fold의 초기 자본이 됨
     * Fold 간 1개월 간격은 현금 보유로 처리
     * @param positionSizePercent null이면 Kelly 자동 계산 사용, 값이 있으면 고정 비율 사용 (0~100)
     */
    @Transactional
    public SequentialBacktestResponse runSequentialBacktest(
        Integer startFold,
        Integer endFold,
        BigDecimal initialCapital,
        BigDecimal confidenceThreshold,
        ConfidenceColumn confidenceColumn,
        ThresholdMode thresholdMode,
        BigDecimal positionSizePercent
    ) {
        log.info("연속 백테스팅 시작: Fold {} ~ {}, InitialCapital={}, ConfidenceThreshold={}, Column={}, Mode={}, PositionSizePercent={}",
            startFold, endFold, initialCapital, confidenceThreshold, confidenceColumn, thresholdMode, positionSizePercent);

        BigDecimal kellyCapital = initialCapital;
        BigDecimal buyHoldCapital = initialCapital;
        List<SequentialBacktestResponse.FoldResult> foldResults = new ArrayList<>();
        int totalKellyTrades = 0;
        int totalWins = 0;
        int totalLosses = 0;
        List<BigDecimal> allCapitalHistory = new ArrayList<>();
        List<BigDecimal> allReturns = new ArrayList<>(); // 전체 거래 수익률 (Sharpe 계산용)

        for (int foldNumber = startFold; foldNumber <= endFold; foldNumber++) {
            log.info("연속 백테스팅 - Fold {} 실행 중...", foldNumber);

            // 각 fold 백테스팅 실행
            BacktestRequest request = new BacktestRequest(foldNumber, kellyCapital, confidenceThreshold, confidenceColumn, thresholdMode, positionSizePercent);
            BacktestResponse response = runBacktest(request);

            // Kelly 전략 결과
            BigDecimal kellyFinalCapital = response.getKellyStrategy().getFinalCapital();
            BigDecimal kellyReturnPct = response.getKellyStrategy().getTotalReturnPct();

            // Buy & Hold 전략 결과 (연속 적용)
            // Buy & Hold는 각 fold에서 독립적으로 계산된 수익률을 현재 자본에 적용
            BigDecimal buyHoldReturnPct = response.getBuyHoldStrategy().getTotalReturnPct();
            BigDecimal buyHoldFinalCapital = buyHoldCapital
                .multiply(BigDecimal.ONE.add(buyHoldReturnPct.divide(new BigDecimal("100"), SCALE, ROUNDING)))
                .setScale(SCALE, ROUNDING);

            // Fold 결과 저장
            FoldConfig foldConfig = FoldConfig.getFold(foldNumber);
            foldResults.add(SequentialBacktestResponse.FoldResult.builder()
                .foldNumber(foldNumber)
                .regime(foldConfig.getRegime())
                .dateRange(foldConfig.getStartDate() + " ~ " + foldConfig.getEndDate())
                .kellyInitialCapital(kellyCapital)
                .kellyFinalCapital(kellyFinalCapital)
                .kellyReturnPct(kellyReturnPct)
                .kellyTrades(response.getKellyStrategy().getTotalTrades())
                .kellyWins(response.getKellyStrategy().getWinTrades())
                .kellyLosses(response.getKellyStrategy().getLossTrades())
                .kellyWinRate(response.getKellyStrategy().getWinRate())
                .kellyMdd(response.getKellyStrategy().getMaxDrawdown())
                .buyHoldInitialCapital(buyHoldCapital)
                .buyHoldFinalCapital(buyHoldFinalCapital)
                .buyHoldReturnPct(buyHoldReturnPct)
                .buyHoldMdd(response.getBuyHoldStrategy().getMaxDrawdown())
                .alpha(response.getAlpha())
                .winner(response.getWinner())
                .build());

            // 다음 fold의 초기 자본 업데이트
            kellyCapital = kellyFinalCapital;
            buyHoldCapital = buyHoldFinalCapital;
            totalKellyTrades += response.getKellyStrategy().getTotalTrades();

            // 통계 집계
            totalWins += response.getKellyStrategy().getWinTrades();
            totalLosses += response.getKellyStrategy().getLossTrades();

            // 자본 이력 수집 (첫 fold는 전체, 이후 fold는 첫 값 제외하여 중복 방지)
            List<BigDecimal> foldCapitalHistory = response.getKellyStrategy().getCapitalHistory();
            if (foldCapitalHistory != null && !foldCapitalHistory.isEmpty()) {
                if (allCapitalHistory.isEmpty()) {
                    // 첫 fold: 전체 이력 추가
                    allCapitalHistory.addAll(foldCapitalHistory);
                } else {
                    // 이후 fold: 첫 값(이전 fold의 마지막 값과 동일)을 제외하고 추가
                    allCapitalHistory.addAll(foldCapitalHistory.subList(1, foldCapitalHistory.size()));
                }
            }

            log.info("Fold {} 완료: Kelly={}원, B&H={}원",
                foldNumber, kellyFinalCapital, buyHoldFinalCapital);
        }

        // 전체 수익률 계산 (0원 초기자본 가드)
        BigDecimal kellyTotalReturnPct = initialCapital.compareTo(BigDecimal.ZERO) == 0
            ? BigDecimal.ZERO
            : kellyCapital.divide(initialCapital, SCALE, ROUNDING)
                .subtract(BigDecimal.ONE)
                .multiply(new BigDecimal("100"));

        BigDecimal buyHoldTotalReturnPct = initialCapital.compareTo(BigDecimal.ZERO) == 0
            ? BigDecimal.ZERO
            : buyHoldCapital.divide(initialCapital, SCALE, ROUNDING)
                .subtract(BigDecimal.ONE)
                .multiply(new BigDecimal("100"));

        BigDecimal totalAlpha = kellyTotalReturnPct.subtract(buyHoldTotalReturnPct);

        // 전체 통계 계산
        BigDecimal overallWinRate = totalKellyTrades == 0 ? BigDecimal.ZERO :
            new BigDecimal(totalWins).divide(new BigDecimal(totalKellyTrades), 4, ROUNDING)
                .multiply(new BigDecimal("100"));

        BigDecimal overallMaxDrawdown = calculateMaxDrawdown(allCapitalHistory);

        // Fold별 수익률로 Sharpe Ratio 계산
        List<BigDecimal> foldReturns = foldResults.stream()
            .map(SequentialBacktestResponse.FoldResult::getKellyReturnPct)
            .collect(Collectors.toList());
        BigDecimal overallSharpeRatio = calculateSharpeRatioFromReturns(foldReturns);

        log.info("연속 백테스팅 완료: Kelly {}% ({}원), B&H {}% ({}원), Alpha {}%, 승률 {}%, MDD {}%, Sharpe {}",
            kellyTotalReturnPct, kellyCapital, buyHoldTotalReturnPct, buyHoldCapital, totalAlpha,
            overallWinRate, overallMaxDrawdown, overallSharpeRatio);

        return SequentialBacktestResponse.builder()
            .initialCapital(initialCapital)
            .finalCapital(kellyCapital)
            .totalReturnPct(kellyTotalReturnPct)
            .startFold(startFold)
            .endFold(endFold)
            .kellyComparison(SequentialBacktestResponse.StrategyComparison.builder()
                .initialCapital(initialCapital)
                .finalCapital(kellyCapital)
                .totalReturnPct(kellyTotalReturnPct)
                .totalTrades(totalKellyTrades)
                .totalAlpha(totalAlpha)
                .totalWins(totalWins)
                .totalLosses(totalLosses)
                .overallWinRate(overallWinRate)
                .overallMaxDrawdown(overallMaxDrawdown)
                .overallSharpeRatio(overallSharpeRatio)
                .build())
            .buyHoldComparison(SequentialBacktestResponse.StrategyComparison.builder()
                .initialCapital(initialCapital)
                .finalCapital(buyHoldCapital)
                .totalReturnPct(buyHoldTotalReturnPct)
                .totalTrades(endFold - startFold + 1) // B&H는 각 fold당 1회 거래
                .totalAlpha(BigDecimal.ZERO)
                .build())
            .foldResults(foldResults)
            .build();
    }

    /**
     * 켈리 비율 계산
     * F = W - (1-W) / R
     * W: 승률
     * R: 평균 수익 / 평균 손실 비율
     */
    private KellyCalculation calculateKellyFraction(List<CsvPredictionData> predictions) {
        if (predictions.isEmpty()) {
            return new KellyCalculation(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        // 1. 승/패 구분 (actual_return 기반)
        List<BigDecimal> wins = new ArrayList<>();
        List<BigDecimal> losses = new ArrayList<>();

        for (CsvPredictionData pred : predictions) {
            BigDecimal actualReturn = pred.getActualReturn();

            // 수수료 차감
            BigDecimal netReturn = actualReturn.subtract(TOTAL_COST);

            if (netReturn.compareTo(BigDecimal.ZERO) > 0) {
                wins.add(netReturn);
            } else {
                losses.add(netReturn.abs());
            }
        }

        // 2. 승률 (W)
        BigDecimal winRate = new BigDecimal(wins.size())
            .divide(new BigDecimal(predictions.size()), SCALE, ROUNDING);

        // 3. 평균 수익 / 평균 손실 (R)
        BigDecimal avgWin = wins.isEmpty() ? BigDecimal.ZERO :
            wins.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(wins.size()), SCALE, ROUNDING);

        BigDecimal avgLoss = losses.isEmpty() ? BigDecimal.ZERO :
            losses.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(losses.size()), SCALE, ROUNDING);

        BigDecimal winLossRatio = avgLoss.compareTo(BigDecimal.ZERO) == 0 ?
            BigDecimal.ZERO :
            avgWin.divide(avgLoss, SCALE, ROUNDING);

        // 4. 켈리 공식: F = W - (1-W) / R
        BigDecimal kellyFraction;
        if (winLossRatio.compareTo(BigDecimal.ZERO) == 0) {
            kellyFraction = BigDecimal.ZERO;
        } else {
            BigDecimal oneMinusW = BigDecimal.ONE.subtract(winRate);
            kellyFraction = winRate.subtract(oneMinusW.divide(winLossRatio, SCALE, ROUNDING));
        }

        // 켈리 값이 음수면 0으로 (투자하지 않음)
        // 켈리 값이 1 초과면 1로 제한 (레버리지 없음)
        kellyFraction = kellyFraction.max(BigDecimal.ZERO).min(BigDecimal.ONE);

        log.info("켈리 계산: 승={}, 패={}, 승률={}, 평균승={}, 평균패={}, 손익비={}, 켈리={}",
            wins.size(), losses.size(), winRate, avgWin, avgLoss, winLossRatio, kellyFraction);

        return new KellyCalculation(kellyFraction, winRate, winLossRatio);
    }

    /**
     * Kelly 전략 백테스팅
     * @param positionSizePercent null이면 Kelly 자동 계산 사용, 값이 있으면 고정 비율 사용 (0~100)
     */
    private BacktestResponse.KellyStrategyResult runKellyStrategy(
        List<CsvPredictionData> predictions,
        BigDecimal initialCapital,
        BigDecimal kellyFraction,
        FoldConfig foldConfig,
        BigDecimal positionSizePercent
    ) {
        BigDecimal capital = initialCapital;
        List<BigDecimal> capitalHistory = new ArrayList<>();
        capitalHistory.add(capital);

        List<TradeResult> tradeResults = new ArrayList<>();

        // 포지션 비율 결정: custom % 우선, 없으면 Kelly 사용
        BigDecimal positionFraction = (positionSizePercent != null)
            ? positionSizePercent.divide(new BigDecimal("100"), SCALE, ROUNDING)
            : kellyFraction;

        log.info("포지션 사이즈 전략: {} (Kelly={}, Custom={})",
            positionSizePercent != null ? "고정 " + positionSizePercent + "%" : "Kelly Criterion",
            kellyFraction.multiply(new BigDecimal("100")),
            positionSizePercent);

        for (CsvPredictionData prediction : predictions) {
            LocalDate tradeDate = prediction.getDate();

            // 업비트 OHLCV 데이터 조회
            HistoricalOhlcv ohlcv = ohlcvRepository.findByMarketAndDate(MARKET, tradeDate)
                .orElse(null);

            if (ohlcv == null) {
                log.warn("거래 불가: {} - OHLCV 데이터 없음", tradeDate);
                continue;
            }

            BigDecimal entryPrice = ohlcv.getOpeningPrice(); // 시가
            BigDecimal exitPrice = ohlcv.getTradePrice();     // 종가

            // 포지션 크기 = 자본 × 포지션 비율 (Kelly 또는 사용자 지정)
            BigDecimal positionSize = capital.multiply(positionFraction).setScale(SCALE, ROUNDING);

            if (positionSize.compareTo(BigDecimal.ZERO) <= 0) {
                continue; // 거래 안 함
            }

            // 가격 수익률
            BigDecimal priceReturn = exitPrice.divide(entryPrice, SCALE, ROUNDING)
                .subtract(BigDecimal.ONE);

            // 순 수익률 (수수료 + 슬리피지 차감)
            BigDecimal netReturn = priceReturn.subtract(TOTAL_COST);

            // 손익
            BigDecimal profit = positionSize.multiply(netReturn).setScale(SCALE, ROUNDING);
            capital = capital.add(profit);
            capitalHistory.add(capital);

            // 거래 결과 저장
            tradeResults.add(new TradeResult(
                tradeDate,
                entryPrice,
                exitPrice,
                netReturn.multiply(new BigDecimal("100")), // %로 변환
                profit,
                capital,
                prediction.getPredDirection(),
                prediction.getConfidence(),
                positionSize
            ));
        }

        // 통계 계산
        int totalTrades = tradeResults.size();
        int winTrades = (int) tradeResults.stream()
            .filter(t -> t.profit.compareTo(BigDecimal.ZERO) > 0)
            .count();
        int lossTrades = totalTrades - winTrades;

        BigDecimal winRate = totalTrades == 0 ? BigDecimal.ZERO :
            new BigDecimal(winTrades).divide(new BigDecimal(totalTrades), 4, ROUNDING)
                .multiply(new BigDecimal("100"));

        List<BigDecimal> wins = tradeResults.stream()
            .filter(t -> t.profit.compareTo(BigDecimal.ZERO) > 0)
            .map(t -> t.returnPct)
            .collect(Collectors.toList());

        List<BigDecimal> lossesAbs = tradeResults.stream()
            .filter(t -> t.profit.compareTo(BigDecimal.ZERO) <= 0)
            .map(t -> t.returnPct.abs())
            .collect(Collectors.toList());

        BigDecimal avgWin = wins.isEmpty() ? BigDecimal.ZERO :
            wins.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(wins.size()), 4, ROUNDING);

        BigDecimal avgLoss = lossesAbs.isEmpty() ? BigDecimal.ZERO :
            lossesAbs.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(lossesAbs.size()), 4, ROUNDING);

        BigDecimal winLossRatio = avgLoss.compareTo(BigDecimal.ZERO) == 0 ?
            BigDecimal.ZERO :
            avgWin.divide(avgLoss, 4, ROUNDING);

        // 총 수익률 계산 (0원 초기자본 가드)
        BigDecimal totalReturnPct = initialCapital.compareTo(BigDecimal.ZERO) == 0
            ? BigDecimal.ZERO
            : capital.divide(initialCapital, SCALE, ROUNDING)
                .subtract(BigDecimal.ONE)
                .multiply(new BigDecimal("100"));

        BigDecimal maxDrawdown = calculateMaxDrawdown(capitalHistory);

        // Sharpe Ratio 계산 (거래 수익률 기반)
        BigDecimal sharpeRatio = calculateSharpeRatio(tradeResults);

        return BacktestResponse.KellyStrategyResult.builder()
            .initialCapital(initialCapital)
            .finalCapital(capital)
            .totalReturnPct(totalReturnPct)
            .totalTrades(totalTrades)
            .winTrades(winTrades)
            .lossTrades(lossTrades)
            .winRate(winRate)
            .avgWin(avgWin)
            .avgLoss(avgLoss)
            .winLossRatio(winLossRatio)
            .kellyFraction(kellyFraction.multiply(new BigDecimal("100"))) // %로 변환
            .maxDrawdown(maxDrawdown)
            .sharpeRatio(sharpeRatio)
            .capitalHistory(capitalHistory) // 연속 백테스팅 MDD 계산을 위해 추가
            .build();
    }

    /**
     * Buy & Hold 전략 백테스팅
     */
    private BacktestResponse.BuyHoldResult runBuyAndHoldStrategy(
        FoldConfig foldConfig,
        BigDecimal initialCapital
    ) {
        // 시작일과 종료일의 OHLCV 데이터 조회
        HistoricalOhlcv startOhlcv = ohlcvRepository.findByMarketAndDate(MARKET, foldConfig.getStartDate())
            .orElseThrow(() -> new IllegalStateException(
                "시작일 OHLCV 데이터 없음: " + foldConfig.getStartDate()));

        HistoricalOhlcv endOhlcv = ohlcvRepository.findByMarketAndDate(MARKET, foldConfig.getEndDate())
            .orElseThrow(() -> new IllegalStateException(
                "종료일 OHLCV 데이터 없음: " + foldConfig.getEndDate()));

        BigDecimal entryPrice = startOhlcv.getOpeningPrice(); // 시작일 시가
        BigDecimal exitPrice = endOhlcv.getTradePrice();      // 종료일 종가

        // B&H는 1회 매수 + 1회 매도만 (수수료 0.1%)
        BigDecimal bhFee = new BigDecimal("0.001"); // 0.1% (양방향)

        BigDecimal priceReturn = exitPrice.divide(entryPrice, SCALE, ROUNDING)
            .subtract(BigDecimal.ONE);

        BigDecimal netReturn = priceReturn.subtract(bhFee);

        BigDecimal finalCapital = initialCapital.multiply(BigDecimal.ONE.add(netReturn))
            .setScale(SCALE, ROUNDING);

        BigDecimal totalReturnPct = netReturn.multiply(new BigDecimal("100"));

        // B&H 최대 낙폭 계산 (기간 중 모든 OHLCV 데이터 필요)
        List<HistoricalOhlcv> periodOhlcv = ohlcvRepository.findByMarketAndDateRange(
            MARKET, foldConfig.getStartDate(), foldConfig.getEndDate());

        BigDecimal maxDrawdown = calculateBuyHoldMaxDrawdown(periodOhlcv, entryPrice);

        return BacktestResponse.BuyHoldResult.builder()
            .initialCapital(initialCapital)
            .finalCapital(finalCapital)
            .totalReturnPct(totalReturnPct)
            .entryPrice(entryPrice)
            .exitPrice(exitPrice)
            .maxDrawdown(maxDrawdown)
            .build();
    }

    /**
     * 최대 낙폭(MDD) 계산
     */
    private BigDecimal calculateMaxDrawdown(List<BigDecimal> capitalHistory) {
        if (capitalHistory.size() < 2) {
            return BigDecimal.ZERO;
        }

        BigDecimal peak = capitalHistory.get(0);
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        for (BigDecimal capital : capitalHistory) {
            if (capital.compareTo(peak) > 0) {
                peak = capital;
            }

            BigDecimal drawdown = peak.subtract(capital)
                .divide(peak, 4, ROUNDING)
                .multiply(new BigDecimal("100"));

            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }

        return maxDrawdown;
    }

    /**
     * Buy & Hold 최대 낙폭 계산 (기간 중 가격 변동 기준)
     */
    private BigDecimal calculateBuyHoldMaxDrawdown(List<HistoricalOhlcv> ohlcvList, BigDecimal entryPrice) {
        if (ohlcvList.size() < 2) {
            return BigDecimal.ZERO;
        }

        BigDecimal peak = entryPrice;
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        for (HistoricalOhlcv ohlcv : ohlcvList) {
            BigDecimal currentPrice = ohlcv.getTradePrice(); // 종가

            if (currentPrice.compareTo(peak) > 0) {
                peak = currentPrice;
            }

            BigDecimal drawdown = peak.subtract(currentPrice)
                .divide(peak, 4, ROUNDING)
                .multiply(new BigDecimal("100"));

            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }

        return maxDrawdown;
    }

    // 내부 클래스: 켈리 계산 결과
    private static class KellyCalculation {
        BigDecimal kellyFraction;
        BigDecimal winRate;
        BigDecimal winLossRatio;

        KellyCalculation(BigDecimal kellyFraction, BigDecimal winRate, BigDecimal winLossRatio) {
            this.kellyFraction = kellyFraction;
            this.winRate = winRate;
            this.winLossRatio = winLossRatio;
        }
    }

    // 내부 클래스: 거래 결과
    private static class TradeResult {
        LocalDate tradeDate;
        BigDecimal entryPrice;
        BigDecimal exitPrice;
        BigDecimal returnPct;
        BigDecimal profit;
        BigDecimal capital;
        Integer predDirection;
        BigDecimal confidence;
        BigDecimal positionSize;

        TradeResult(LocalDate tradeDate, BigDecimal entryPrice, BigDecimal exitPrice,
                    BigDecimal returnPct, BigDecimal profit, BigDecimal capital,
                    Integer predDirection, BigDecimal confidence, BigDecimal positionSize) {
            this.tradeDate = tradeDate;
            this.entryPrice = entryPrice;
            this.exitPrice = exitPrice;
            this.returnPct = returnPct;
            this.profit = profit;
            this.capital = capital;
            this.predDirection = predDirection;
            this.confidence = confidence;
            this.positionSize = positionSize;
        }
    }

    /**
     * DB에서 AI 예측 데이터를 조회하여 CsvPredictionData로 변환
     * @param foldNumber 1~8
     * @return 예측 데이터 리스트
     */
    private List<CsvPredictionData> loadPredictionsFromDb(int foldNumber) {
        log.info("DB에서 AI 예측 데이터 로드 시작: Fold {}", foldNumber);

        List<HistoricalAiPrediction> entities = aiPredictionRepository
            .findByMarketAndFoldNumberOrderByPredictionDateAsc(MARKET, foldNumber);

        if (entities.isEmpty()) {
            throw new IllegalStateException(
                String.format("Fold %d의 AI 예측 데이터가 DB에 없습니다. 먼저 데이터를 적재해주세요.", foldNumber));
        }

        List<CsvPredictionData> predictions = entities.stream()
            .map(entity -> CsvPredictionData.builder()
                .date(entity.getPredictionDate())
                .actualDirection(entity.getActualDirection())
                .actualReturn(entity.getActualReturn())
                .predDirection(entity.getPredDirection())
                .predProbaUp(entity.getPredProbaUp())
                .predProbaDown(entity.getPredProbaDown())
                .maxProba(entity.getMaxProba())
                .confidence(entity.getConfidence())
                .correct(entity.getCorrect())
                .build())
            .collect(Collectors.toList());

        log.info("DB에서 AI 예측 데이터 로드 완료: Fold {} ({}건)", foldNumber, predictions.size());
        return predictions;
    }

    /**
     * 수익률 리스트로부터 Sharpe Ratio 계산 (연속 백테스팅용)
     * @param returns 수익률 리스트 (%)
     * @return Sharpe Ratio
     */
    private BigDecimal calculateSharpeRatioFromReturns(List<BigDecimal> returns) {
        if (returns.isEmpty()) {
            return BigDecimal.ZERO;
        }

        // 1. 평균 수익률
        BigDecimal sumReturns = returns.stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgReturn = sumReturns.divide(new BigDecimal(returns.size()), SCALE, ROUNDING);

        // 2. 표준편차
        BigDecimal sumSquaredDiff = returns.stream()
            .map(r -> {
                BigDecimal diff = r.subtract(avgReturn);
                return diff.multiply(diff);
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal variance = sumSquaredDiff.divide(new BigDecimal(returns.size()), SCALE, ROUNDING);
        double stdDevDouble = Math.sqrt(variance.doubleValue());
        BigDecimal stdDev = new BigDecimal(stdDevDouble).setScale(SCALE, ROUNDING);

        // 3. Sharpe Ratio
        if (stdDev.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return avgReturn.divide(stdDev, 4, ROUNDING);
    }

    /**
     * Sharpe Ratio 계산 (거래별)
     * Sharpe Ratio = (평균 수익률 - 무위험 수익률) / 수익률의 표준편차
     * 무위험 수익률은 0으로 가정
     *
     * @param tradeResults 거래 결과 리스트
     * @return Sharpe Ratio
     */
    private BigDecimal calculateSharpeRatio(List<TradeResult> tradeResults) {
        if (tradeResults.isEmpty()) {
            return BigDecimal.ZERO;
        }

        // 1. 평균 수익률 계산 (%)
        BigDecimal sumReturns = tradeResults.stream()
            .map(t -> t.returnPct)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal avgReturn = sumReturns.divide(new BigDecimal(tradeResults.size()), SCALE, ROUNDING);

        // 2. 표준편차 계산
        // 분산 = Σ(수익률 - 평균)² / N
        BigDecimal sumSquaredDiff = tradeResults.stream()
            .map(t -> {
                BigDecimal diff = t.returnPct.subtract(avgReturn);
                return diff.multiply(diff);
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal variance = sumSquaredDiff.divide(new BigDecimal(tradeResults.size()), SCALE, ROUNDING);

        // 표준편차 = √분산
        double stdDevDouble = Math.sqrt(variance.doubleValue());
        BigDecimal stdDev = new BigDecimal(stdDevDouble).setScale(SCALE, ROUNDING);

        // 3. Sharpe Ratio 계산 (무위험 수익률 = 0 가정)
        if (stdDev.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal sharpeRatio = avgReturn.divide(stdDev, 4, ROUNDING);

        log.debug("Sharpe Ratio 계산: 평균수익률={}, 표준편차={}, Sharpe={}",
            avgReturn, stdDev, sharpeRatio);

        return sharpeRatio;
    }

    /**
     * 백분위수 계산 (파이썬 quantile 함수와 동일한 로직)
     *
     * @param predictions 예측 데이터 리스트
     * @param percentile 백분위수 (0~100, 예: 50 = 50번째 백분위, 75 = 75번째 백분위)
     * @param column 사용할 컬럼 (CONFIDENCE 또는 PRED_PROBA_UP)
     * @return 계산된 임계값
     */
    private BigDecimal calculateQuantile(List<CsvPredictionData> predictions, BigDecimal percentile, ConfidenceColumn column) {
        if (predictions.isEmpty()) {
            return BigDecimal.ZERO;
        }

        // 1. 선택한 컬럼의 값들만 추출하여 정렬
        List<BigDecimal> values = predictions.stream()
            .map(p -> column == ConfidenceColumn.CONFIDENCE ? p.getConfidence() : p.getPredProbaUp())
            .sorted()
            .collect(Collectors.toList());

        // 2. 백분위수를 0~1 범위로 변환 (50 → 0.50)
        BigDecimal percentileDecimal = percentile.divide(new BigDecimal("100"), 4, ROUNDING);

        // 3. 인덱스 계산 (파이썬 numpy.quantile과 동일한 방식: linear interpolation)
        int n = values.size();
        BigDecimal position = percentileDecimal.multiply(new BigDecimal(n - 1));
        int lowerIndex = position.intValue();
        int upperIndex = Math.min(lowerIndex + 1, n - 1);

        // 4. 선형 보간 (linear interpolation)
        BigDecimal lowerValue = values.get(lowerIndex);
        BigDecimal upperValue = values.get(upperIndex);
        BigDecimal fraction = position.subtract(new BigDecimal(lowerIndex));

        BigDecimal result = lowerValue.add(
            upperValue.subtract(lowerValue).multiply(fraction)
        ).setScale(4, ROUNDING);

        log.debug("Quantile 계산 [{}]: {}% → 인덱스 {}/{} → 값 {}",
            column, percentile, lowerIndex, upperIndex, result);

        return result;
    }
}
