package com.coinreaders.engine.adapter.in.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 거래 웹 페이지 컨트롤러
 *
 * Thymeleaf 템플릿을 렌더링하는 컨트롤러
 */
@Slf4j
@Controller
@RequestMapping("/trading")
@RequiredArgsConstructor
public class TradingWebController {

    /**
     * 거래 대시보드
     *
     * GET /trading/dashboard
     *
     * @return 대시보드 페이지
     */
    @GetMapping("/dashboard")
    public String dashboard() {
        log.info("[Web] 거래 대시보드 페이지 요청");
        return "trading-dashboard";
    }

    /**
     * 마이페이지 (안전장치 설정)
     *
     * GET /trading/mypage
     *
     * @return 마이페이지
     */
    @GetMapping("/mypage")
    public String mypage() {
        log.info("[Web] 마이페이지 요청");
        return "trading-mypage";
    }

    /**
     * 거래 내역 페이지
     *
     * GET /trading/history
     *
     * @return 거래 내역 페이지
     */
    @GetMapping("/history")
    public String history() {
        log.info("[Web] 거래 내역 페이지 요청");
        return "trading-history";
    }
}
