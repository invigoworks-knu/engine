package com.coinreaders.engine.application.backtest;

import com.coinreaders.engine.application.backtest.dto.CusumSignalData;
import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestResponse;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CUSUM 필터 기반 신호 백테스팅 서비스
 *
 * 새로운 ML 파이프라인 기반:
 * 1. CUSUM Filter로 노이즈 제거된 신호만 사용
 * 2. Triple Barrier Method로 라벨링된 데이터
 * 3. AI가 전략 검증 (Buy/Pass 판단)
 *
 * CSV 컬럼 매핑 (실제 명세 기준):
 * - signal_time: 트리거 시각
 * - strategy: 전략 ID
 * - model: 모델 ID
 * - fold_id: 검증 차수
 * - primary_signal: 1차 신호
 * - ml_prediction: AI 예측 (1=상승, 0=하락)
 * - final_action: BUY/PASS
 * - confidence: 확신도
 * - threshold: 기준점
 * - cusum_selectivity_pct: 희소성
 * - suggested_weight: 매수 비중 (Kelly Criterion)
 * - entry_price_ref: 참고 진입가
 * - take_profit_price: 익절가
 * - stop_loss_price: 손절가
 * - expiration_time: 타임 컷
 * - actual_direction: 실제 방향
 * - correct: 정답 여부
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CusumSignalBacktestService {

    private static final String CSV_PATH = "cusum_signals/backend_signals_master.csv";
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005"); // 0.05% 편도 수수료
    private static final BigDecimal DEFAULT_POSITION_RATIO = new BigDecimal("0.8"); // 기본값: 자본의 80%

    // 메모리에 캐싱된 신호 데이터
    private List<CusumSignalData> allSignals = new ArrayList<>();
    private boolean dataLoaded = false;

    // 날짜 포맷터들
    private static final DateTimeFormatter[] DATE_TIME_FORMATTERS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME
    };

    /**
     * 애플리케이션 시작 시 CSV 데이터 로드 시도
     * (파일이 없으면 경고만 출력하고 넘어감)
     */
    @PostConstruct
    public void init() {
        try {
            loadCsvData();
        } catch (Exception e) {
            log.warn("CUSUM 신호 CSV 파일을 찾을 수 없습니다. 파일 경로: {}", CSV_PATH);
            log.warn("파일을 src/main/resources/cusum_signals/backend_signals_master.csv 에 배치해주세요.");
        }
    }

    /**
     * CSV 데이터 로드 (수동 트리거용)
     */
    public int loadCsvData() throws IOException, CsvValidationException {
        log.info("CUSUM 신호 CSV 로딩 시작: {}", CSV_PATH);

        ClassPathResource resource = new ClassPathResource(CSV_PATH);
        List<CusumSignalData> signals = new ArrayList<>();

        try (CSVReader reader = new CSVReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String[] header = reader.readNext();
            if (header == null) {
                throw new IOException("CSV 헤더가 없습니다");
            }

            // BOM 제거
            if (header[0].startsWith("\ufeff")) {
                header[0] = header[0].substring(1);
            }

            // 컬럼 인덱스 매핑 (유연한 처리 - 대소문자 무시, 공백 제거)
            Map<String, Integer> columnIndex = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                columnIndex.put(header[i].trim().toLowerCase().replace(" ", "_"), i);
            }

            log.info("CSV 컬럼: {}", Arrays.asList(header));

            String[] line;
            int lineNumber = 1;
            int successCount = 0;
            int failCount = 0;

            while ((line = reader.readNext()) != null) {
                lineNumber++;
                try {
                    CusumSignalData signal = parseCsvLine(line, columnIndex);
                    if (signal != null) {
                        signals.add(signal);
                        successCount++;
                    }
                } catch (Exception e) {
                    failCount++;
                    if (failCount <= 10) {
                        log.debug("Line {} 파싱 실패: {}", lineNumber, e.getMessage());
                    }
                }
            }

            allSignals = signals;
            dataLoaded = true;

            log.info("CUSUM 신호 CSV 로딩 완료: 성공 {}건, 실패 {}건", successCount, failCount);
            return successCount;
        }
    }

    /**
     * CSV 라인 파싱 (실제 컬럼명 기준)
     */
    private CusumSignalData parseCsvLine(String[] line, Map<String, Integer> columnIndex) {
        // 필수 컬럼 확인 (실제 컬럼명 기준)
        Integer signalTimeIdx = columnIndex.get("signal_time");
        Integer entryPriceIdx = columnIndex.get("entry_price_ref");

        // 이전 컬럼명도 지원 (하위 호환)
        if (entryPriceIdx == null) {
            entryPriceIdx = columnIndex.get("entry_price");
        }

        if (signalTimeIdx == null || entryPriceIdx == null) {
            return null;
        }

        // 값 추출 (실제 컬럼명 기준)
        LocalDateTime signalTime = parseDateTime(getColumnValue(line, signalTimeIdx));
        String strategy = getColumnValue(line, columnIndex.get("strategy"));
        String model = getColumnValue(line, columnIndex.get("model"));
        Integer foldId = parseInteger(getColumnValue(line, columnIndex.get("fold_id")));

        // 매매 신호 및 필터링
        Boolean primarySignal = parseBoolean(getColumnValue(line, columnIndex.get("primary_signal")));
        Integer mlPrediction = parseInteger(getColumnValue(line,
            columnIndex.get("ml_prediction") != null ? columnIndex.get("ml_prediction") : columnIndex.get("ml_predict")));
        String finalAction = getColumnValue(line, columnIndex.get("final_action"));
        BigDecimal confidence = parseBigDecimal(getColumnValue(line, columnIndex.get("confidence")));
        BigDecimal threshold = parseBigDecimal(getColumnValue(line, columnIndex.get("threshold")));
        BigDecimal cusumSelectivityPct = parseBigDecimal(getColumnValue(line,
            columnIndex.get("cusum_selectivity_pct") != null ? columnIndex.get("cusum_selectivity_pct") : columnIndex.get("cusum_sel")));

        // 실행 및 자금 관리
        BigDecimal suggestedWeight = parseBigDecimal(getColumnValue(line,
            columnIndex.get("suggested_weight") != null ? columnIndex.get("suggested_weight") : columnIndex.get("suggested")));
        BigDecimal entryPriceRef = parseBigDecimal(getColumnValue(line, entryPriceIdx));
        BigDecimal takeProfitPrice = parseBigDecimal(getColumnValue(line,
            columnIndex.get("take_profit_price") != null ? columnIndex.get("take_profit_price") : columnIndex.get("take_profit")));
        BigDecimal stopLossPrice = parseBigDecimal(getColumnValue(line,
            columnIndex.get("stop_loss_price") != null ? columnIndex.get("stop_loss_price") : columnIndex.get("stop_loss_1")));
        LocalDateTime expirationTime = parseDateTime(getColumnValue(line,
            columnIndex.get("expiration_time") != null ? columnIndex.get("expiration_time") : columnIndex.get("expiration")));

        // 사후 검증용
        Integer actualDirection = parseInteger(getColumnValue(line, columnIndex.get("actual_direction")));
        Boolean correct = parseBoolean(getColumnValue(line, columnIndex.get("correct")));

        if (signalTime == null || entryPriceRef == null) {
            return null;
        }

        return CusumSignalData.builder()
            .signalTime(signalTime)
            .strategy(strategy)
            .model(model)
            .foldId(foldId)
            .primarySignal(primarySignal)
            .mlPrediction(mlPrediction)
            .finalAction(finalAction)
            .confidence(confidence)
            .threshold(threshold)
            .cusumSelectivityPct(cusumSelectivityPct)
            .suggestedWeight(suggestedWeight)
            .entryPriceRef(entryPriceRef)
            .takeProfitPrice(takeProfitPrice)
            .stopLossPrice(stopLossPrice)
            .expirationTime(expirationTime)
            .actualDirection(actualDirection)
            .correct(correct)
            .build();
    }

    private String getColumnValue(String[] line, Integer index) {
        if (index == null || index >= line.length) return null;
        String value = line[index].trim();
        return value.isEmpty() || "null".equalsIgnoreCase(value) || "######".equals(value) ? null : value;
    }

    private Integer parseInteger(String value) {
        if (value == null) return null;
        try {
            return (int) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean parseBoolean(String value) {
        if (value == null) return null;
        return "TRUE".equalsIgnoreCase(value) || "1".equals(value) || "true".equals(value);
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null) return null;

        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }

        // 날짜만 있는 경우
        try {
            LocalDate date = LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
            return date.atStartOfDay();
        } catch (DateTimeParseException ignored) {
        }

        return null;
    }

    /**
     * 데이터 로드 여부 확인
     */
    public boolean isDataLoaded() {
        return dataLoaded && !allSignals.isEmpty();
    }

    /**
     * 로드된 신호 개수
     */
    public int getSignalCount() {
        return allSignals.size();
    }

    /**
     * 사용 가능한 전략 목록 조회
     */
    public List<String> getAvailableStrategies() {
        return allSignals.stream()
            .map(CusumSignalData::getStrategy)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }

    /**
     * 사용 가능한 모델 목록 조회
     */
    public List<String> getAvailableModels() {
        return allSignals.stream()
            .map(CusumSignalData::getModel)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }

    /**
     * 사용 가능한 Fold 목록 조회
     */
    public List<Integer> getAvailableFolds() {
        return allSignals.stream()
            .map(CusumSignalData::getFoldId)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }

    /**
     * CUSUM 신호 기반 백테스팅 실행
     *
     * @param foldNumber    Fold 번호 (null이면 전체)
     * @param strategy      전략명 (null이면 전체, 예: "target_24h_Jackpot")
     * @param model         모델명 (null이면 전체, 예: "LGBM")
     * @param initialCapital 초기 자본
     * @return 백테스팅 결과
     */
    public TakeProfitStopLossBacktestResponse runBacktest(
            Integer foldNumber,
            String strategy,
            String model,
            BigDecimal initialCapital
    ) {
        if (!isDataLoaded()) {
            throw new IllegalStateException("CUSUM 신호 데이터가 로드되지 않았습니다. CSV 파일을 확인해주세요.");
        }

        log.info("CUSUM 백테스팅 시작: Fold={}, Strategy={}, Model={}, Capital={}",
            foldNumber, strategy, model, initialCapital);

        // 필터링된 신호 추출 (BUY 신호만)
        List<CusumSignalData> filteredSignals = allSignals.stream()
            .filter(s -> foldNumber == null || foldNumber.equals(s.getFoldId()))
            .filter(s -> strategy == null || strategy.equals(s.getStrategy()))
            .filter(s -> model == null || model.equals(s.getModel()))
            .filter(CusumSignalData::isBuySignal)  // BUY 신호만
            .sorted(Comparator.comparing(CusumSignalData::getSignalTime))
            .collect(Collectors.toList());

        log.info("필터링된 BUY 신호: {}건", filteredSignals.size());

        if (filteredSignals.isEmpty()) {
            return buildEmptyResponse(foldNumber, strategy, model, initialCapital);
        }

        // 백테스팅 실행
        return executeBacktest(filteredSignals, foldNumber, strategy, model, initialCapital);
    }

    /**
     * 백테스팅 실행 로직
     * - suggested_weight를 포지션 사이징에 반영 (Kelly Criterion)
     */
    private TakeProfitStopLossBacktestResponse executeBacktest(
            List<CusumSignalData> signals,
            Integer foldNumber,
            String strategy,
            String model,
            BigDecimal initialCapital
    ) {
        BigDecimal capital = initialCapital;
        List<TakeProfitStopLossBacktestResponse.TradeDetail> tradeHistory = new ArrayList<>();

        int totalTrades = 0;
        int takeProfitExits = 0;
        int stopLossExits = 0;
        int timeoutExits = 0;
        int winCount = 0;

        BigDecimal totalHoldingHours = BigDecimal.ZERO;
        BigDecimal peakCapital = initialCapital;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        BigDecimal totalWin = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;

        // CUSUM 집계용 변수
        BigDecimal totalConfidence = BigDecimal.ZERO;
        BigDecimal totalSelectivity = BigDecimal.ZERO;
        BigDecimal totalInvestmentRatio = BigDecimal.ZERO;
        int confidenceCount = 0;
        int selectivityCount = 0;
        int investmentRatioCount = 0;

        LocalDateTime currentPositionEnd = null; // 현재 포지션 종료 시각 (중복 진입 방지)

        for (CusumSignalData signal : signals) {
            // 이미 포지션이 있으면 스킵 (중복 진입 방지)
            if (currentPositionEnd != null && signal.getSignalTime().isBefore(currentPositionEnd)) {
                continue;
            }

            // 진입가, TP, SL 확인
            BigDecimal entryPrice = signal.getEntryPrice();
            BigDecimal takeProfit = signal.getTakeProfit();
            BigDecimal stopLoss = signal.getStopLoss();

            if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            // ★ 포지션 크기 계산: suggested_weight 사용 (Kelly Criterion 반영)
            // suggested_weight가 있으면 사용, 없으면 기본값 80%
            BigDecimal investmentRatio = signal.getInvestmentRatio();
            BigDecimal positionSize = capital.multiply(investmentRatio);
            BigDecimal quantity = positionSize.divide(entryPrice, 8, RoundingMode.HALF_DOWN);

            // CUSUM 집계 (평균 계산용)
            if (signal.getConfidence() != null) {
                totalConfidence = totalConfidence.add(signal.getConfidence());
                confidenceCount++;
            }
            if (signal.getCusumSelectivityPct() != null) {
                totalSelectivity = totalSelectivity.add(signal.getCusumSelectivityPct());
                selectivityCount++;
            }
            totalInvestmentRatio = totalInvestmentRatio.add(investmentRatio);
            investmentRatioCount++;

            // 진입 수수료
            BigDecimal entryFee = positionSize.multiply(FEE_RATE);

            // 청산 결정 (correct 필드 활용)
            String exitReason;
            BigDecimal exitPrice;

            // correct == TRUE면 익절 (take_profit_price에서 청산)
            // correct == FALSE면 손절 (stop_loss_price에서 청산)
            Boolean correct = signal.getCorrect();
            if (correct != null && correct) {
                // 익절
                exitReason = "TAKE_PROFIT";
                exitPrice = (takeProfit != null) ? takeProfit : entryPrice.multiply(new BigDecimal("1.015"));
                takeProfitExits++;
                winCount++;
            } else {
                // 손절 또는 만료
                if (stopLoss != null && stopLoss.compareTo(BigDecimal.ZERO) > 0) {
                    exitReason = "STOP_LOSS";
                    exitPrice = stopLoss;
                    stopLossExits++;
                } else {
                    exitReason = "TIMEOUT";
                    exitPrice = entryPrice.multiply(new BigDecimal("0.99")); // 가정: 1% 손실
                    timeoutExits++;
                }
            }

            // 청산 금액 및 수수료
            BigDecimal exitAmount = quantity.multiply(exitPrice);
            BigDecimal exitFee = exitAmount.multiply(FEE_RATE);

            // 손익 계산
            BigDecimal profit = exitAmount.subtract(exitFee).subtract(positionSize).subtract(entryFee);
            BigDecimal returnPct = profit.divide(positionSize, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

            // 자본 업데이트
            capital = capital.add(profit);

            // 승/패 누적
            if (profit.compareTo(BigDecimal.ZERO) > 0) {
                totalWin = totalWin.add(profit);
            } else {
                totalLoss = totalLoss.add(profit.abs());
            }

            // 최대 낙폭 계산
            if (capital.compareTo(peakCapital) > 0) {
                peakCapital = capital;
            }
            BigDecimal drawdown = peakCapital.subtract(capital).divide(peakCapital, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }

            // 보유 기간 (expiration_time 또는 strategy에서 추출)
            int holdingHours = signal.getHoldingHours();
            totalHoldingHours = totalHoldingHours.add(BigDecimal.valueOf(holdingHours));

            // 포지션 종료 시각 업데이트
            currentPositionEnd = signal.getSignalTime().plusHours(holdingHours);

            // 거래 내역 기록
            TakeProfitStopLossBacktestResponse.TradeDetail trade = TakeProfitStopLossBacktestResponse.TradeDetail.builder()
                .tradeNumber(++totalTrades)
                .entryDate(signal.getSignalTime().toLocalDate())
                .entryDateTime(signal.getSignalTime())
                .entryPrice(entryPrice)
                .exitDate(currentPositionEnd.toLocalDate())
                .exitDateTime(currentPositionEnd)
                .exitPrice(exitPrice)
                .takeProfitPrice(takeProfit)
                .stopLossPrice(stopLoss)
                .positionSize(positionSize)
                .investmentRatio(investmentRatio) // suggested_weight 반영
                .profit(profit)
                .returnPct(returnPct)
                .exitReason(exitReason)
                .holdingDays(BigDecimal.valueOf(holdingHours).divide(new BigDecimal("24"), 2, RoundingMode.HALF_UP))
                .predProbaUp(signal.getConfidence())
                .confidence(signal.getConfidence())
                .capitalAfter(capital)
                // CUSUM 전용 필드
                .strategy(signal.getStrategy())
                .mlModel(signal.getModel())
                .cusumSelectivity(signal.getCusumSelectivityPct())
                .threshold(signal.getThreshold())
                .isCorrect(signal.getCorrect())
                .build();

            tradeHistory.add(trade);
        }

        // 최종 통계 계산
        BigDecimal totalReturnPct = capital.subtract(initialCapital)
            .divide(initialCapital, 6, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));

        BigDecimal winRate = totalTrades > 0 ?
            BigDecimal.valueOf(winCount).divide(BigDecimal.valueOf(totalTrades), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")) : BigDecimal.ZERO;

        BigDecimal avgHoldingDays = totalTrades > 0 ?
            totalHoldingHours.divide(BigDecimal.valueOf(totalTrades), 2, RoundingMode.HALF_UP)
                .divide(new BigDecimal("24"), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        BigDecimal avgWin = winCount > 0 ?
            totalWin.divide(BigDecimal.valueOf(winCount), 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        int lossCount = totalTrades - winCount;
        BigDecimal avgLoss = lossCount > 0 ?
            totalLoss.divide(BigDecimal.valueOf(lossCount), 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        BigDecimal winLossRatio = avgLoss.compareTo(BigDecimal.ZERO) > 0 ?
            avgWin.divide(avgLoss, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        // 시작/종료일
        LocalDate startDate = signals.stream()
            .map(s -> s.getSignalTime().toLocalDate())
            .min(Comparator.naturalOrder())
            .orElse(null);

        LocalDate endDate = signals.stream()
            .map(s -> s.getSignalTime().toLocalDate())
            .max(Comparator.naturalOrder())
            .orElse(null);

        // 모델명 결정
        String modelName = buildModelName(foldNumber, strategy, model);

        // CUSUM 평균 계산
        BigDecimal avgConfidence = confidenceCount > 0 ?
            totalConfidence.divide(BigDecimal.valueOf(confidenceCount), 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal avgSelectivity = selectivityCount > 0 ?
            totalSelectivity.divide(BigDecimal.valueOf(selectivityCount), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal avgInvestmentRatio = investmentRatioCount > 0 ?
            totalInvestmentRatio.divide(BigDecimal.valueOf(investmentRatioCount), 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        // 전략 타임프레임 및 타입 추출 (첫 번째 신호에서)
        String strategyTimeframe = null;
        String strategyType = null;
        if (!signals.isEmpty()) {
            CusumSignalData firstSignal = signals.get(0);
            strategyTimeframe = firstSignal.getStrategyTimeframe();
            strategyType = firstSignal.getStrategyType();
        }

        log.info("CUSUM 백테스팅 완료: {} trades, {} → {} ({}%)",
            totalTrades, initialCapital, capital, totalReturnPct);

        return TakeProfitStopLossBacktestResponse.builder()
            .modelName(modelName)
            .foldNumber(foldNumber)
            .regime("CUSUM")
            .startDate(startDate)
            .endDate(endDate)
            .initialCapital(initialCapital)
            .finalCapital(capital.setScale(0, RoundingMode.HALF_UP))
            .totalReturnPct(totalReturnPct.setScale(2, RoundingMode.HALF_UP))
            .totalTrades(totalTrades)
            .takeProfitExits(takeProfitExits)
            .stopLossExits(stopLossExits)
            .timeoutExits(timeoutExits)
            .winRate(winRate.setScale(2, RoundingMode.HALF_UP))
            .avgHoldingDays(avgHoldingDays)
            .maxDrawdown(maxDrawdown.setScale(2, RoundingMode.HALF_UP))
            .sharpeRatio(BigDecimal.ZERO) // 추후 계산
            .avgWin(avgWin)
            .avgLoss(avgLoss)
            .winLossRatio(winLossRatio)
            .tradeHistory(tradeHistory)
            // CUSUM 전용 필드
            .strategy(strategy)
            .mlModel(model)
            .strategyTimeframe(strategyTimeframe)
            .strategyType(strategyType)
            .avgConfidence(avgConfidence)
            .avgSelectivity(avgSelectivity)
            .avgInvestmentRatio(avgInvestmentRatio)
            .build();
    }

    private String buildModelName(Integer foldNumber, String strategy, String model) {
        StringBuilder sb = new StringBuilder("CUSUM");
        if (strategy != null) {
            sb.append("_").append(strategy);
        } else if (model != null) {
            sb.append("_").append(model);
        }
        return sb.toString();
    }

    private TakeProfitStopLossBacktestResponse buildEmptyResponse(
            Integer foldNumber, String strategy, String model, BigDecimal initialCapital) {

        return TakeProfitStopLossBacktestResponse.builder()
            .modelName(buildModelName(foldNumber, strategy, model))
            .foldNumber(foldNumber)
            .regime("CUSUM")
            .initialCapital(initialCapital)
            .finalCapital(initialCapital)
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
            .tradeHistory(Collections.emptyList())
            .build();
    }

    /**
     * 전략별 요약 통계 조회
     */
    public Map<String, Object> getStrategySummary() {
        if (!isDataLoaded()) {
            return Collections.singletonMap("error", "데이터가 로드되지 않았습니다");
        }

        Map<String, Long> strategyCount = allSignals.stream()
            .filter(CusumSignalData::isBuySignal)
            .collect(Collectors.groupingBy(
                s -> s.getStrategy() != null ? s.getStrategy() : "unknown",
                Collectors.counting()
            ));

        Map<String, Long> modelCount = allSignals.stream()
            .filter(CusumSignalData::isBuySignal)
            .collect(Collectors.groupingBy(
                s -> s.getModel() != null ? s.getModel() : "unknown",
                Collectors.counting()
            ));

        Map<String, Long> foldCount = allSignals.stream()
            .filter(CusumSignalData::isBuySignal)
            .collect(Collectors.groupingBy(
                s -> "Fold " + s.getFoldId(),
                Collectors.counting()
            ));

        long totalBuySignals = allSignals.stream().filter(CusumSignalData::isBuySignal).count();
        long correctBuySignals = allSignals.stream()
            .filter(CusumSignalData::isBuySignal)
            .filter(s -> Boolean.TRUE.equals(s.getCorrect()))
            .count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalSignals", allSignals.size());
        summary.put("totalBuySignals", totalBuySignals);
        summary.put("correctBuySignals", correctBuySignals);
        summary.put("overallAccuracy", totalBuySignals > 0 ?
            String.format("%.2f%%", (correctBuySignals * 100.0 / totalBuySignals)) : "N/A");
        summary.put("byStrategy", strategyCount);
        summary.put("byModel", modelCount);
        summary.put("byFold", foldCount);

        return summary;
    }
}
