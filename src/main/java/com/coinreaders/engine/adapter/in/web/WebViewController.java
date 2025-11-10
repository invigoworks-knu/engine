package com.coinreaders.engine.adapter.in.web;

import com.coinreaders.engine.application.backtest.BacktestService;
import com.coinreaders.engine.application.backtest.FoldConfig;
import com.coinreaders.engine.application.backtest.dto.BacktestRequest;
import com.coinreaders.engine.application.backtest.dto.BacktestResponse;
import com.coinreaders.engine.application.backtest.dto.SequentialBacktestResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

@Controller
@RequiredArgsConstructor
@Slf4j
public class WebViewController {

    private final BacktestService backtestService;

    /**
     * 메인 페이지 (백테스팅 입력 폼)
     */
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("folds", FoldConfig.getAllFolds());
        return "index";
    }

    /**
     * 단일 Fold 백테스팅 결과 페이지
     */
    @GetMapping("/backtest")
    public String backtest(
        @RequestParam Integer foldNumber,
        @RequestParam(required = false, defaultValue = "10000") BigDecimal initialCapital,
        @RequestParam(required = false, defaultValue = "0.5") BigDecimal confidenceThreshold,
        @RequestParam(required = false) BigDecimal positionSizePercent,
        Model model
    ) {
        try {
            // 입력 검증
            if (foldNumber < 1 || foldNumber > 8) {
                model.addAttribute("error", "Fold number must be between 1 and 8");
                return "error";
            }
            if (initialCapital.compareTo(BigDecimal.ZERO) <= 0) {
                model.addAttribute("error", "Initial capital must be positive");
                return "error";
            }
            if (confidenceThreshold.compareTo(BigDecimal.ZERO) < 0 || confidenceThreshold.compareTo(BigDecimal.ONE) > 0) {
                model.addAttribute("error", "상승 확률 임계값은 0과 1 사이여야 합니다");
                return "error";
            }
            if (positionSizePercent != null && (positionSizePercent.compareTo(BigDecimal.ZERO) < 0 || positionSizePercent.compareTo(new BigDecimal("100")) > 0)) {
                model.addAttribute("error", "Position size must be between 0 and 100");
                return "error";
            }

            BacktestRequest request = new BacktestRequest(foldNumber, initialCapital, confidenceThreshold, positionSizePercent);
            BacktestResponse response = backtestService.runBacktest(request);

            model.addAttribute("result", response);
            model.addAttribute("foldNumber", foldNumber);
            model.addAttribute("initialCapital", initialCapital);
            model.addAttribute("confidenceThreshold", confidenceThreshold);
            model.addAttribute("positionSizePercent", positionSizePercent);

            return "result";
        } catch (Exception e) {
            log.error("백테스팅 실행 중 오류 발생", e);
            model.addAttribute("error", "Error: " + e.getMessage());
            return "error";
        }
    }

    /**
     * 연속 백테스팅 결과 페이지 (Fold 1~7)
     */
    @GetMapping("/backtest-sequential")
    public String backtestSequential(
        @RequestParam(required = false, defaultValue = "1") Integer startFold,
        @RequestParam(required = false, defaultValue = "7") Integer endFold,
        @RequestParam(required = false, defaultValue = "10000") BigDecimal initialCapital,
        @RequestParam(required = false, defaultValue = "0.5") BigDecimal confidenceThreshold,
        @RequestParam(required = false) BigDecimal positionSizePercent,
        Model model
    ) {
        try {
            // 입력 검증
            if (startFold < 1 || startFold > 8 || endFold < 1 || endFold > 8) {
                model.addAttribute("error", "Fold numbers must be between 1 and 8");
                return "error";
            }
            if (startFold > endFold) {
                model.addAttribute("error", "Start fold must be less than or equal to end fold");
                return "error";
            }
            if (initialCapital.compareTo(BigDecimal.ZERO) <= 0) {
                model.addAttribute("error", "Initial capital must be positive");
                return "error";
            }
            if (confidenceThreshold.compareTo(BigDecimal.ZERO) < 0 || confidenceThreshold.compareTo(BigDecimal.ONE) > 0) {
                model.addAttribute("error", "상승 확률 임계값은 0과 1 사이여야 합니다");
                return "error";
            }
            if (positionSizePercent != null && (positionSizePercent.compareTo(BigDecimal.ZERO) < 0 || positionSizePercent.compareTo(new BigDecimal("100")) > 0)) {
                model.addAttribute("error", "Position size must be between 0 and 100");
                return "error";
            }

            SequentialBacktestResponse response = backtestService.runSequentialBacktest(
                startFold, endFold, initialCapital, confidenceThreshold, positionSizePercent
            );

            model.addAttribute("result", response);
            model.addAttribute("startFold", startFold);
            model.addAttribute("endFold", endFold);
            model.addAttribute("initialCapital", initialCapital);
            model.addAttribute("confidenceThreshold", confidenceThreshold);
            model.addAttribute("positionSizePercent", positionSizePercent);

            return "result-sequential";
        } catch (Exception e) {
            log.error("연속 백테스팅 실행 중 오류 발생", e);
            model.addAttribute("error", "Error: " + e.getMessage());
            return "error";
        }
    }
}
