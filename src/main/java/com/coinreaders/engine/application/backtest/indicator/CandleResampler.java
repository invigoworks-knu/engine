package com.coinreaders.engine.application.backtest.indicator;

import com.coinreaders.engine.domain.entity.HistoricalMinuteOhlcv;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 1분봉 → N시간봉 리샘플러
 */
@Slf4j
public class CandleResampler {

    /**
     * 1분봉 데이터를 4시간봉으로 리샘플링
     *
     * @param minuteCandles 1분봉 데이터 (시간순 정렬 필수)
     * @return 4시간봉 데이터
     */
    public static List<FourHourCandle> resampleTo4Hour(List<HistoricalMinuteOhlcv> minuteCandles) {
        if (minuteCandles.isEmpty()) {
            return new ArrayList<>();
        }

        List<FourHourCandle> fourHourCandles = new ArrayList<>();
        List<HistoricalMinuteOhlcv> buffer = new ArrayList<>();

        LocalDateTime currentPeriodStart = null;

        for (HistoricalMinuteOhlcv candle : minuteCandles) {
            LocalDateTime candleTime = candle.getCandleDateTimeKst();

            // 4시간봉 시작 시각 결정 (01:00, 05:00, 09:00, 13:00, 17:00, 21:00)
            LocalDateTime periodStart = get4HourPeriodStart(candleTime);

            // 새로운 4시간 기간 시작
            if (currentPeriodStart == null || !periodStart.equals(currentPeriodStart)) {
                // 이전 버퍼를 4시간봉으로 변환
                if (!buffer.isEmpty()) {
                    fourHourCandles.add(aggregate4HourCandle(buffer, currentPeriodStart));
                }

                // 새 기간 시작
                buffer.clear();
                currentPeriodStart = periodStart;
            }

            buffer.add(candle);
        }

        // 마지막 버퍼 처리
        if (!buffer.isEmpty()) {
            fourHourCandles.add(aggregate4HourCandle(buffer, currentPeriodStart));
        }

        log.debug("리샘플링 완료: 1분봉 {}개 → 4시간봉 {}개", minuteCandles.size(), fourHourCandles.size());
        return fourHourCandles;
    }

    /**
     * 4시간 기간의 시작 시각 계산
     * 01:00, 05:00, 09:00, 13:00, 17:00, 21:00 중 하나
     */
    private static LocalDateTime get4HourPeriodStart(LocalDateTime time) {
        int hour = time.getHour();

        // 4시간 단위로 나누기 (01, 05, 09, 13, 17, 21)
        int periodStartHour;
        if (hour >= 1 && hour < 5) {
            periodStartHour = 1;
        } else if (hour >= 5 && hour < 9) {
            periodStartHour = 5;
        } else if (hour >= 9 && hour < 13) {
            periodStartHour = 9;
        } else if (hour >= 13 && hour < 17) {
            periodStartHour = 13;
        } else if (hour >= 17 && hour < 21) {
            periodStartHour = 17;
        } else if (hour >= 21) {
            periodStartHour = 21;
        } else {
            // 00:00 ~ 00:59 → 전날 21:00 기간에 속함
            return time.minusDays(1).withHour(21).withMinute(0).withSecond(0).withNano(0);
        }

        return time.withHour(periodStartHour).withMinute(0).withSecond(0).withNano(0);
    }

    /**
     * 1분봉 리스트를 하나의 4시간봉으로 집계
     */
    private static FourHourCandle aggregate4HourCandle(
        List<HistoricalMinuteOhlcv> minuteCandles,
        LocalDateTime periodStart
    ) {
        if (minuteCandles.isEmpty()) {
            throw new IllegalArgumentException("Cannot aggregate empty candle list");
        }

        BigDecimal open = minuteCandles.get(0).getOpeningPrice();
        BigDecimal close = minuteCandles.get(minuteCandles.size() - 1).getTradePrice();

        BigDecimal high = minuteCandles.stream()
            .map(HistoricalMinuteOhlcv::getHighPrice)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);

        BigDecimal low = minuteCandles.stream()
            .map(HistoricalMinuteOhlcv::getLowPrice)
            .min(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);

        BigDecimal volume = minuteCandles.stream()
            .map(c -> c.getCandleAccTradeVolume() != null ? c.getCandleAccTradeVolume() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new FourHourCandle(periodStart, open, high, low, close, volume);
    }

    /**
     * 4시간봉 데이터 클래스
     */
    @Getter
    @AllArgsConstructor
    public static class FourHourCandle {
        private LocalDateTime timestamp;    // 4시간봉 시작 시각
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private BigDecimal volume;

        @Override
        public String toString() {
            return String.format("%s | O:%.2f H:%.2f L:%.2f C:%.2f V:%.2f",
                timestamp, open, high, low, close, volume);
        }
    }
}
