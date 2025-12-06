package com.coinreaders.engine.application.backtest;

import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestResponse;
import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestResponse.TradeDetail;
import com.coinreaders.engine.application.backtest.indicator.CandleResampler;
import com.coinreaders.engine.application.backtest.indicator.CandleResampler.FourHourCandle;
import com.coinreaders.engine.application.backtest.indicator.TechnicalIndicators;
import com.coinreaders.engine.application.backtest.indicator.TechnicalIndicators.BollingerBands;
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
import java.util.*;
import java.util.stream.Collectors;

/**
 * Rule-Based 백테스팅 서비스
 * - Trend Following 전략
 * - 4시간봉 기술적 지표 기반 (SMA, EMA)
 * - Buy & Hold와 동일하게 벤치마크로 사용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleBasedBacktestService {

    private final HistoricalAiPredictionRepository aiPredictionRepository;
    private final HistoricalMinuteOhlcvRepository minuteOhlcvRepository;

    private static final String MARKET = "KRW-ETH";
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005"); // 0.05% (편도)
    private static final int SCALE = 8;

    // 전략 파라미터 (Trend Following)
    private static final int SHORT_SMA = 20;  // 단기 이동평균
    private static final int LONG_SMA = 50;   // 장기 이동평균
    private static final int EMA_PERIOD = 20; // 청산용 EMA
    private static final int VOLUME_MA = 20;  // 거래량 이동평균
    private static final BigDecimal VOLUME_THRESHOLD = new BigDecimal("1.2"); // 거래량 1.2배
    private static final BigDecimal POSITION_SIZE = new BigDecimal("0.8"); // 80%
    private static final BigDecimal STOP_LOSS_PCT = new BigDecimal("0.95"); // 5% 손절

    /**
     * Rule-Based 백테스팅 실행
     */
    @Transactional(readOnly = true)
    public TakeProfitStopLossBacktestResponse runBacktest(Integer foldNumber, BigDecimal initialCapital) {
        log.info("=== Rule-Based 백테스팅 시작: Fold={}, 초기자본={}원 ===", foldNumber, initialCapital);

        // 1. Fold 기간 파악 (AI 예측 데이터 기준)
        List<HistoricalAiPrediction> predictions = aiPredictionRepository
            .findByMarketAndFoldNumberOrderByPredictionDateAsc(MARKET, foldNumber);

        if (predictions.isEmpty()) {
            log.warn("예측 데이터가 없습니다: Fold={}", foldNumber);
            return createEmptyResponse(foldNumber, initialCapital);
        }

        LocalDate startDate = predictions.get(0).getPredictionDate();
        LocalDate endDate = predictions.get(predictions.size() - 1).getPredictionDate();
        String regime = determineRegime(foldNumber);

        log.info("Fold {} 기간: {} ~ {} ({}일)", foldNumber, startDate, endDate,
            java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate));

        // 2. 1분봉 데이터 조회 (지표 계산을 위해 더 넓은 기간 필요)
        LocalDateTime dataStart = startDate.minusDays(30).atTime(0, 0); // 지표 계산용 여유
        LocalDateTime dataEnd = endDate.plusDays(1).atTime(0, 0);

        List<HistoricalMinuteOhlcv> minuteCandles = minuteOhlcvRepository
            .findByMarketAndCandleDateTimeKstBetweenOrderByCandleDateTimeKstAsc(
                MARKET, dataStart, dataEnd);

        if (minuteCandles.size() < 1000) {
            log.warn("1분봉 데이터 부족: {}개", minuteCandles.size());
            return createEmptyResponse(foldNumber, initialCapital);
        }

        log.info("1분봉 데이터 조회: {}개", minuteCandles.size());

        // 3. 4시간봉으로 리샘플링
        List<FourHourCandle> fourHourCandles = CandleResampler.resampleTo4Hour(minuteCandles);

        if (fourHourCandles.size() < LONG_SMA + 10) {
            log.warn("4시간봉 데이터 부족: {}개", fourHourCandles.size());
            return createEmptyResponse(foldNumber, initialCapital);
        }

        // 4. 기술적 지표 계산
        Map<String, List<BigDecimal>> indicators = calculateIndicators(fourHourCandles);

        // 5. 진입/청산 신호 생성
        List<Integer> entrySignals = generateEntrySignals(fourHourCandles, indicators, startDate, endDate);
        log.info("진입 신호 생성: {}개 (4시간봉 총 {}개, Fold 기간: {} ~ {})",
            entrySignals.size(), fourHourCandles.size(), startDate, endDate);

        if (entrySignals.isEmpty()) {
            log.warn("⚠️ 진입 신호가 하나도 생성되지 않았습니다. 전략 조건을 확인하세요.");
        }

        // 6. 1분봉을 Map으로 변환 (성능 최적화: O(1) 조회)
        Map<LocalDateTime, HistoricalMinuteOhlcv> minuteCandleMap = minuteCandles.stream()
            .collect(Collectors.toMap(
                HistoricalMinuteOhlcv::getCandleDateTimeKst,
                c -> c,
                (c1, c2) -> c1 // 중복 시 첫 번째 유지
            ));

        // 7. 거래 시뮬레이션
        List<TradeDetail> tradeHistory = simulateTrades(
            fourHourCandles, indicators, entrySignals, initialCapital, minuteCandleMap);

        // 8. 최종 자본 계산
        BigDecimal finalCapital = tradeHistory.isEmpty() ?
            initialCapital :
            tradeHistory.get(tradeHistory.size() - 1).getCapitalAfter();

        BigDecimal totalReturn = finalCapital.subtract(initialCapital);
        BigDecimal totalReturnPct = totalReturn.divide(initialCapital, 4, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));

        log.info("=== Rule-Based 백테스팅 완료: Fold={} ===", foldNumber);
        log.info("총 거래: {}건", tradeHistory.size());
        log.info("초기 자본: {}원", initialCapital);
        log.info("최종 자본: {}원", finalCapital);
        log.info("수익: {}원 ({}%)", totalReturn, totalReturnPct);

        // 9. 응답 생성
        return buildResponse(foldNumber, regime, startDate, endDate, initialCapital, finalCapital, tradeHistory);
    }

    /**
     * 기술적 지표 계산 (Trend Following 전략)
     */
    private Map<String, List<BigDecimal>> calculateIndicators(List<FourHourCandle> candles) {
        Map<String, List<BigDecimal>> indicators = new HashMap<>();

        // 가격 리스트 추출
        List<BigDecimal> close = candles.stream().map(FourHourCandle::getClose).collect(Collectors.toList());
        List<BigDecimal> volume = candles.stream().map(FourHourCandle::getVolume).collect(Collectors.toList());

        // SMA (단기/장기)
        List<BigDecimal> sma20 = TechnicalIndicators.calculateSMA(close, SHORT_SMA);
        List<BigDecimal> sma50 = TechnicalIndicators.calculateSMA(close, LONG_SMA);
        indicators.put("sma20", sma20);
        indicators.put("sma50", sma50);

        // EMA (청산용)
        List<BigDecimal> ema20 = TechnicalIndicators.calculateEMA(close, EMA_PERIOD);
        indicators.put("ema20", ema20);

        // Volume MA
        List<BigDecimal> volumeMa = TechnicalIndicators.calculateSMA(volume, VOLUME_MA);
        indicators.put("volume_ma", volumeMa);

        log.info("✅ 지표 계산 완료: SMA(20), SMA(50), EMA(20), Volume MA(20)");
        return indicators;
    }

    /**
     * 진입 신호 생성 (Trend Following)
     */
    private List<Integer> generateEntrySignals(
        List<FourHourCandle> candles,
        Map<String, List<BigDecimal>> indicators,
        LocalDate startDate,
        LocalDate endDate
    ) {
        List<Integer> signals = new ArrayList<>();

        List<BigDecimal> close = candles.stream().map(FourHourCandle::getClose).collect(Collectors.toList());
        List<BigDecimal> volume = candles.stream().map(FourHourCandle::getVolume).collect(Collectors.toList());
        List<BigDecimal> sma20 = indicators.get("sma20");
        List<BigDecimal> sma50 = indicators.get("sma50");
        List<BigDecimal> volumeMa = indicators.get("volume_ma");

        // 디버깅 카운터
        int totalCandles = 0;
        int foldRangeCandles = 0;
        int nullIndicators = 0;
        int trendCount = 0;     // Close > SMA20 > SMA50
        int volumeCount = 0;    // Volume > MA × 1.2
        int allConditionsCount = 0;

        for (int i = 1; i < candles.size(); i++) {
            totalCandles++;
            FourHourCandle currentCandle = candles.get(i);
            LocalDate currentDate = currentCandle.getTimestamp().toLocalDate();

            // Fold 기간 내에서만 진입
            if (currentDate.isBefore(startDate) || currentDate.isAfter(endDate)) {
                continue;
            }
            foldRangeCandles++;

            int prevIdx = i - 1;

            // 지표 null 체크
            if (sma20.get(prevIdx) == null || sma50.get(prevIdx) == null || volumeMa.get(prevIdx) == null) {
                nullIndicators++;
                continue;
            }

            // 조건 1: 상승 추세 (Close > SMA20 > SMA50)
            boolean aboveSma20 = close.get(prevIdx).compareTo(sma20.get(prevIdx)) > 0;
            boolean aboveSma50 = close.get(prevIdx).compareTo(sma50.get(prevIdx)) > 0;
            boolean trendCondition = aboveSma20 && aboveSma50;
            if (trendCondition) trendCount++;

            // 조건 2: 거래량 증가 (Volume > MA × 1.2)
            BigDecimal volumeThreshold = volumeMa.get(prevIdx).multiply(VOLUME_THRESHOLD);
            boolean volumeCondition = volume.get(prevIdx).compareTo(volumeThreshold) > 0;
            if (volumeCondition) volumeCount++;

            // 진입 신호
            if (trendCondition && volumeCondition) {
                signals.add(i);
                allConditionsCount++;
                log.info("✅ 진입 신호: index={}, date={}, Close={}, SMA20={}, SMA50={}, Vol={}",
                    i, currentDate,
                    close.get(prevIdx).setScale(0, RoundingMode.HALF_UP),
                    sma20.get(prevIdx).setScale(0, RoundingMode.HALF_UP),
                    sma50.get(prevIdx).setScale(0, RoundingMode.HALF_UP),
                    volume.get(prevIdx).setScale(0, RoundingMode.HALF_UP));
            }
        }

        // 디버깅 요약
        log.info("📊 진입 조건 분석 (Trend Following):");
        log.info("  - 전체 4시간봉: {}개", totalCandles);
        log.info("  - Fold 기간 내: {}개", foldRangeCandles);
        log.info("  - 지표 null 제외: {}개", nullIndicators);
        log.info("  - 상승 추세 (Close>SMA20>SMA50): {}개", trendCount);
        log.info("  - 거래량 증가 (Vol>MA×1.2): {}개", volumeCount);
        log.info("  - ✅ 모든 조건 만족: {}개", allConditionsCount);

        return signals;
    }

    /**
     * 거래 시뮬레이션
     */
    private List<TradeDetail> simulateTrades(
        List<FourHourCandle> candles,
        Map<String, List<BigDecimal>> indicators,
        List<Integer> entrySignals,
        BigDecimal initialCapital,
        Map<LocalDateTime, HistoricalMinuteOhlcv> minuteCandleMap
    ) {
        log.info("거래 시뮬레이션 시작: 초기자본={}원, 진입신호={}개", initialCapital, entrySignals.size());
        List<TradeDetail> trades = new ArrayList<>();
        BigDecimal capital = initialCapital;
        int tradeNumber = 1;
        LocalDateTime lastExitTime = null;

        for (int entryIdx : entrySignals) {
            FourHourCandle entryCandle = candles.get(entryIdx);
            LocalDateTime entryTime = entryCandle.getTimestamp();

            // 포지션 오버랩 방지
            if (lastExitTime != null && entryTime.isBefore(lastExitTime)) {
                log.debug("포지션 보유 중이므로 거래 건너뜀: entryTime={}, lastExitTime={}", entryTime, lastExitTime);
                continue;
            }

            try {
                Optional<TradeDetail> tradeOpt = simulateSingleTrade(
                    entryIdx, candles, indicators, capital, tradeNumber, minuteCandleMap);

                if (tradeOpt.isPresent()) {
                    TradeDetail trade = tradeOpt.get();
                    trades.add(trade);
                    capital = trade.getCapitalAfter();
                    lastExitTime = trade.getExitDateTime();
                    tradeNumber++;
                }
            } catch (Exception e) {
                log.warn("거래 시뮬레이션 실패: entryTime={}, error={}", entryTime, e.getMessage());
            }
        }

        log.info("거래 시뮬레이션 완료: 총 {}건 거래 실행", trades.size());
        return trades;
    }

    /**
     * 개별 거래 시뮬레이션
     */
    private Optional<TradeDetail> simulateSingleTrade(
        int entryIdx,
        List<FourHourCandle> candles,
        Map<String, List<BigDecimal>> indicators,
        BigDecimal capital,
        int tradeNumber,
        Map<LocalDateTime, HistoricalMinuteOhlcv> minuteCandleMap
    ) {
        FourHourCandle entryCandle = candles.get(entryIdx);
        LocalDateTime entryTime = entryCandle.getTimestamp();

        // 1. 진입가 = 4시간봉 시작 시각의 1분봉 Open 가격 (Map에서 O(1) 조회)
        HistoricalMinuteOhlcv entryMinuteCandle = minuteCandleMap.get(entryTime);

        if (entryMinuteCandle == null) {
            // 정확한 시각이 없으면 4시간봉의 Open 가격 사용
            log.debug("진입 시각 1분봉 없음, 4시간봉 Open 사용: {}", entryTime);
            BigDecimal entryPrice = entryCandle.getOpen();
            return simulateTradeWithPrice(entryIdx, candles, indicators, capital, tradeNumber, entryPrice, entryTime);
        }

        BigDecimal entryPrice = entryMinuteCandle.getOpeningPrice();
        LocalDateTime actualEntryTime = entryMinuteCandle.getCandleDateTimeKst();

        return simulateTradeWithPrice(entryIdx, candles, indicators, capital, tradeNumber, entryPrice, actualEntryTime);
    }

    /**
     * 가격과 시각이 확정된 후의 거래 시뮬레이션
     */
    private Optional<TradeDetail> simulateTradeWithPrice(
        int entryIdx,
        List<FourHourCandle> candles,
        Map<String, List<BigDecimal>> indicators,
        BigDecimal capital,
        int tradeNumber,
        BigDecimal entryPrice,
        LocalDateTime actualEntryTime
    ) {
        List<BigDecimal> close = candles.stream().map(FourHourCandle::getClose).collect(Collectors.toList());
        List<BigDecimal> ema20 = indicators.get("ema20");

        // 2. 포지션 사이징 (80% 고정)
        BigDecimal positionSize = capital.multiply(POSITION_SIZE).setScale(2, RoundingMode.DOWN);

        log.debug("포지션 계산: 자본={}원, 포지션크기={}원 ({}%)", capital, positionSize, POSITION_SIZE.multiply(new BigDecimal("100")));

        if (positionSize.compareTo(BigDecimal.ONE) < 0) {
            log.warn("포지션 크기 너무 작음 (< 1원), 거래 제외: capital={}, positionSize={}", capital, positionSize);
            return Optional.empty();
        }

        // 3. 진입 수수료 차감
        BigDecimal entryFee = positionSize.multiply(FEE_RATE).setScale(2, RoundingMode.UP);
        BigDecimal entryAmount = positionSize.subtract(entryFee);
        BigDecimal quantity = entryAmount.divide(entryPrice, SCALE, RoundingMode.DOWN);

        // 4. 청산 조건 체크 (4시간봉마다)
        String exitReason = null;
        BigDecimal exitPrice = null;
        LocalDateTime exitTime = null;

        for (int i = entryIdx + 1; i < candles.size(); i++) {
            FourHourCandle checkCandle = candles.get(i);

            if (ema20.get(i) == null) {
                continue;
            }

            // 조건 1: EMA(20) 하향 돌파
            boolean emaExit = close.get(i).compareTo(ema20.get(i)) < 0;

            // 조건 2: 손절 (진입가 대비 5% 하락)
            BigDecimal stopLossPrice = entryPrice.multiply(STOP_LOSS_PCT);
            boolean stopLossExit = close.get(i).compareTo(stopLossPrice) < 0;

            if (emaExit || stopLossExit) {
                exitReason = emaExit ? "EMA_CROSS" : "STOP_LOSS";
                exitTime = checkCandle.getTimestamp();
                exitPrice = checkCandle.getClose(); // 현재 캔들 종가로 청산
                break;
            }
        }

        // 청산 신호 없으면 마지막 캔들에서 청산
        if (exitPrice == null) {
            FourHourCandle lastCandle = candles.get(candles.size() - 1);
            exitReason = "END_OF_PERIOD";
            exitTime = lastCandle.getTimestamp();
            exitPrice = lastCandle.getClose();
        }

        // 5. 손익 계산
        BigDecimal sellAmount = quantity.multiply(exitPrice);
        BigDecimal exitFee = sellAmount.multiply(FEE_RATE).setScale(2, RoundingMode.UP);
        BigDecimal netSellAmount = sellAmount.subtract(exitFee);

        BigDecimal profit = netSellAmount.subtract(positionSize);
        BigDecimal returnPct = profit.divide(positionSize, 4, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));

        BigDecimal finalCapital = capital.subtract(positionSize).add(netSellAmount);

        long holdingDays = java.time.temporal.ChronoUnit.DAYS.between(
            actualEntryTime.toLocalDate(), exitTime.toLocalDate());

        // 6. TradeDetail 생성
        return Optional.of(TradeDetail.builder()
            .tradeNumber(tradeNumber)
            .entryDate(actualEntryTime.toLocalDate())
            .entryDateTime(actualEntryTime)
            .entryPrice(entryPrice)
            .exitDate(exitTime.toLocalDate())
            .exitDateTime(exitTime)
            .exitPrice(exitPrice)
            .positionSize(positionSize)
            .investmentRatio(POSITION_SIZE)
            .profit(profit)
            .returnPct(returnPct)
            .exitReason(exitReason)
            .holdingDays(BigDecimal.valueOf(holdingDays))
            .predProbaUp(null) // Rule-Based는 예측 확률 없음
            .confidence(null)
            .capitalAfter(finalCapital)
            .exitEvents(Collections.emptyList())
            .build());
    }

    /**
     * 빈 응답 생성
     */
    private TakeProfitStopLossBacktestResponse createEmptyResponse(Integer foldNumber, BigDecimal initialCapital) {
        return TakeProfitStopLossBacktestResponse.builder()
            .modelName("Rule-Based")
            .foldNumber(foldNumber)
            .regime(determineRegime(foldNumber))
            .initialCapital(initialCapital)
            .finalCapital(initialCapital)
            .totalReturnPct(BigDecimal.ZERO)
            .totalTrades(0)
            .tradeHistory(Collections.emptyList())
            .build();
    }

    /**
     * 응답 생성
     */
    private TakeProfitStopLossBacktestResponse buildResponse(
        Integer foldNumber,
        String regime,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal initialCapital,
        BigDecimal finalCapital,
        List<TradeDetail> tradeHistory
    ) {
        BigDecimal totalReturn = finalCapital.subtract(initialCapital);
        BigDecimal totalReturnPct = totalReturn.divide(initialCapital, 4, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));

        // 거래 통계 계산
        long winCount = tradeHistory.stream().filter(t -> t.getProfit().compareTo(BigDecimal.ZERO) > 0).count();
        long lossCount = tradeHistory.stream().filter(t -> t.getProfit().compareTo(BigDecimal.ZERO) < 0).count();

        BigDecimal winRate = tradeHistory.isEmpty() ? BigDecimal.ZERO :
            BigDecimal.valueOf(winCount).divide(BigDecimal.valueOf(tradeHistory.size()), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        // 평균 보유 기간
        BigDecimal avgHoldingDays = tradeHistory.isEmpty() ? BigDecimal.ZERO :
            tradeHistory.stream()
                .map(TradeDetail::getHoldingDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(tradeHistory.size()), 2, RoundingMode.HALF_UP);

        // 청산 사유별 집계
        int emaCrossExits = (int) tradeHistory.stream().filter(t -> "EMA_CROSS".equals(t.getExitReason())).count();
        int stopLossExits = (int) tradeHistory.stream().filter(t -> "STOP_LOSS".equals(t.getExitReason())).count();
        int timeoutExits = (int) tradeHistory.stream().filter(t -> "END_OF_PERIOD".equals(t.getExitReason())).count();

        // 평균 수익/손실
        BigDecimal avgWin = winCount > 0 ?
            tradeHistory.stream()
                .filter(t -> t.getProfit().compareTo(BigDecimal.ZERO) > 0)
                .map(TradeDetail::getReturnPct)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(winCount), 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        BigDecimal avgLoss = lossCount > 0 ?
            tradeHistory.stream()
                .filter(t -> t.getProfit().compareTo(BigDecimal.ZERO) < 0)
                .map(TradeDetail::getReturnPct)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(lossCount), 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        // 손익비
        BigDecimal winLossRatio = (lossCount > 0 && avgLoss.compareTo(BigDecimal.ZERO) != 0) ?
            avgWin.divide(avgLoss.abs(), 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        return TakeProfitStopLossBacktestResponse.builder()
            .modelName("Rule-Based")
            .foldNumber(foldNumber)
            .regime(regime)
            .startDate(startDate)
            .endDate(endDate)
            .initialCapital(initialCapital)
            .finalCapital(finalCapital)
            .totalReturnPct(totalReturnPct)
            .totalTrades(tradeHistory.size())
            .takeProfitExits(emaCrossExits)    // EMA 크로스는 익절로 간주
            .stopLossExits(stopLossExits)
            .timeoutExits(timeoutExits)
            .winRate(winRate)
            .avgHoldingDays(avgHoldingDays)
            .maxDrawdown(BigDecimal.ZERO)      // 추후 구현 가능
            .sharpeRatio(BigDecimal.ZERO)      // 추후 구현 가능
            .avgWin(avgWin)
            .avgLoss(avgLoss)
            .winLossRatio(winLossRatio)
            .tradeHistory(tradeHistory)
            .build();
    }

    /**
     * Fold 번호로 시장 국면 판단 (하드코딩)
     */
    private String determineRegime(Integer foldNumber) {
        return switch (foldNumber) {
            case 1 -> "BULL";
            case 2 -> "BEAR";
            case 3 -> "SIDEWAYS";
            case 4 -> "BULL";
            case 5 -> "BEAR";
            case 6 -> "SIDEWAYS";
            case 7 -> "BULL";
            default -> "UNKNOWN";
        };
    }
}
