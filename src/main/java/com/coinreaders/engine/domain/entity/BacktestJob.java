package com.coinreaders.engine.domain.entity;

import com.coinreaders.engine.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 비동기 백테스팅 작업 상태 관리 엔티티
 */
@Entity
@Table(name = "backtest_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BacktestJob extends BaseTimeEntity {

    @Id
    @Column(name = "job_id", length = 64)
    private String jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(nullable = false)
    private Integer totalTasks;

    @Column(nullable = false)
    private Integer completedTasks;

    @Column(nullable = false)
    private Integer failedTasks;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column
    private LocalDateTime startedAt;

    @Column
    private LocalDateTime completedAt;

    private BacktestJob(String jobId, Integer totalTasks) {
        this.jobId = jobId;
        this.status = JobStatus.PENDING;
        this.totalTasks = totalTasks;
        this.completedTasks = 0;
        this.failedTasks = 0;
        this.startedAt = LocalDateTime.now();
    }

    public static BacktestJob create(String jobId, Integer totalTasks) {
        return new BacktestJob(jobId, totalTasks);
    }

    public void start() {
        this.status = JobStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
    }

    public void incrementCompleted() {
        this.completedTasks++;
        if (this.completedTasks + this.failedTasks >= this.totalTasks) {
            this.complete();
        }
    }

    public void incrementFailed() {
        this.failedTasks++;
        if (this.completedTasks + this.failedTasks >= this.totalTasks) {
            this.complete();
        }
    }

    public void complete() {
        this.status = JobStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String errorMessage) {
        this.status = JobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    public int getProgress() {
        if (totalTasks == 0) return 0;
        return (int) ((completedTasks * 100.0) / totalTasks);
    }

    public enum JobStatus {
        PENDING,    // 대기 중
        RUNNING,    // 실행 중
        COMPLETED,  // 완료
        FAILED      // 실패
    }
}
