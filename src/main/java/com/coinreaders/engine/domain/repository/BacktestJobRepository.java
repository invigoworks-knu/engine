package com.coinreaders.engine.domain.repository;

import com.coinreaders.engine.domain.entity.BacktestJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface BacktestJobRepository extends JpaRepository<BacktestJob, String> {

    /**
     * 특정 상태의 작업 목록 조회
     */
    List<BacktestJob> findByStatus(BacktestJob.JobStatus status);

    /**
     * 특정 시간 이후에 생성된 작업 목록 조회
     */
    List<BacktestJob> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime after);

    /**
     * 오래된 완료 작업 삭제용 (정리 배치)
     */
    void deleteByStatusAndCompletedAtBefore(BacktestJob.JobStatus status, LocalDateTime before);
}
