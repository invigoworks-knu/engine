package com.coinreaders.engine.adapter.in.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
@Slf4j
public class WebViewController {

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
        return "tp-sl-backtest";
    }
}
