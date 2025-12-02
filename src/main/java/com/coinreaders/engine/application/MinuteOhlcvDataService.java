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

        String lastFetchedTime = null;
        long totalCount = 0;
        int batchCount = 0;

        while (true) {
            // API 호출
            List<UpbitApiClient.UpbitMinuteCandleDto> candles = upbitApiClient
                .fetchMinuteCandles(MARKET, BATCH_SIZE, lastFetchedTime)
                .collectList()
                .block();

            if (candles == null || candles.isEmpty()) {
                log.info("API가 더 이상 데이터를 반환하지 않습니다. 중지.");
                break;
            }

            // DTO → Entity 변환
            List<HistoricalMinuteOhlcv> entities = new ArrayList<>();
            for (UpbitApiClient.UpbitMinuteCandleDto dto : candles) {
                try {
                    entities.add(toEntity(dto));
                } catch (Exception e) {
                    log.warn("DTO 변환 실패: {}", dto, e);
                }
            }

            // 배치 저장 (중복 방지: unique constraint로 자동 처리)
            try {
                minuteOhlcvRepository.saveAll(entities);
                totalCount += entities.size();
                batchCount++;
            } catch (Exception e) {
                log.warn("배치 저장 중 일부 중복 데이터 존재: {}", e.getMessage());
                // 중복 데이터는 무시하고 계속 진행
            }

            // 다음 조회 시각 설정 (가장 오래된 데이터의 시각)
            lastFetchedTime = candles.get(candles.size() - 1).getCandleDateTimeKst();

            // 목표 날짜 도달 확인
            LocalDateTime lastDateTime = LocalDateTime.parse(lastFetchedTime,
                DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            if (lastDateTime.toLocalDate().isBefore(start) ||
                lastDateTime.toLocalDate().isEqual(start)) {
                log.info("목표 날짜({})에 도달했습니다. 중지.", startDate);
                break;
            }

            // API Rate Limit 준수 (초당 10회 제한 → 안전하게 초당 6회)
            try {
                Thread.sleep(150); // 0.15초 대기
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("API 요청 지연 중 인터럽트 발생");
            }

            // 진행 상황 로깅
            if (batchCount % 50 == 0) {
                log.info("1분봉 적재 중: {}건 ({}번째 배치, 마지막 시각: {})",
                    totalCount, batchCount, lastFetchedTime);
            }
        }

        log.info("1분봉 데이터 적재 완료: 총 {}건 ({}번의 API 호출)", totalCount, batchCount);
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
