package com.coinreaders.engine.domain.entity;

import com.coinreaders.engine.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user") // DDL의 'user' 테이블과 매핑
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA는 기본 생성자가 필요
public class User extends BaseTimeEntity { // createdAt, updatedAt 상속

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String nickname;

    @Column(length = 32)
    private String selectedStrategy;

    @Column(length = 255)
    private String upbitAccessKey; // Jasypt로 암호화될 필드

    @Column(length = 255)
    private String upbitSecretKey; // Jasypt로 암호화될 필드
}