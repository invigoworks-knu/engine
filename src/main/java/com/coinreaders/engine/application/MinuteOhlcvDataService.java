package com.coinreaders.engine.application;

import com.coinreaders.engine.adapter.out.api.UpbitApiClient;
import com.coinreaders.engine.application.backtest.CusumSignalBacktestService;
import com.coinreaders.engine.domain.entity.HistoricalMinuteOhlcv;
import com.coinreaders.engine.domain.repository.HistoricalMinuteOhlcvRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinuteOhlcvDataService {

    private final UpbitApiClient upbitApiClient;
    private final HistoricalMinuteOhlcvRepository minuteOhlcvRepository;
    private final CusumSignalBacktestService cusumSignalBacktestService;

    private static final String MARKET = "KRW-ETH";
    private static final int BATCH_SIZE = 200; // API 최대 반환 개수

    /**
     * Fold 1~7 기간의 모든 1분봉 데이터 적재
     * @param startDate "2022-12-07"
     * @param endDate "2025-10-21"
     */
    @Transactional
    public void loadAllMinuteCandles(String startDate, String endDate) {
        log.info("1분봉 데이터 적재 시작: {} ~ {}", startDate, endDate);

        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);

        // 1. DB에서 가장 오래된 데이터(가장 과거 시간)를 조회해 본다.
        HistoricalMinuteOhlcv oldestData = minuteOhlcvRepository
            .findFirstByMarketOrderByCandleDateTimeKstAsc(MARKET)
            .orElse(null);

        String lastFetchedTime;

        // 2. 데이터가 있다면, 그 시간부터 더 과거를 가져오도록 설정 (이어하기)
        if (oldestData != null) {
            // KST를 UTC로 변환하여 전송 (Upbit API는 타임존 없는 시각을 UTC로 해석)
            Instant instant = oldestData.getCandleDateTimeKst().atZone(ZoneId.of("Asia/Seoul")).toInstant();
            lastFetchedTime = instant.atZone(ZoneId.of("UTC"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            log.info("기존 데이터 발견! KST {} → UTC {} 부터 과거 데이터 적재를 이어갑니다.",
                oldestData.getCandleDateTimeKst(), lastFetchedTime);
        } else {
            // 3. 데이터가 없다면, 원래대로 입력받은 endDate부터 시작
            // KST를 UTC로 변환
            Instant instant = end.atTime(23, 59, 59).atZone(ZoneId.of("Asia/Seoul")).toInstant();
            lastFetchedTime = instant.atZone(ZoneId.of("UTC"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            log.info("기존 데이터 없음. KST {} 23:59:59 → UTC {} 부터 적재를 시작합니다.", end, lastFetchedTime);
        }


        long totalCount = 0;
        int batchCount = 0;
        int consecutiveSkipBatches = 0; // 연속으로 모든 데이터가 스킵된 배치 수

        while (true) {
            // 1. API 호출 (ISO 8601 형식 유지)
            List<UpbitApiClient.UpbitMinuteCandleDto> candles = upbitApiClient
                .fetchMinuteCandles(MARKET, BATCH_SIZE, lastFetchedTime)
                .collectList()
                .block();

            if (candles == null || candles.isEmpty()) {
                log.info("API가 더 이상 데이터를 반환하지 않습니다. 중지.");
                break;
            }

            // 2. DTO -> Entity 변환
            List<HistoricalMinuteOhlcv> entities = new ArrayList<>();
            for (UpbitApiClient.UpbitMinuteCandleDto dto : candles) {
                try {
                    entities.add(toEntity(dto));
                } catch (Exception e) {
                    log.warn("DTO 변환 실패: {}", dto, e);
                }
            }

            if (entities.isEmpty()) {
                log.info("변환된 엔티티가 없습니다. 다음 배치로 이동.");
                break;
            }

            // 3. [개선된 로직] 사전 중복 체크 후 필터링
            // 3-1. 모든 timestamp 추출
            List<LocalDateTime> dateTimes = entities.stream()
                .map(HistoricalMinuteOhlcv::getCandleDateTimeKst)
                .collect(Collectors.toList());

            // 3-2. DB에 이미 존재하는 timestamp 조회
            Set<LocalDateTime> existingDateTimes = new HashSet<>(
                minuteOhlcvRepository.findExistingDateTimes(MARKET, dateTimes)
            );

            // 3-3. 존재하지 않는 것만 필터링
            List<HistoricalMinuteOhlcv> newEntities = entities.stream()
                .filter(entity -> !existingDateTimes.contains(entity.getCandleDateTimeKst()))
                .collect(Collectors.toList());

            // 3-4. 새로운 데이터만 배치로 저장
            int savedInBatch = 0;
            if (!newEntities.isEmpty()) {
                minuteOhlcvRepository.saveAll(newEntities);
                savedInBatch = newEntities.size();
                totalCount += savedInBatch;
            }

            batchCount++;
            int skippedInBatch = entities.size() - savedInBatch;

            // 4. [개선] 다음 조회 시각 갱신 (저장 여부에 따라 다르게 처리)
            if (savedInBatch > 0) {
                // 4-1. 새로운 데이터가 저장되었으면, 저장된 것 중 가장 오래된(과거) 시간으로 갱신
                LocalDateTime oldestSavedTime = newEntities.stream()
                    .map(HistoricalMinuteOhlcv::getCandleDateTimeKst)
                    .min(LocalDateTime::compareTo)
                    .orElse(null);

                if (oldestSavedTime != null) {
                    // KST를 UTC로 변환
                    Instant instant = oldestSavedTime.atZone(ZoneId.of("Asia/Seoul")).toInstant();
                    lastFetchedTime = instant.atZone(ZoneId.of("UTC"))
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
                }
                consecutiveSkipBatches = 0; // 리셋
            } else {
                // 4-2. 모든 데이터가 스킵되었으면, 현재 배치의 가장 오래된 시간 - 1분으로 갱신
                LocalDateTime oldestInBatch = LocalDateTime.parse(
                    candles.get(candles.size() - 1).getCandleDateTimeKst(),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME
                );
                // KST를 UTC로 변환
                Instant instant = oldestInBatch.minusMinutes(1).atZone(ZoneId.of("Asia/Seoul")).toInstant();
                lastFetchedTime = instant.atZone(ZoneId.of("UTC"))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
                consecutiveSkipBatches++;

                // 4-3. 연속으로 3번 이상 모든 데이터가 스킵되면 종료 (더 이상 새 데이터 없음)
                if (consecutiveSkipBatches >= 3) {
                    log.info("연속 {}번 모든 데이터 중복. 더 이상 새로운 데이터가 없다고 판단하여 종료합니다.", consecutiveSkipBatches);
                    break;
                }
            }

            // 5. 종료 조건 확인 (UTC를 KST로 변환하여 비교)
            LocalDateTime lastDateTimeUtc = LocalDateTime.parse(lastFetchedTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            LocalDateTime lastDateTimeKst = lastDateTimeUtc.atZone(ZoneId.of("UTC"))
                .withZoneSameInstant(ZoneId.of("Asia/Seoul"))
                .toLocalDateTime();
            if (lastDateTimeKst.toLocalDate().isBefore(start)) {
                log.info("목표 날짜({})에 도달했습니다. 중지.", startDate);
                break;
            }

            // 6. 속도 조절
            try {
                Thread.sleep(100); // 0.1초 대기
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 로그 출력 (개선됨)
            if (batchCount % 10 == 0) {
                log.info("진행 중: 총 {}건 저장 (이번 배치 - 저장: {}건, 스킵: {}건, 현재 시점: {})",
                    totalCount, savedInBatch, skippedInBatch, lastFetchedTime);
            } else if (skippedInBatch > 0 && savedInBatch == 0) {
                // 모든 데이터가 중복인 경우 경고 로그 (연속 횟수 표시)
                log.warn("배치 #{}: 모든 데이터 중복으로 스킵 ({}건) - 연속 스킵: {}회",
                    batchCount, skippedInBatch, consecutiveSkipBatches);
            }
        }

        log.info("1분봉 데이터 적재 완료: 총 {}건", totalCount);
    }

    /**
     * DTO를 Entity로 변환
     */
    private HistoricalMinuteOhlcv toEntity(UpbitApiClient.UpbitMinuteCandleDto dto) {
        return HistoricalMinuteOhlcv.of(
            dto.getMarket(),
            LocalDateTime.parse(dto.getCandleDateTimeKst(), DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            dto.getOpeningPrice(),
            dto.getHighPrice(),
            dto.getLowPrice(),
            dto.getTradePrice(),
            dto.getCandleAccTradeVolume()
        );
    }

    /**
     * 1분봉 데이터 통계 조회
     */
    public String getMinuteCandleStats() {
        long totalCount = minuteOhlcvRepository.count();

        HistoricalMinuteOhlcv oldest = minuteOhlcvRepository
            .findFirstByMarketOrderByCandleDateTimeKstAsc(MARKET)
            .orElse(null);

        HistoricalMinuteOhlcv latest = minuteOhlcvRepository
            .findFirstByMarketOrderByCandleDateTimeKstDesc(MARKET)
            .orElse(null);

        if (oldest == null || latest == null) {
            return "1분봉 데이터 없음";
        }

        return String.format("1분봉 데이터: 총 %d건, 시작 %s, 종료 %s",
            totalCount,
            oldest.getCandleDateTimeKst(),
            latest.getCandleDateTimeKst());
    }

    /**
     * CSV 신호 기간을 커버하도록 1분봉 데이터 자동 수집
     * CSV 신호의 최소/최대 날짜를 확인하고, 현재 1분봉 데이터 범위와 비교하여 부족한 과거 데이터를 자동으로 수집
     */
    @Transactional
    public String collectMinuteCandlesToCoverSignals() {
        log.info("CSV 신호 기간 기반 1분봉 자동 수집 시작");

        // 1. CSV 신호 날짜 범위 조회
        Map<String, LocalDate> signalRange = cusumSignalBacktestService.getSignalDateRange();
        if (signalRange.isEmpty()) {
            String msg = "CSV 신호 데이터가 로드되지 않았습니다. 먼저 백테스팅을 실행하거나 CSV를 로드하세요.";
            log.warn(msg);
            return msg;
        }

        LocalDate csvStartDate = signalRange.get("startDate");
        LocalDate csvEndDate = signalRange.get("endDate");
        log.info("CSV 신호 기간: {} ~ {}", csvStartDate, csvEndDate);

        // 2. 현재 1분봉 데이터 범위 조회
        HistoricalMinuteOhlcv oldestCandle = minuteOhlcvRepository
            .findFirstByMarketOrderByCandleDateTimeKstAsc(MARKET)
            .orElse(null);

        HistoricalMinuteOhlcv latestCandle = minuteOhlcvRepository
            .findFirstByMarketOrderByCandleDateTimeKstDesc(MARKET)
            .orElse(null);

        if (oldestCandle == null || latestCandle == null) {
            // 1분봉 데이터가 전혀 없는 경우, CSV 전체 기간 수집
            log.info("1분봉 데이터 없음. CSV 전체 기간 수집: {} ~ {}", csvStartDate, csvEndDate);
            loadAllMinuteCandles(csvStartDate.toString(), csvEndDate.toString());
            return String.format("1분봉 데이터 수집 완료: %s ~ %s", csvStartDate, csvEndDate);
        }

        LocalDate candleStartDate = oldestCandle.getCandleDateTimeKst().toLocalDate();
        LocalDate candleEndDate = latestCandle.getCandleDateTimeKst().toLocalDate();
        log.info("현재 1분봉 기간: {} ~ {}", candleStartDate, candleEndDate);

        // 3. 갭 분석 및 수집
        StringBuilder result = new StringBuilder();

        // 3-1. 과거 데이터 부족 확인 (CSV 시작 < 1분봉 시작)
        if (csvStartDate.isBefore(candleStartDate)) {
            long gapDays = java.time.temporal.ChronoUnit.DAYS.between(csvStartDate, candleStartDate);
            log.info("과거 데이터 부족 감지: CSV 시작({})이 1분봉 시작({})보다 {}일 빠름",
                csvStartDate, candleStartDate, gapDays);

            result.append(String.format("과거 데이터 수집 중: %s ~ %s (%d일)\n",
                csvStartDate, candleStartDate.minusDays(1), gapDays));

            // 과거 데이터 수집 (CSV 시작 ~ 현재 1분봉 시작 -1일)
            loadAllMinuteCandles(csvStartDate.toString(), candleStartDate.minusDays(1).toString());

            result.append("과거 데이터 수집 완료\n");
        } else {
            log.info("과거 데이터 충분: 1분봉 시작({})이 CSV 시작({}) 이전 또는 같음",
                candleStartDate, csvStartDate);
            result.append("과거 데이터 충분 (추가 수집 불필요)\n");
        }

        // 3-2. 미래 데이터 부족 확인 (CSV 종료 > 1분봉 종료)
        if (csvEndDate.isAfter(candleEndDate)) {
            long gapDays = java.time.temporal.ChronoUnit.DAYS.between(candleEndDate, csvEndDate);
            log.info("미래 데이터 부족 감지: CSV 종료({})가 1분봉 종료({})보다 {}일 늦음",
                csvEndDate, candleEndDate, gapDays);

            result.append(String.format("미래 데이터 수집 중: %s ~ %s (%d일)\n",
                candleEndDate.plusDays(1), csvEndDate, gapDays));

            // 미래 데이터 수집 (현재 1분봉 종료 +1일 ~ CSV 종료)
            // 참고: loadAllMinuteCandles는 과거로 가는 로직이므로, 미래 데이터는 별도 처리 필요
            // 현재는 과거 데이터만 수집하도록 구현됨
            result.append("주의: 현재 수집 로직은 과거 방향만 지원. 미래 데이터는 수동으로 수집하세요.\n");
        } else {
            log.info("미래 데이터 충분: 1분봉 종료({})가 CSV 종료({}) 이후 또는 같음",
                candleEndDate, csvEndDate);
            result.append("미래 데이터 충분 (추가 수집 불필요)\n");
        }

        // 4. 최종 상태 보고
        String finalStats = getMinuteCandleStats();
        result.append("\n").append(finalStats);

        log.info("CSV 신호 기간 기반 1분봉 자동 수집 완료");
        return result.toString();
    }
}
