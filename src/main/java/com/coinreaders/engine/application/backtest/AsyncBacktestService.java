package com.coinreaders.engine.application.backtest;

import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestRequest;
import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestResponse;
import com.coinreaders.engine.domain.entity.BacktestJob;
import com.coinreaders.engine.domain.repository.BacktestJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 비동기 백테스팅 서비스
 * - @Async를 통한 비블로킹 배치 실행
 * - 진행 상황 DB 저장
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncBacktestService {

    private final TakeProfitStopLossBacktestService backtestService;
    private final BacktestJobRepository jobRepository;

    /**
     * 배치 백테스팅을 비동기로 실행
     * @return jobId (작업 추적용)
     */
    public String submitBatchBacktest(
        List<String> modelNames,
        List<Integer> foldNumbers,
        BigDecimal initialCapital,
        BigDecimal predProbaThreshold,
        Integer holdingPeriodDays
    ) {
        // 1. Job ID 생성 및 작업 등록
        String jobId = UUID.randomUUID().toString();
        int totalTasks = modelNames.size() * foldNumbers.size();

        BacktestJob job = BacktestJob.create(jobId, totalTasks);
        jobRepository.save(job);

        log.info("배치 백테스팅 작업 등록: jobId={}, total={}",  jobId, totalTasks);

        // 2. 비동기 실행 (별도 스레드에서 실행)
        executeBatchAsync(jobId, modelNames, foldNumbers, initialCapital, predProbaThreshold, holdingPeriodDays);

        return jobId;
    }

    /**
     * 실제 배치 실행 (비동기)
     */
    @Async("backtestExecutor")
    @Transactional
    public void executeBatchAsync(
        String jobId,
        List<String> modelNames,
        List<Integer> foldNumbers,
        BigDecimal initialCapital,
        BigDecimal predProbaThreshold,
        Integer holdingPeriodDays
    ) {
        log.info("=== 비동기 배치 백테스팅 시작: jobId={} ===", jobId);

        BacktestJob job = jobRepository.findById(jobId).orElseThrow();
        job.start();
        jobRepository.save(job);

        int currentIndex = 0;
        int totalTasks = modelNames.size() * foldNumbers.size();

        try {
            for (String modelName : modelNames) {
                for (Integer foldNumber : foldNumbers) {
                    currentIndex++;
                    log.info("진행: {}/{} - Model={}, Fold={}", currentIndex, totalTasks, modelName, foldNumber);

                    try {
                        TakeProfitStopLossBacktestRequest request = TakeProfitStopLossBacktestRequest.builder()
                            .foldNumber(foldNumber)
                            .modelName(modelName)
                            .initialCapital(initialCapital)
                            .predProbaThreshold(predProbaThreshold)
                            .holdingPeriodDays(holdingPeriodDays)
                            .build();

                        TakeProfitStopLossBacktestResponse response = backtestService.runBacktest(request);

                        // 성공 카운트 증가
                        job.incrementCompleted();
                        jobRepository.save(job);

                        log.info("✓ 완료: {}/{} - Model={}, Fold={}, Return={}%",
                            currentIndex, totalTasks, modelName, foldNumber, response.getTotalReturnPct());

                    } catch (Exception e) {
                        log.error("✗ 실패: {}/{} - Model={}, Fold={}, Error={}",
                            currentIndex, totalTasks, modelName, foldNumber, e.getMessage());

                        // 실패 카운트 증가
                        job.incrementFailed();
                        jobRepository.save(job);
                    }
                }
            }

            log.info("=== 배치 백테스팅 완료: jobId={}, 성공={}, 실패={} ===",
                jobId, job.getCompletedTasks(), job.getFailedTasks());

        } catch (Exception e) {
            log.error("배치 백테스팅 중 심각한 오류 발생: jobId={}", jobId, e);
            job.fail(e.getMessage());
            jobRepository.save(job);
        }
    }

    /**
     * 작업 상태 조회
     */
    @Transactional(readOnly = true)
    public BacktestJob getJobStatus(String jobId) {
        return jobRepository.findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
    }

    /**
     * 완료된 작업의 결과 조회
     *
     * Note: 현재는 DB에 결과를 저장하지 않고 있으므로,
     * 프론트엔드에서 완료 후 다시 배치 API를 호출해야 합니다.
     * 향후 개선: BacktestResult 엔티티를 만들어 결과를 DB에 저장
     */
    @Transactional(readOnly = true)
    public List<TakeProfitStopLossBacktestResponse> getJobResults(String jobId) {
        BacktestJob job = getJobStatus(jobId);

        if (job.getStatus() != BacktestJob.JobStatus.COMPLETED) {
            throw new IllegalStateException("Job not completed yet: " + jobId);
        }

        // TODO: 실제로는 DB에서 저장된 결과를 조회해야 함
        // 현재는 빈 리스트 반환 (프론트엔드에서 재조회 필요)
        log.warn("결과 조회 기능은 아직 구현되지 않았습니다. 프론트엔드에서 배치 API를 다시 호출하세요.");
        return new ArrayList<>();
    }
}
