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
 * - Volatility Squeeze Breakout 전략
 * - 4시간봉 기술적 지표 기반
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

    // 전략 파라미터 (Python 코드 기본값)
    private static final int BB_PERIOD = 20;
    private static final BigDecimal BB_MULTIPLIER = new BigDecimal("2.0");
    private static final int EMA_PERIOD = 20;
    private static final int ATR_PERIOD = 14;
    private static final BigDecimal ATR_MULTIPLIER = new BigDecimal("4.0");
    private static final BigDecimal VOLUME_SPIKE_THRESHOLD = new BigDecimal("2.5");
    private static final BigDecimal POSITION_SIZE = new BigDecimal("0.8"); // 80%
    private static final int SQUEEZE_WINDOW = 120; // 120개 4시간봉 = 20일
    private static final double SQUEEZE_QUANTILE = 0.20; // 하위 20%
    private static final BigDecimal NATR_THRESHOLD = new BigDecimal("2.5"); // 2.5%

    /**
     * Rule-Based 백테스팅 실행
     */
    @Transactional(readOnly = true)
    public TakeProfitStopLossBacktestResponse runBacktest(Integer foldNumber, BigDecimal initialCapital) {
        log.info("=== Rule-Based 백테스팅 시작: Fold={} ===", foldNumber);

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

        if (fourHourCandles.size() < BB_PERIOD + SQUEEZE_WINDOW) {
            log.warn("4시간봉 데이터 부족: {}개", fourHourCandles.size());
            return createEmptyResponse(foldNumber, initialCapital);
        }

        // 4. 기술적 지표 계산
        Map<String, List<BigDecimal>> indicators = calculateIndicators(fourHourCandles);

        // 5. 진입/청산 신호 생성
        List<Integer> entrySignals = generateEntrySignals(fourHourCandles, indicators, startDate, endDate);
        log.info("진입 신호 생성: {}개", entrySignals.size());

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
     * 기술적 지표 계산
     */
    private Map<String, List<BigDecimal>> calculateIndicators(List<FourHourCandle> candles) {
        Map<String, List<BigDecimal>> indicators = new HashMap<>();

        // 가격 리스트 추출
        List<BigDecimal> close = candles.stream().map(FourHourCandle::getClose).collect(Collectors.toList());
        List<BigDecimal> high = candles.stream().map(FourHourCandle::getHigh).collect(Collectors.toList());
        List<BigDecimal> low = candles.stream().map(FourHourCandle::getLow).collect(Collectors.toList());
        List<BigDecimal> volume = candles.stream().map(FourHourCandle::getVolume).collect(Collectors.toList());

        // Bollinger Bands
        BollingerBands bb = TechnicalIndicators.calculateBollingerBands(close, BB_PERIOD, BB_MULTIPLIER);
        indicators.put("bb_sma", bb.sma);
        indicators.put("bb_upper", bb.upper);
        indicators.put("bb_lower", bb.lower);
        indicators.put("bb_width", bb.width);

        // EMA
        List<BigDecimal> ema = TechnicalIndicators.calculateEMA(close, EMA_PERIOD);
        indicators.put("ema", ema);

        // ATR & NATR
        List<BigDecimal> atr = TechnicalIndicators.calculateATR(high, low, close, ATR_PERIOD);
        List<BigDecimal> natr = TechnicalIndicators.calculateNATR(atr, close);
        indicators.put("atr", atr);
        indicators.put("natr", natr);

        // Squeeze 임계값 (BB_Width의 rolling 20% quantile)
        List<BigDecimal> squeezeThreshold = TechnicalIndicators.calculateRollingQuantile(
            bb.width, SQUEEZE_WINDOW, SQUEEZE_QUANTILE);
        indicators.put("squeeze_threshold", squeezeThreshold);

        // Volume MA
        List<BigDecimal> volumeMa = TechnicalIndicators.calculateSMA(volume, 20);
        indicators.put("volume_ma", volumeMa);

        // Volume Spike
        List<Boolean> volumeSpike = TechnicalIndicators.detectVolumeSpike(
            volume, volumeMa, VOLUME_SPIKE_THRESHOLD);
        indicators.put("volume_spike", volumeSpike.stream()
            .map(v -> v ? BigDecimal.ONE : BigDecimal.ZERO)
            .collect(Collectors.toList()));

        // Rolling High (Chandelier용)
        List<BigDecimal> rollingHigh = TechnicalIndicators.calculateRollingMax(high, 20);
        indicators.put("rolling_high", rollingHigh);

        log.debug("기술적 지표 계산 완료");
        return indicators;
    }

    /**
     * 진입 신호 생성
     */
    private List<Integer> generateEntrySignals(
        List<FourHourCandle> candles,
        Map<String, List<BigDecimal>> indicators,
        LocalDate startDate,
        LocalDate endDate
    ) {
        List<Integer> signals = new ArrayList<>();

        List<BigDecimal> close = candles.stream().map(FourHourCandle::getClose).collect(Collectors.toList());
        List<BigDecimal> bbWidth = indicators.get("bb_width");
        List<BigDecimal> bbUpper = indicators.get("bb_upper");
        List<BigDecimal> natr = indicators.get("natr");
        List<BigDecimal> squeezeThreshold = indicators.get("squeeze_threshold");
        List<BigDecimal> volumeSpike = indicators.get("volume_spike");

        for (int i = 1; i < candles.size(); i++) {
            FourHourCandle currentCandle = candles.get(i);
            LocalDate currentDate = currentCandle.getTimestamp().toLocalDate();

            // Fold 기간 내에서만 진입
            if (currentDate.isBefore(startDate) || currentDate.isAfter(endDate)) {
                continue;
            }

            // 전날(i-1) 조건 확인
            int prevIdx = i - 1;

            if (bbWidth.get(prevIdx) == null || bbUpper.get(prevIdx) == null ||
                natr.get(prevIdx) == null || squeezeThreshold.get(prevIdx) == null) {
                continue;
            }

            // 조건 1: Setup (Squeeze OR 낮은 변동성)
            boolean isSqueeze = bbWidth.get(prevIdx).compareTo(squeezeThreshold.get(prevIdx)) < 0;
            boolean isLowVolatility = natr.get(prevIdx).compareTo(NATR_THRESHOLD) < 0;
            boolean setupCondition = isSqueeze || isLowVolatility;

            // 조건 2: Breakout (Close > BB Upper)
            boolean breakoutCondition = close.get(prevIdx).compareTo(bbUpper.get(prevIdx)) > 0;

            // 조건 3: Volume Spike
            boolean volumeCondition = volumeSpike.get(prevIdx).compareTo(BigDecimal.ZERO) > 0;

            // 진입 신호
            if (setupCondition && breakoutCondition && volumeCondition) {
                signals.add(i);
                log.debug("진입 신호 발생: index={}, date={}, squeeze={}, lowVol={}, breakout={}, volume={}",
                    i, currentDate, isSqueeze, isLowVolatility, breakoutCondition, volumeCondition);
            }
        }

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
        List<BigDecimal> ema = indicators.get("ema");
        List<BigDecimal> atr = indicators.get("atr");
        List<BigDecimal> rollingHigh = indicators.get("rolling_high");

        // 2. 포지션 사이징 (80% 고정)
        BigDecimal positionSize = capital.multiply(POSITION_SIZE).setScale(2, RoundingMode.DOWN);

        if (positionSize.compareTo(BigDecimal.ONE) < 0) {
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

            if (ema.get(i) == null || atr.get(i) == null || rollingHigh.get(i) == null) {
                continue;
            }

            // 조건 1: EMA 하향 돌파
            boolean emaExit = close.get(i).compareTo(ema.get(i)) < 0;

            // 조건 2: Chandelier Trailing Stop
            BigDecimal chandelier = rollingHigh.get(i).subtract(atr.get(i).multiply(ATR_MULTIPLIER));
            boolean chandelierExit = close.get(i).compareTo(chandelier) < 0;

            if (emaExit || chandelierExit) {
                exitReason = emaExit ? "EMA_CROSS" : "CHANDELIER_STOP";
                exitTime = checkCandle.getTimestamp();
                exitPrice = checkCandle.getOpen(); // 다음 4시간봉 시작가로 청산
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

        // 승률 계산
        long winCount = tradeHistory.stream().filter(t -> t.getProfit().compareTo(BigDecimal.ZERO) > 0).count();
        BigDecimal winRate = tradeHistory.isEmpty() ? BigDecimal.ZERO :
            BigDecimal.valueOf(winCount).divide(BigDecimal.valueOf(tradeHistory.size()), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        // 평균 보유 기간
        BigDecimal avgHoldingDays = tradeHistory.isEmpty() ? BigDecimal.ZERO :
            tradeHistory.stream()
                .map(TradeDetail::getHoldingDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(tradeHistory.size()), 2, RoundingMode.HALF_UP);

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
            .winRate(winRate)
            .avgHoldingDays(avgHoldingDays)
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
