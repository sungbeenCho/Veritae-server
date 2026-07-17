package com.veritae.veritae_server.domain.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 회원의 신원/인증/프로필 정보만 소유하는 애그리게잇 루트.
 * 향후 추가될 분석/위험도(risk-profile) 등 다른 도메인 개념은 이 엔티티에 두지 않는다.
 */
@Entity
@Table(name = "members", indexes = @Index(name = "idx_members_email", columnList = "email", unique = true))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용
public class Member {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 10)
    private String nickname;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Member(UUID id, String email, String passwordHash, String nickname, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.createdAt = createdAt;
    }

    /**
     * 신규 회원 생성. id 는 순차 증가 PK 가 아닌 애플리케이션에서 할당한 UUID 를 사용한다
     * (외부에 노출되는 공개 식별자, ADR-0001 참고).
     */
    public static Member register(String email, String passwordHash, String nickname) {
        return new Member(UUID.randomUUID(), email, passwordHash, nickname, Instant.now());
    }
}
