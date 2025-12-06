package com.coinreaders.engine.application.backtest.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 기술적 지표 계산 유틸리티
 * - SMA, EMA, Bollinger Bands, ATR 등
 */
public class TechnicalIndicators {

    private static final int SCALE = 8;

    /**
     * SMA (Simple Moving Average) 계산
     */
    public static List<BigDecimal> calculateSMA(List<BigDecimal> prices, int period) {
        List<BigDecimal> sma = new ArrayList<>();

        for (int i = 0; i < prices.size(); i++) {
            if (i < period - 1) {
                sma.add(null); // 데이터 부족
            } else {
                BigDecimal sum = BigDecimal.ZERO;
                for (int j = 0; j < period; j++) {
                    sum = sum.add(prices.get(i - j));
                }
                BigDecimal avg = sum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
                sma.add(avg);
            }
        }

        return sma;
    }

    /**
     * EMA (Exponential Moving Average) 계산
     */
    public static List<BigDecimal> calculateEMA(List<BigDecimal> prices, int period) {
        List<BigDecimal> ema = new ArrayList<>();

        if (prices.isEmpty()) {
            return ema;
        }

        // 평활 계수: 2 / (period + 1)
        BigDecimal multiplier = BigDecimal.valueOf(2.0)
            .divide(BigDecimal.valueOf(period + 1), SCALE, RoundingMode.HALF_UP);

        // 첫 EMA는 첫 가격으로 시작 (또는 SMA 사용 가능)
        BigDecimal prevEma = prices.get(0);
        ema.add(prevEma);

        // EMA[i] = Price[i] × multiplier + EMA[i-1] × (1 - multiplier)
        for (int i = 1; i < prices.size(); i++) {
            BigDecimal price = prices.get(i);
            BigDecimal currentEma = price.multiply(multiplier)
                .add(prevEma.multiply(BigDecimal.ONE.subtract(multiplier)));
            ema.add(currentEma);
            prevEma = currentEma;
        }

        return ema;
    }

    /**
     * 표준편차 계산
     */
    public static List<BigDecimal> calculateStd(List<BigDecimal> prices, int period) {
        List<BigDecimal> std = new ArrayList<>();
        List<BigDecimal> sma = calculateSMA(prices, period);

        for (int i = 0; i < prices.size(); i++) {
            if (i < period - 1 || sma.get(i) == null) {
                std.add(null);
            } else {
                BigDecimal mean = sma.get(i);
                BigDecimal sumSquaredDiff = BigDecimal.ZERO;

                for (int j = 0; j < period; j++) {
                    BigDecimal diff = prices.get(i - j).subtract(mean);
                    sumSquaredDiff = sumSquaredDiff.add(diff.multiply(diff));
                }

                BigDecimal variance = sumSquaredDiff.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
                BigDecimal stdDev = sqrt(variance);
                std.add(stdDev);
            }
        }

        return std;
    }

    /**
     * Bollinger Bands 계산
     */
    public static BollingerBands calculateBollingerBands(
        List<BigDecimal> prices,
        int period,
        BigDecimal multiplier
    ) {
        List<BigDecimal> sma = calculateSMA(prices, period);
        List<BigDecimal> std = calculateStd(prices, period);

        List<BigDecimal> upper = new ArrayList<>();
        List<BigDecimal> lower = new ArrayList<>();
        List<BigDecimal> width = new ArrayList<>();

        for (int i = 0; i < prices.size(); i++) {
            if (sma.get(i) == null || std.get(i) == null) {
                upper.add(null);
                lower.add(null);
                width.add(null);
            } else {
                BigDecimal smaVal = sma.get(i);
                BigDecimal stdVal = std.get(i);

                BigDecimal upperVal = smaVal.add(stdVal.multiply(multiplier));
                BigDecimal lowerVal = smaVal.subtract(stdVal.multiply(multiplier));

                // BB Width = (Upper - Lower) / SMA
                BigDecimal widthVal = upperVal.subtract(lowerVal)
                    .divide(smaVal, SCALE, RoundingMode.HALF_UP);

                upper.add(upperVal);
                lower.add(lowerVal);
                width.add(widthVal);
            }
        }

        return new BollingerBands(sma, upper, lower, width);
    }

    /**
     * True Range 계산
     */
    public static List<BigDecimal> calculateTrueRange(
        List<BigDecimal> high,
        List<BigDecimal> low,
        List<BigDecimal> close
    ) {
        List<BigDecimal> tr = new ArrayList<>();

        for (int i = 0; i < high.size(); i++) {
            if (i == 0) {
                // 첫 데이터는 High - Low
                tr.add(high.get(i).subtract(low.get(i)));
            } else {
                BigDecimal prevClose = close.get(i - 1);

                // TR = max(High-Low, |High-PrevClose|, |Low-PrevClose|)
                BigDecimal tr1 = high.get(i).subtract(low.get(i));
                BigDecimal tr2 = high.get(i).subtract(prevClose).abs();
                BigDecimal tr3 = low.get(i).subtract(prevClose).abs();

                BigDecimal maxTr = tr1.max(tr2).max(tr3);
                tr.add(maxTr);
            }
        }

        return tr;
    }

