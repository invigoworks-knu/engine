package com.coinreaders.engine.application.backtest;

import com.coinreaders.engine.application.backtest.dto.CusumSignalData;
import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestResponse;
import com.coinreaders.engine.domain.entity.HistoricalMinuteOhlcv;
import com.coinreaders.engine.domain.repository.HistoricalMinuteOhlcvRepository;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.stream.Stream;

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
    private static final String MARKET = "KRW-ETH"; // 백테스팅 대상 마켓
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0004"); // 0.04% 편도 수수료
    private static final BigDecimal DEFAULT_POSITION_RATIO = new BigDecimal("0.8"); // 기본값: 자본의 80%
    private static final int SCALE = 8; // 소수점 자릿수

    // 1분봉 데이터 Repository (실제 가격 추적용)
    private final HistoricalMinuteOhlcvRepository minuteOhlcvRepository;

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
     * 전체 CSV 데이터를 필터링 없이 로드합니다.
     */
    public int loadCsvData() throws IOException, CsvValidationException {
        log.info("CUSUM 신호 CSV 로딩 시작 (전체 데이터 로드): {}", CSV_PATH);

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

            log.info("CUSUM 신호 CSV 로딩 완료: 총 {}건, 파싱 실패 {}건",
                successCount, failCount);
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
    @Transactional(readOnly = true)
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

        BigDecimal totalHoldingHours = BigDecimal.ZERO;
        BigDecimal peakCapital = initialCapital;
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        // TP/SL 손익 집계 (전략 예측력 평가용)
        BigDecimal tpWinSum = BigDecimal.ZERO;
        BigDecimal slLossSum = BigDecimal.ZERO;

        // Timeout 손익 집계 (별도 참고용)
        BigDecimal timeoutProfitSum = BigDecimal.ZERO;

        // CUSUM 집계용 변수
        BigDecimal totalConfidence = BigDecimal.ZERO;
        BigDecimal totalSelectivity = BigDecimal.ZERO;
        BigDecimal totalInvestmentRatio = BigDecimal.ZERO;
        int confidenceCount = 0;
        int selectivityCount = 0;
        int investmentRatioCount = 0;

        // 스킵 카운터
        int skippedNoMinuteData = 0; // 1분봉 없어서 스킵
        int skippedOverlap = 0; // 포지션 중복으로 스킵

        LocalDateTime currentPositionEnd = null; // 현재 포지션 종료 시각 (중복 진입 방지)

        for (CusumSignalData signal : signals) {
            // 이미 포지션이 있으면 스킵 (중복 진입 방지)
            if (currentPositionEnd != null && signal.getSignalTime().isBefore(currentPositionEnd)) {
                skippedOverlap++;
                continue;
            }

            // 1. 실제 진입 시각의 1분봉 찾기 (CSV의 signal_time 이후 첫 1분봉)
            Optional<HistoricalMinuteOhlcv> entryCandle = minuteOhlcvRepository
                .findFirstByMarketAndCandleDateTimeKstGreaterThanEqualOrderByCandleDateTimeKstAsc(
                    MARKET, signal.getSignalTime()
                );

            if (entryCandle.isEmpty()) {
                skippedNoMinuteData++;
                continue;
            }

            // 실제 진입 가격 및 시각
            BigDecimal entryPrice = entryCandle.get().getOpeningPrice();
            LocalDateTime actualEntryTime = entryCandle.get().getCandleDateTimeKst();

            if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("진입 가격 오류: entryPrice={}", entryPrice);
                continue;
            }

            // TP/SL 가격 (실제 진입가 기준으로 재계산)
            // CSV의 TP/SL은 CSV 진입가(entry_price_ref) 기준이므로
            // 실제 진입가로 동일한 비율(%)을 적용하여 재계산
            BigDecimal csvEntryPrice = signal.getEntryPriceRef();
            BigDecimal csvTakeProfit = signal.getTakeProfit();
            BigDecimal csvStopLoss = signal.getStopLoss();

            if (csvEntryPrice == null || csvTakeProfit == null || csvStopLoss == null ||
                csvEntryPrice.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("CSV TP/SL 없음: signal={}", signal.getSignalTime());
                continue;
            }

            // CSV 기준 TP/SL 비율(%) 계산
            BigDecimal takeProfitPct = csvTakeProfit.subtract(csvEntryPrice)
                .divide(csvEntryPrice, 6, RoundingMode.HALF_UP);
            BigDecimal stopLossPct = csvStopLoss.subtract(csvEntryPrice)
                .divide(csvEntryPrice, 6, RoundingMode.HALF_UP);

            // 실제 진입가 기준으로 TP/SL 재계산
            BigDecimal takeProfit = entryPrice.multiply(BigDecimal.ONE.add(takeProfitPct))
                .setScale(0, RoundingMode.HALF_UP);
            BigDecimal stopLoss = entryPrice.multiply(BigDecimal.ONE.add(stopLossPct))
                .setScale(0, RoundingMode.HALF_UP);

            log.debug("TP/SL 재계산: CSV 진입가={}, 실제 진입가={}, TP={}({}%), SL={}({}%)",
                csvEntryPrice, entryPrice, takeProfit, takeProfitPct.multiply(new BigDecimal("100")),
                stopLoss, stopLossPct.multiply(new BigDecimal("100")));

            // 2. 포지션 크기 계산 (suggested_weight 사용)
            BigDecimal investmentRatio = signal.getInvestmentRatio();
            BigDecimal positionSize = capital.multiply(investmentRatio);

            if (positionSize.compareTo(BigDecimal.ONE) < 0) {
                log.debug("포지션 크기가 너무 작음 (< 1원), 거래 제외");
                continue;
            }

            // 진입 수수료 차감
            BigDecimal entryFee = positionSize.multiply(FEE_RATE).setScale(2, RoundingMode.UP);
            BigDecimal entryAmount = positionSize.subtract(entryFee);
            BigDecimal quantity = entryAmount.divide(entryPrice, SCALE, RoundingMode.DOWN);

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

            // 3. 1분봉 추적하여 TP/SL 도달 확인 (Look-ahead Bias 제거)
            // CSV의 정확한 만료 시각 사용 (전략명에서 추출하지 않음)
            LocalDateTime exitCheckStart = actualEntryTime; // 진입 봉부터 체크
            LocalDateTime exitCheckEnd = signal.getExpiration(); // CSV의 정확한 만료 시각!

            if (exitCheckEnd == null) {
                // CSV에 만료 시각이 없는 경우만 폴백 (전략명에서 추출)
                int holdingHours = signal.getHoldingHours();
                exitCheckEnd = actualEntryTime.plusHours(holdingHours);
                log.warn("CSV에 만료 시각 없음. 전략명({})에서 {}시간 추출", signal.getStrategy(), holdingHours);
            }

            String exitReason = null;
            BigDecimal exitPrice = null;
            LocalDateTime exitTime = null;
            HistoricalMinuteOhlcv lastCandle = null;

            try (Stream<HistoricalMinuteOhlcv> candleStream = minuteOhlcvRepository
                    .streamByMarketAndDateTimeRange(MARKET, exitCheckStart, exitCheckEnd)) {

                var iterator = candleStream.iterator();
                boolean hasData = false;

                while (iterator.hasNext()) {
                    HistoricalMinuteOhlcv candle = iterator.next();
                    hasData = true;
                    lastCandle = candle;

                    boolean tpReached = candle.getHighPrice().compareTo(takeProfit) >= 0;
                    boolean slReached = candle.getLowPrice().compareTo(stopLoss) <= 0;

                    // 진입 봉에서 TP/SL 둘 다 도달한 경우:
                    // 1분봉 내에서 실제 순서를 알 수 없으므로 종가 기준으로 판단
                    // - 종가 >= 진입가: 상승 추세 → TP 먼저 도달 가능성
                    // - 종가 < 진입가: 하락 추세 → SL 먼저 도달 가능성
                    boolean isEntryCandle = candle.getCandleDateTimeKst().equals(actualEntryTime);
                    if (isEntryCandle && tpReached && slReached) {
                        BigDecimal closePrice = candle.getTradePrice(); // 종가

                        // 종가와 시가(진입가)의 관계로 방향 판단
                        if (closePrice.compareTo(entryPrice) >= 0) {
                            // 양봉 또는 동일 → 상승 후 하락한 경우로 TP 먼저 도달 가능성
                            exitReason = "TAKE_PROFIT";
                            exitPrice = takeProfit;
                            takeProfitExits++;
                        } else {
                            // 음봉 → 하락 후 상승한 경우로 SL 먼저 도달 가능성
                            exitReason = "STOP_LOSS";
                            exitPrice = stopLoss;
                            stopLossExits++;
                        }
                        exitTime = candle.getCandleDateTimeKst();
                        log.debug("진입 봉에서 TP/SL 둘 다 도달: {} (시가: {}, 종가: {}, TP: {}, SL: {})",
                            exitReason, entryPrice, closePrice, takeProfit, stopLoss);
                        break;
                    }

                    // 일반 봉에서 TP/SL 둘 다 도달한 경우 (변동성이 큰 봉)
                    // 진입 봉과 동일한 로직 적용: 종가 기준으로 판단
                    if (tpReached && slReached) {
                        BigDecimal closePrice = candle.getTradePrice();
                        BigDecimal openPrice = candle.getOpeningPrice();

                        // 양봉이면 상승 추세 → TP 먼저, 음봉이면 하락 추세 → SL 먼저
                        if (closePrice.compareTo(openPrice) >= 0) {
                            exitReason = "TAKE_PROFIT";
                            exitPrice = takeProfit;
                            takeProfitExits++;
                        } else {
                            exitReason = "STOP_LOSS";
                            exitPrice = stopLoss;
                            stopLossExits++;
                        }
                        exitTime = candle.getCandleDateTimeKst();
                        log.debug("일반 봉에서 TP/SL 둘 다 도달: {} (시가: {}, 종가: {})",
                            exitReason, openPrice, closePrice);
                        break;
                    }

                    // TP만 도달
                    if (tpReached) {
                        exitReason = "TAKE_PROFIT";
                        exitPrice = takeProfit;
                        exitTime = candle.getCandleDateTimeKst();
                        takeProfitExits++;
                        break;
                    }

                    // SL만 도달
                    if (slReached) {
                        exitReason = "STOP_LOSS";
                        exitPrice = stopLoss;
                        exitTime = candle.getCandleDateTimeKst();
                        stopLossExits++;
                        break;
                    }
                }

                if (!hasData) {
                    log.warn("추적 기간 1분봉 데이터 없음: entry={}, exitCheckEnd={}",
                        actualEntryTime, exitCheckEnd);
                    continue;
                }
            }

            // 4. Timeout 처리 (TP/SL 미도달)
            if (exitReason == null && lastCandle != null) {
                exitReason = "TIMEOUT";
                exitPrice = lastCandle.getTradePrice(); // 마지막 1분봉의 종가
                exitTime = exitCheckEnd; // CSV의 정확한 만료 시각 사용 (HH:59 문제 해결)
                timeoutExits++;

                log.debug("TIMEOUT: 마지막 1분봉={}, 만료 시각={}",
                    lastCandle.getCandleDateTimeKst(), exitCheckEnd);
            }

            if (exitPrice == null || exitTime == null) {
                log.warn("청산 가격/시간 없음: signal={}", signal.getSignalTime());
                continue;
            }

            // 5. 손익 계산
            // - positionSize: 투자한 총 금액 (진입 수수료 포함)
            // - entryFee: 이미 positionSize에서 차감되어 entryAmount가 됨
            // - exitAmount: 청산 금액
            // - exitFee: 청산 수수료
            // profit = (청산 후 받는 금액) - (투자한 총 금액)
            //        = (exitAmount - exitFee) - positionSize
            // 주의: entryFee는 positionSize의 일부이므로 따로 빼면 안 됨!
            BigDecimal exitAmount = quantity.multiply(exitPrice);
            BigDecimal exitFee = exitAmount.multiply(FEE_RATE).setScale(2, RoundingMode.UP);
            BigDecimal profit = exitAmount.subtract(exitFee).subtract(positionSize);
            BigDecimal returnPct = profit.divide(positionSize, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

            // 청산 유형과 손익의 일관성 검증 (디버깅용)
            // - TP: 청산가 > 진입가 → 이익이어야 정상 (수수료 제외 시)
            // - SL: 청산가 < 진입가 → 손실이어야 정상 (수수료 제외 시)
            if ("TAKE_PROFIT".equals(exitReason) && exitPrice.compareTo(entryPrice) < 0) {
                log.warn("⚠️ 비정상: TP인데 청산가({}) < 진입가({})! 재계산 오류 의심. signal={}, TP={}, SL={}",
                    exitPrice, entryPrice, signal.getSignalTime(), takeProfit, stopLoss);
            } else if ("STOP_LOSS".equals(exitReason) && exitPrice.compareTo(entryPrice) > 0) {
                log.warn("⚠️ 비정상: SL인데 청산가({}) > 진입가({})! 재계산 오류 의심. signal={}, TP={}, SL={}",
                    exitPrice, entryPrice, signal.getSignalTime(), takeProfit, stopLoss);
            }

            // 자본 업데이트
            capital = capital.add(profit);

            // 승/패 누적 (전략 예측력 평가: TP=승, SL=패, Timeout=제외)
            if ("TAKE_PROFIT".equals(exitReason)) {
                // TP 도달 = 예측 성공
                tpWinSum = tpWinSum.add(profit);
            } else if ("STOP_LOSS".equals(exitReason)) {
                // SL 도달 = 예측 실패
                slLossSum = slLossSum.add(profit.abs());
            } else if ("TIMEOUT".equals(exitReason)) {
                // Timeout = 승률 계산 제외 (하지만 수익은 집계)
                timeoutProfitSum = timeoutProfitSum.add(profit);
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

            // 보유 기간 계산
            long actualHoldingMinutes = java.time.Duration.between(actualEntryTime, exitTime).toMinutes();
            BigDecimal actualHoldingDays = BigDecimal.valueOf(actualHoldingMinutes)
                .divide(new BigDecimal("1440"), 2, RoundingMode.HALF_UP); // 1440분 = 1일
            totalHoldingHours = totalHoldingHours.add(BigDecimal.valueOf(actualHoldingMinutes / 60.0));

            // 포지션 종료 시각 업데이트 (중복 진입 방지)
            currentPositionEnd = exitTime;

            // 6. 거래 내역 기록
            TakeProfitStopLossBacktestResponse.TradeDetail trade = TakeProfitStopLossBacktestResponse.TradeDetail.builder()
                .tradeNumber(++totalTrades)
                .entryDate(actualEntryTime.toLocalDate())
                .entryDateTime(actualEntryTime)
                .entryPrice(entryPrice)
                .exitDate(exitTime.toLocalDate())
                .exitDateTime(exitTime)
                .exitPrice(exitPrice)
                .takeProfitPrice(takeProfit)
                .stopLossPrice(stopLoss)
                .positionSize(positionSize)
                .investmentRatio(investmentRatio)
                .profit(profit)
                .returnPct(returnPct)
                .exitReason(exitReason)
                .holdingDays(actualHoldingDays)
                .predProbaUp(signal.getConfidence())
                .confidence(signal.getConfidence())
                .capitalAfter(capital)
                // CUSUM 전용 필드
                .strategy(signal.getStrategy())
                .mlModel(signal.getModel())
                .cusumSelectivity(signal.getCusumSelectivityPct())
                .threshold(signal.getThreshold())
                .isCorrect(signal.getCorrect()) // 참고용으로만 보관 (사용하지 않음)
                .build();

            tradeHistory.add(trade);
        }

        // 최종 통계 계산
        BigDecimal totalReturnPct = capital.subtract(initialCapital)
            .divide(initialCapital, 6, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));

        // 승률: TP / (TP + SL) * 100 (Timeout 제외!)
        int decidedTrades = takeProfitExits + stopLossExits; // TP/SL만 카운트
        BigDecimal winRate = decidedTrades > 0 ?
            BigDecimal.valueOf(takeProfitExits).divide(BigDecimal.valueOf(decidedTrades), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")) : BigDecimal.ZERO;

        BigDecimal avgHoldingDays = totalTrades > 0 ?
            totalHoldingHours.divide(BigDecimal.valueOf(totalTrades), 2, RoundingMode.HALF_UP)
                .divide(new BigDecimal("24"), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        // TP 거래의 평균 이익
        BigDecimal avgWin = takeProfitExits > 0 ?
            tpWinSum.divide(BigDecimal.valueOf(takeProfitExits), 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        // SL 거래의 평균 손실
        BigDecimal avgLoss = stopLossExits > 0 ?
            slLossSum.divide(BigDecimal.valueOf(stopLossExits), 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        // 손익비 (TP 평균 이익 / SL 평균 손실)
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
        log.info("청산 유형: TP {}건({}%), SL {}건({}%), Timeout {}건({}%)",
            takeProfitExits, decidedTrades > 0 ? String.format("%.1f", takeProfitExits * 100.0 / decidedTrades) : "N/A",
            stopLossExits, decidedTrades > 0 ? String.format("%.1f", stopLossExits * 100.0 / decidedTrades) : "N/A",
            timeoutExits, totalTrades > 0 ? String.format("%.1f", timeoutExits * 100.0 / totalTrades) : "N/A");
        log.info("승률 (TP/SL만): {}% (TP {} / 전체 TP+SL {})", winRate, takeProfitExits, decidedTrades);
        log.info("신호 처리: 전체 {}건 → 거래 {}건, 1분봉 없음 {}건, 포지션 중복 {}건",
            signals.size(), totalTrades, skippedNoMinuteData, skippedOverlap);

        if (skippedNoMinuteData > 0) {
            double skipRatio = (skippedNoMinuteData * 100.0) / signals.size();
            log.warn("⚠️  1분봉 부족으로 {}건({}%) 신호를 사용하지 못했습니다. '/api/v1/data/minute-candles/auto-fill'로 추가 수집하세요.",
                skippedNoMinuteData, String.format("%.1f", skipRatio));
        }

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

    /**
     * CSV 신호 데이터의 날짜 범위 조회
     * @return Map with "startDate" and "endDate" as LocalDate, or empty map if no data
     */
    public Map<String, LocalDate> getSignalDateRange() {
        if (!isDataLoaded() || allSignals.isEmpty()) {
            return Collections.emptyMap();
        }

        LocalDate minDate = allSignals.stream()
            .map(s -> s.getSignalTime().toLocalDate())
            .min(LocalDate::compareTo)
            .orElse(null);

        LocalDate maxDate = allSignals.stream()
            .map(s -> s.getSignalTime().toLocalDate())
            .max(LocalDate::compareTo)
            .orElse(null);

        if (minDate == null || maxDate == null) {
            return Collections.emptyMap();
        }

        Map<String, LocalDate> range = new HashMap<>();
        range.put("startDate", minDate);
        range.put("endDate", maxDate);
        return range;
    }
}
