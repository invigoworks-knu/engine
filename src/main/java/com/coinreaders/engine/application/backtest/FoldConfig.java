package com.coinreaders.engine.application.backtest;

import lombok.Getter;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 백테스팅을 위한 Fold 정의
 * (Python 코드의 folds 정의를 기반으로 함)
 */
@Getter
public class FoldConfig {

    private final int foldNumber;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final String regime;

    private FoldConfig(int foldNumber, LocalDate startDate, LocalDate endDate, String regime) {
        this.foldNumber = foldNumber;
        this.startDate = startDate;
        this.endDate = endDate;
        this.regime = regime;
    }

    private static final Map<Integer, FoldConfig> FOLD_MAP = new HashMap<>();

    static {
        // fold_1: SIDEWAYS
        FOLD_MAP.put(1, new FoldConfig(1,
            LocalDate.of(2022, 12, 7),
            LocalDate.of(2023, 5, 5),
            "SIDEWAYS"));

        // fold_2: SIDEWAYS
        FOLD_MAP.put(2, new FoldConfig(2,
            LocalDate.of(2023, 5, 6),
            LocalDate.of(2023, 10, 2),
            "SIDEWAYS"));

        // fold_3: BULL
        FOLD_MAP.put(3, new FoldConfig(3,
            LocalDate.of(2023, 10, 3),
            LocalDate.of(2024, 2, 29),
            "BULL"));

        // fold_4: BULL
        FOLD_MAP.put(4, new FoldConfig(4,
            LocalDate.of(2024, 3, 1),
            LocalDate.of(2024, 7, 28),
            "BULL"));

        // fold_5: BEAR
        FOLD_MAP.put(5, new FoldConfig(5,
            LocalDate.of(2024, 7, 29),
            LocalDate.of(2024, 12, 25),
            "BEAR"));

        // fold_6: BEAR
        FOLD_MAP.put(6, new FoldConfig(6,
            LocalDate.of(2024, 12, 26),
            LocalDate.of(2025, 5, 24),
            "BEAR"));

        // fold_7: BULL
        FOLD_MAP.put(7, new FoldConfig(7,
            LocalDate.of(2025, 5, 25),
            LocalDate.of(2025, 10, 21),
            "BULL"));

        // fold_8: MIXED (Final Holdout)
        FOLD_MAP.put(8, new FoldConfig(8,
            LocalDate.of(2025, 1, 1),
            LocalDate.of(2025, 10, 21),
            "MIXED"));
    }

    public static FoldConfig getFold(int foldNumber) {
        FoldConfig config = FOLD_MAP.get(foldNumber);
        if (config == null) {
            throw new IllegalArgumentException("Invalid fold number: " + foldNumber + ". Valid range: 1-8");
        }
        return config;
    }

    public static boolean isValidFold(int foldNumber) {
        return FOLD_MAP.containsKey(foldNumber);
    }
}
