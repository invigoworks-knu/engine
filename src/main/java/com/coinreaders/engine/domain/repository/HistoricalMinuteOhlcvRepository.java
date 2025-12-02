package com.coinreaders.engine.domain.repository;

import com.coinreaders.engine.domain.entity.HistoricalMinuteOhlcv;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface HistoricalMinuteOhlcvRepository extends JpaRepository<HistoricalMinuteOhlcv, Long> {

    /**
     * 특정 기간의 1분봉 데이터 조회 (시간 순서대로)
     * @param market "KRW-ETH"
     * @param startDateTime 시작 시각 (포함)
     * @param endDateTime 종료 시각 (미포함)
     * @return 1분봉 리스트
     */
    List<HistoricalMinuteOhlcv> findByMarketAndCandleDateTimeKstBetweenOrderByCandleDateTimeKstAsc(
        String market,
        LocalDateTime startDateTime,
        LocalDateTime endDateTime
    );

    /**
     * 특정 날짜의 모든 1분봉 조회 (1,440개)
     * @param market "KRW-ETH"
     * @param date 조회할 날짜
     * @return 해당 날짜의 1분봉 리스트
     */
    default List<HistoricalMinuteOhlcv> findByMarketAndDate(String market, LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        return findByMarketAndCandleDateTimeKstBetweenOrderByCandleDateTimeKstAsc(market, start, end);
    }

    /**
     * 특정 시각 이후의 첫 1분봉 조회 (진입 시각 확인용)
     * @param market "KRW-ETH"
     * @param dateTime 기준 시각
     * @return 첫 1분봉
     */
    Optional<HistoricalMinuteOhlcv> findFirstByMarketAndCandleDateTimeKstGreaterThanEqualOrderByCandleDateTimeKstAsc(
        String market,
        LocalDateTime dateTime
    );

    /**
     * 특정 마켓의 가장 오래된 1분봉 조회
     */
    Optional<HistoricalMinuteOhlcv> findFirstByMarketOrderByCandleDateTimeKstAsc(String market);

    /**
     * 특정 마켓의 가장 최신 1분봉 조회
     */
    Optional<HistoricalMinuteOhlcv> findFirstByMarketOrderByCandleDateTimeKstDesc(String market);
}