    /**
     * ATR (Average True Range) 계산
     */
    public static List<BigDecimal> calculateATR(
        List<BigDecimal> high,
        List<BigDecimal> low,
        List<BigDecimal> close,
        int period
    ) {
        List<BigDecimal> tr = calculateTrueRange(high, low, close);
        return calculateSMA(tr, period);
    }

    /**
     * NATR (Normalized ATR) 계산 - 백분율
     */
    public static List<BigDecimal> calculateNATR(List<BigDecimal> atr, List<BigDecimal> close) {
        List<BigDecimal> natr = new ArrayList<>();

        for (int i = 0; i < atr.size(); i++) {
            if (atr.get(i) == null || close.get(i).compareTo(BigDecimal.ZERO) == 0) {
                natr.add(null);
            } else {
                // NATR = (ATR / Close) × 100
                BigDecimal natrVal = atr.get(i)
                    .divide(close.get(i), SCALE, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
                natr.add(natrVal);
            }
        }

        return natr;
    }

    /**
     * Volume Spike 감지
     * @param volume 거래량 리스트
     * @param volumeMa 거래량 이동평균
     * @param threshold 임계값 (예: 2.5배)
     */
    public static List<Boolean> detectVolumeSpike(
        List<BigDecimal> volume,
        List<BigDecimal> volumeMa,
        BigDecimal threshold
    ) {
        List<Boolean> spikes = new ArrayList<>();

        for (int i = 0; i < volume.size(); i++) {
            if (volumeMa.get(i) == null || volumeMa.get(i).compareTo(BigDecimal.ZERO) == 0) {
                spikes.add(false);
            } else {
                // Volume > MA × threshold
                BigDecimal thresholdValue = volumeMa.get(i).multiply(threshold);
                spikes.add(volume.get(i).compareTo(thresholdValue) > 0);
            }
        }

        return spikes;
    }

    /**
     * Quantile 계산 (하위 N% 값)
     * @param values 값 리스트
     * @param quantile 분위수 (0.0 ~ 1.0)
     */
    public static BigDecimal calculateQuantile(List<BigDecimal> values, double quantile) {
        List<BigDecimal> sorted = new ArrayList<>(values);
        sorted.removeIf(v -> v == null);
        sorted.sort(BigDecimal::compareTo);

        if (sorted.isEmpty()) {
            return BigDecimal.ZERO;
        }

        int index = (int) Math.ceil(sorted.size() * quantile) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));

        return sorted.get(index);
    }

    /**
     * Rolling Quantile 계산
     * @param values 값 리스트
     * @param window 윈도우 크기
     * @param quantile 분위수
     */
    public static List<BigDecimal> calculateRollingQuantile(
        List<BigDecimal> values,
        int window,
        double quantile
    ) {
        List<BigDecimal> result = new ArrayList<>();

        for (int i = 0; i < values.size(); i++) {
            if (i < window - 1 || values.get(i) == null) {
                result.add(null);
            } else {
                List<BigDecimal> windowValues = new ArrayList<>();
                for (int j = 0; j < window; j++) {
                    BigDecimal val = values.get(i - j);
                    if (val != null) {
                        windowValues.add(val);
                    }
                }
                result.add(calculateQuantile(windowValues, quantile));
            }
        }

        return result;
    }

    /**
     * Rolling Max 계산 (최근 N개 중 최대값)
     */
    public static List<BigDecimal> calculateRollingMax(List<BigDecimal> values, int window) {
        List<BigDecimal> result = new ArrayList<>();

        for (int i = 0; i < values.size(); i++) {
            if (i < window - 1) {
                result.add(null);
            } else {
                BigDecimal max = values.get(i);
                for (int j = 1; j < window; j++) {
                    BigDecimal val = values.get(i - j);
                    if (val != null && val.compareTo(max) > 0) {
                        max = val;
                    }
                }
                result.add(max);
            }
        }

        return result;
    }

    /**
     * 제곱근 계산 (Newton's method)
     */
    private static BigDecimal sqrt(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal x = value;
        BigDecimal two = new BigDecimal("2");

        // Newton's method: x_new = (x + value/x) / 2
        for (int i = 0; i < 10; i++) {
            x = x.add(value.divide(x, SCALE, RoundingMode.HALF_UP))
                .divide(two, SCALE, RoundingMode.HALF_UP);
        }

        return x;
    }

    /**
     * Bollinger Bands 결과 클래스
     */
    public static class BollingerBands {
        public final List<BigDecimal> sma;
        public final List<BigDecimal> upper;
        public final List<BigDecimal> lower;
        public final List<BigDecimal> width;

        public BollingerBands(
            List<BigDecimal> sma,
            List<BigDecimal> upper,
            List<BigDecimal> lower,
            List<BigDecimal> width
        ) {
            this.sma = sma;
            this.upper = upper;
            this.lower = lower;
            this.width = width;
        }
    }
}
