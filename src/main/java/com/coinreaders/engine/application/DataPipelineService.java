package com.coinreaders.engine.application;

import com.coinreaders.engine.adapter.out.api.UpbitApiClient;
import com.coinreaders.engine.domain.entity.HistoricalOhlcv;
import com.coinreaders.engine.domain.repository.HistoricalOhlcvRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataPipelineService {

    private final UpbitApiClient upbitApiClient;
    private final HistoricalOhlcvRepository historicalOhlcvRepository;

    @Transactional
    public void initializeDayCandles(String market, int count) {
        log.info("Starting to initialize {} day candles for market: {}", count, market);

        // 1. Upbit API를 통해 데이터 호출 (Outbound Adapter)
        upbitApiClient.fetchDayCandles(market, count)
            // 2. DTO를 Entity로 변환 (Application 로직)
            .map(dto -> HistoricalOhlcv.of( // ⭐️ 'new()'와 'set' 대신 정적 팩토리 메서드 사용
                dto.getMarket(),
                LocalDateTime.parse(dto.getCandleDateTimeKst(), DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                dto.getOpeningPrice(),
                dto.getHighPrice(),
                dto.getLowPrice(),
                dto.getTradePrice(),
                dto.getCandleAccTradeVolume()
            ))
            // 3. DB에 저장 (Outbound Port -> Adapter)
            .doOnNext(historicalOhlcvRepository::save)
            .doOnComplete(() -> log.info("Successfully saved {} day candles for market: {}", count, market))
            .doOnError(e -> log.error("Failed to fetch or save day candles", e))
            .subscribe(); // 비동기(Flux) 작업을 구독(실행)
    }
}