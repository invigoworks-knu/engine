package com.coinreaders.engine.application;

import com.coinreaders.engine.adapter.out.api.UpbitApiClient;
import com.coinreaders.engine.domain.entity.HistoricalOhlcv;
import com.coinreaders.engine.domain.repository.HistoricalOhlcvRepository;
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
public class DataPipelineService {

    private final UpbitApiClient upbitApiClient;
    private final HistoricalOhlcvRepository historicalOhlcvRepository;
    private static final int BATCH_SIZE = 200;

    /**
     * (기존 메서드) 최신 200개 캔들만 적재 (실거래 시 일일 갱신용)
     */
    @Transactional
    public void initializeDayCandles(String market, int count) {
        log.info("Starting to initialize {} day candles for market: {}", count, market);
        // 1. Upbit API를 통해 데이터 호출 (Outbound Adapter)
        upbitApiClient.fetchDayCandles(market, count, null) // 'to'는 null
            // 2. DTO를 Entity로 변환 (Application 로직)
            .map(this::mapDtoToEntity) // DTO -> Entity 변환 로직 분리
            // 3. DB에 저장 (Outbound Port -> Adapter)
            .doOnNext(historicalOhlcvRepository::save)
            .doOnComplete(() -> log.info("Successfully saved {} day candles for market: {}", count, market))
            .doOnError(e -> log.error("Failed to fetch or save day candles", e))
            .subscribe(); // 비동기(Flux) 작업을 구독(실행)
    }

    /**
     * (신규 메서드) 특정 날짜까지의 모든 과거 데이터를 API로 적재 (백테스팅 초기 구축용)
     * @param market "KRW-ETH"
     * @param stopDateStr "2017-11-09" (이 날짜를 포함할 때까지)
     */
    @Transactional
    public void loadAllHistoricalOhlcv(String market, String stopDateStr) {
        log.info("과거 전체 OHLCV 데이터 적재 시작 ({}까지)...", stopDateStr);

        // 1. 적재 전, 기존 데이터 모두 삭제
        historicalOhlcvRepository.deleteAllInBatch();
        log.info("기존 historical_ohlcv 테이블 데이터를 삭제했습니다.");

        final LocalDate stopDate = LocalDate.parse(stopDateStr);
        String lastFetchedDateStr = null; // 'to' 파라미터로 사용
        long totalCount = 0;

        while (true) {
            log.info("API 호출: {} 캔들 ({} 이전)", BATCH_SIZE, lastFetchedDateStr);

            // 2. API 호출을 동기식(blocking)으로 변경하여 루프 제어
            List<UpbitApiClient.UpbitDayCandleDto> candles = upbitApiClient
                .fetchDayCandles(market, BATCH_SIZE, lastFetchedDateStr)
                .collectList()
                .block(); // Flux -> List로 동기 변환

            if (candles == null || candles.isEmpty()) {
                log.info("API가 더 이상 데이터를 반환하지 않습니다. 중지.");
                break;
            }

            // 3. DTO -> Entity 변환 및 리스트에 추가
            List<HistoricalOhlcv> ohlcvList = new ArrayList<>();
            for (UpbitApiClient.UpbitDayCandleDto dto : candles) {
                ohlcvList.add(mapDtoToEntity(dto));
            }

            // 4. DB에 일괄 저장 (Batch Insert)
            historicalOhlcvRepository.saveAll(ohlcvList);
            totalCount += ohlcvList.size();

            // 5. 다음 'to' 파라미터 설정 (가져온 데이터 중 가장 오래된 날짜)
            UpbitApiClient.UpbitDayCandleDto lastCandle = candles.get(candles.size() - 1);
            lastFetchedDateStr = lastCandle.getCandleDateTimeKst(); // "2025-11-03T09:00:00"

            // 6. 중지 날짜 확인 (KST 날짜의 T09:00:00이므로 T 이전의 날짜 부분만 파싱)
            LocalDateTime lastFetchedKst = LocalDateTime.parse(lastFetchedDateStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            if (lastFetchedKst.toLocalDate().isBefore(stopDate) || lastFetchedKst.toLocalDate().isEqual(stopDate)) {
                log.info("목표 날짜({})에 도달했습니다. 중지.", stopDateStr);
                break;
            }

            // 7. Upbit API 분당 600회, 초당 10회 Rate Limit 준수를 위한 지연
            try {
                Thread.sleep(200); // 0.2초 대기
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("API 요청 지연 중 인터럽트 발생");
            }
        }
        log.info("과거 전체 OHLCV 데이터 적재 완료. 총 {}건", totalCount);
    }

    /**
     * DTO를 엔티티로 변환하는 헬퍼 메서드
     */
    private HistoricalOhlcv mapDtoToEntity(UpbitApiClient.UpbitDayCandleDto dto) {
        return HistoricalOhlcv.of(
            dto.getMarket(),
            LocalDateTime.parse(dto.getCandleDateTimeKst(), DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            dto.getOpeningPrice(),
            dto.getHighPrice(),
            dto.getLowPrice(),
            dto.getTradePrice(),
            dto.getCandleAccTradeVolume()
        );
    }
}