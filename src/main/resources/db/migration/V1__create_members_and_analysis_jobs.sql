-- 운영 첫 스키마(2026-10-28). 로컬 개발 DB에서 Hibernate(ddl-auto=update)가 만든 테이블을 그대로 옮겼다.
-- 로컬 DB에만 남아 있던 옛 칸(media_content_type, media_size_bytes - 지금 엔티티에 없음)은 넣지 않았다.

CREATE TABLE members (
    id            BINARY(16)   NOT NULL,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    nickname      VARCHAR(10)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY idx_members_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE analysis_jobs (
    id                    BINARY(16)                                         NOT NULL,
    member_id             BINARY(16)                                         NOT NULL,
    modality              ENUM ('AUDIO', 'IMAGE', 'VIDEO')                   NOT NULL,
    status                ENUM ('COMPLETED', 'FAILED', 'PENDING', 'PROCESSING') NOT NULL,
    result_json           LONGTEXT,
    ai_score              DOUBLE,
    scam_score            DOUBLE,
    misinfo_refuted_count INT,
    error_code            VARCHAR(50),
    error_message         VARCHAR(1000),
    media_key             VARCHAR(200),
    created_at            DATETIME(6)                                        NOT NULL,
    updated_at            DATETIME(6)                                        NOT NULL,
    PRIMARY KEY (id),
    KEY idx_analysis_jobs_member_id (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
