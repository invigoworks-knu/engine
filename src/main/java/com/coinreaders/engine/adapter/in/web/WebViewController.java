package com.coinreaders.engine.adapter.in.web;

import com.coinreaders.engine.application.backtest.BacktestService;
import com.coinreaders.engine.application.backtest.FoldConfig;
import com.coinreaders.engine.application.backtest.dto.BacktestRequest;
import com.coinreaders.engine.application.backtest.dto.BacktestResponse;
import com.coinreaders.engine.application.backtest.dto.SequentialBacktestResponse;
import com.coinreaders.engine.application.backtest.dto.ThresholdMode;
import com.coinreaders.engine.application.backtest.dto.ConfidenceColumn;
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
     * 메인 페이지 (대시보드로 리다이렉트)
     */
    @GetMapping("/")
    public String home() {
        log.info("[Web] 메인 페이지 요청 - 대시보드로 리다이렉트");
        return "redirect:/trading/dashboard";
    }

    /**
     * 백테스팅 페이지 (TP/SL 백테스팅으로 리다이렉트)
     */
    @GetMapping("/backtest")
    public String backtestForm() {
        log.info("[Web] 백테스팅 페이지 요청 - TP/SL 백테스팅으로 리다이렉트");
        return "redirect:/backtest/tp-sl";
    }

    /**
     * AI 예측 데이터 조회 페이지
     */
    @GetMapping("/predictions")
    public String predictions() {
        return "predictions";
    }

    /**
     * TP/SL 백테스팅 설정 페이지
     */
    @GetMapping("/backtest/tp-sl")
    public String tpSlBacktestConfig(Model model) {
        model.addAttribute("folds", FoldConfig.getAllFolds());
        return "tp-sl-backtest";
    }
}
