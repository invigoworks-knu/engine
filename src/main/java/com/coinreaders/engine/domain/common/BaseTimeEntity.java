package com.coinreaders.engine.domain.common;

import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Getter
@MappedSuperclass // 이 클래스를 상속받는 엔티티들은 아래 필드들을 컬럼으로 포함
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    @CreatedDate // 엔티티 생성 시 시간 자동 저장
    private LocalDateTime createdAt;

    @LastModifiedDate // 엔티티 수정 시 시간 자동 저장
    private LocalDateTime updatedAt;
}