package com.coinreaders.engine.domain.repository;

import com.coinreaders.engine.domain.entity.HistoricalOhlcv;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface HistoricalOhlcvRepository extends JpaRepository<HistoricalOhlcv, Long> {

    /**
     * 특정 마켓과 날짜로 OHLCV 데이터 조회
     * (업비트 일봉은 09:00:00 KST로 저장되므로 날짜 범위로 조회)
     */
    default Optional<HistoricalOhlcv> findByMarketAndDate(String market, LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.plusDays(1).atStartOfDay();
        return findFirstByMarketAndCandleDateTimeKstBetweenOrderByCandleDateTimeKstAsc(
            market, startOfDay, endOfDay
        );
    }

    Optional<HistoricalOhlcv> findFirstByMarketAndCandleDateTimeKstBetweenOrderByCandleDateTimeKstAsc(
        String market, LocalDateTime startInclusive, LocalDateTime endExclusive
    );

    /**
     * 특정 마켓의 기간별 OHLCV 데이터 조회 (정렬: 날짜 오름차순)
     */
    default List<HistoricalOhlcv> findByMarketAndDateRange(String market, LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();
        return findByMarketAndCandleDateTimeKstBetweenOrderByCandleDateTimeKstAsc(
            market, startDateTime, endDateTime
        );
    }

    List<HistoricalOhlcv> findByMarketAndCandleDateTimeKstBetweenOrderByCandleDateTimeKstAsc(
        String market, LocalDateTime startInclusive, LocalDateTime endExclusive
    );

    /**
     * 특정 마켓의 가장 오래된 데이터 조회
     */
    Optional<HistoricalOhlcv> findFirstByMarketOrderByCandleDateTimeKstAsc(String market);

    /**
     * 특정 마켓의 가장 최신 데이터 조회
     */
    Optional<HistoricalOhlcv> findFirstByMarketOrderByCandleDateTimeKstDesc(String market);
}
