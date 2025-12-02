package com.coinreaders.engine.application;

import com.coinreaders.engine.adapter.out.api.UpbitApiClient;
import com.coinreaders.engine.domain.entity.HistoricalMinuteOhlcv;
import com.coinreaders.engine.domain.repository.HistoricalMinuteOhlcvRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinuteOhlcvDataService {

    private final UpbitApiClient upbitApiClient;
    private final HistoricalMinuteOhlcvRepository minuteOhlcvRepository;

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
            lastFetchedTime = oldestData.getCandleDateTimeKst().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            log.info("기존 데이터 발견! {} 부터 과거 데이터 적재를 이어갑니다.", lastFetchedTime);
        } else {
            // 3. 데이터가 없다면, 원래대로 입력받은 endDate부터 시작
            lastFetchedTime = end.atTime(23, 59, 59).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            log.info("기존 데이터 없음. {} 부터 적재를 시작합니다.", lastFetchedTime);
        }


        long totalCount = 0;
        int batchCount = 0;

        while (true) {
            // 1. API 호출 (T 제거)
            List<UpbitApiClient.UpbitMinuteCandleDto> candles = upbitApiClient
                .fetchMinuteCandles(MARKET, BATCH_SIZE, lastFetchedTime != null ? lastFetchedTime.replace("T", " ") : null)
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

            // 3. [중요 변경] 한 건씩 저장 (중복 에러나도 무시하고 다음 것 저장)
            int savedInBatch = 0;
            for (HistoricalMinuteOhlcv entity : entities) {
                try {
                    minuteOhlcvRepository.save(entity);
                    savedInBatch++;
                    totalCount++;
                } catch (Exception e) {
                    // 중복 에러 발생 시 무시하고 넘어감 (로그 생략 가능 or 디버그)
                    // log.debug("중복 데이터 건너뜀: {}", entity.getCandleDateTimeKst());
                }
            }
            batchCount++;

            // 4. 다음 조회 시각 갱신 (무조건 실행됨)
            // T가 포함된 원본 시간 포맷을 유지하여 저장
            lastFetchedTime = candles.get(candles.size() - 1).getCandleDateTimeKst();

            // 5. 종료 조건 확인
            LocalDateTime lastDateTime = LocalDateTime.parse(lastFetchedTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            if (lastDateTime.toLocalDate().isBefore(start) || lastDateTime.toLocalDate().isEqual(start)) {
                log.info("목표 날짜({})에 도달했습니다. 중지.", startDate);
                break;
            }

            // 6. 속도 조절
            try {
                Thread.sleep(100); // 0.1초 대기
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 로그 출력
            if (batchCount % 10 == 0) {
                log.info("진행 중: 총 {}건 저장 (이번 배치 저장: {}건, 현재 시점: {})",
                    totalCount, savedInBatch, lastFetchedTime);
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
}
