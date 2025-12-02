package com.coinreaders.engine.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 비동기 처리 설정
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 백테스팅 전용 ThreadPool
     * - 동시에 실행할 수 있는 백테스팅 작업 수 제한
     * - 메모리 부족 방지
     */
    @Bean(name = "backtestExecutor")
    public Executor backtestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 기본 스레드 수: 2개
        executor.setCorePoolSize(2);

        // 최대 스레드 수: 4개 (동시에 최대 4개 배치 작업 실행 가능)
        executor.setMaxPoolSize(4);

        // 대기 큐 크기: 10개
        executor.setQueueCapacity(10);

        // 스레드 이름 prefix
        executor.setThreadNamePrefix("BacktestAsync-");

        // 큐가 가득 찼을 때 정책: 호출한 스레드에서 실행
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 애플리케이션 종료 시 대기 중인 작업 완료 대기
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();

        log.info("BacktestExecutor 초기화 완료: core={}, max={}, queue={}",
            executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());

        return executor;
    }
}
