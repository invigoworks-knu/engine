package com.coinreaders.engine.domain.repository;

import com.coinreaders.engine.domain.entity.HistoricalMinuteOhlcv;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

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

    /**
     * 특정 기간의 1분봉 데이터 조회 (Stream 방식 - 메모리 최적화)
     * @param market "KRW-ETH"
     * @param startDateTime 시작 시각 (포함)
     * @param endDateTime 종료 시각 (미포함)
     * @return 1분봉 Stream (사용 후 반드시 close 필요)
     */
    @QueryHints(value = @QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_FETCH_SIZE, value = "50"))
    @Query("SELECT m FROM HistoricalMinuteOhlcv m WHERE m.market = :market " +
           "AND m.candleDateTimeKst >= :startDateTime AND m.candleDateTimeKst < :endDateTime " +
           "ORDER BY m.candleDateTimeKst ASC")
    Stream<HistoricalMinuteOhlcv> streamByMarketAndDateTimeRange(
        @Param("market") String market,
        @Param("startDateTime") LocalDateTime startDateTime,
        @Param("endDateTime") LocalDateTime endDateTime
    );

    /**
     * 특정 마켓의 여러 시각 중 이미 존재하는 시각들만 조회
     * (중복 체크용 - 배치 처리 최적화)
     * @param market "KRW-ETH"
     * @param dateTimes 확인할 시각 리스트
     * @return DB에 이미 존재하는 시각 리스트
     */
    @Query("SELECT m.candleDateTimeKst FROM HistoricalMinuteOhlcv m " +
           "WHERE m.market = :market AND m.candleDateTimeKst IN :dateTimes")
    List<LocalDateTime> findExistingDateTimes(
        @Param("market") String market,
        @Param("dateTimes") List<LocalDateTime> dateTimes
    );
}
