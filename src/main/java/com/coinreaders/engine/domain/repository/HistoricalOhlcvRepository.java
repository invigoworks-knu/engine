package com.coinreaders.engine.domain.repository;

import com.coinreaders.engine.domain.entity.HistoricalOhlcv;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface HistoricalOhlcvRepository extends JpaRepository<HistoricalOhlcv, Long> {

    /**
     * 특정 마켓과 날짜로 OHLCV 데이터 조회
     * (업비트 일봉은 09:00:00 KST로 저장되므로 날짜 범위로 조회)
     */
    @Query("SELECT h FROM HistoricalOhlcv h WHERE h.market = :market " +
           "AND DATE(h.candleDateTimeKst) = :date")
    Optional<HistoricalOhlcv> findByMarketAndDate(@Param("market") String market,
                                                   @Param("date") LocalDate date);

    /**
     * 특정 마켓의 기간별 OHLCV 데이터 조회 (정렬: 날짜 오름차순)
     */
    @Query("SELECT h FROM HistoricalOhlcv h WHERE h.market = :market " +
           "AND DATE(h.candleDateTimeKst) >= :startDate " +
           "AND DATE(h.candleDateTimeKst) <= :endDate " +
           "ORDER BY h.candleDateTimeKst ASC")
    List<HistoricalOhlcv> findByMarketAndDateRange(@Param("market") String market,
                                                    @Param("startDate") LocalDate startDate,
                                                    @Param("endDate") LocalDate endDate);

    /**
     * 특정 마켓의 가장 오래된 데이터 조회
     */
    @Query("SELECT h FROM HistoricalOhlcv h WHERE h.market = :market " +
           "ORDER BY h.candleDateTimeKst ASC LIMIT 1")
    Optional<HistoricalOhlcv> findOldestByMarket(@Param("market") String market);

    /**
     * 특정 마켓의 가장 최신 데이터 조회
     */
    @Query("SELECT h FROM HistoricalOhlcv h WHERE h.market = :market " +
           "ORDER BY h.candleDateTimeKst DESC LIMIT 1")
    Optional<HistoricalOhlcv> findLatestByMarket(@Param("market") String market);
}