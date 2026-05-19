-- ================================================================
-- Queue Platform — DDL v1.10
-- MySQL 8.0
--
-- [DATETIME(3)] 밀리초 단위. Redis 복구 시 issued_at.toEpochMilli() → score
-- [ID 전략] id(BIGINT): 내부 PK. xxxId(VARCHAR): 외부 식별자
-- [파티셔닝] tokens: issued_at 기준 Range 파티션 (월별)
-- [파티션 유예] DROP은 M+2월 초 실행 (월말 걸친 토큰 보호)
-- [단순화] billing_events, stats_events 제거
--   billing: tokens 원본 직접 집계 → billing_snapshots UPSERT
--   avgWaitingTime: complete 시 직접 Redis HINCRBYFLOAT
-- [v1.10] tenants.status 추가 (ACTIVE=0, DEACTIVATED=1)
-- ================================================================

CREATE TABLE tenants (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     VARCHAR(36)  NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    status        TINYINT      NOT NULL DEFAULT 0,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uq_tenants_tenant_id (tenant_id),
    UNIQUE KEY uq_tenants_email     (email)
);


CREATE TABLE api_keys (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    api_key_id  VARCHAR(36) NOT NULL,
    tenant_id   BIGINT      NOT NULL,
    key_hash    VARCHAR(64) NOT NULL,
    status      TINYINT     NOT NULL DEFAULT 0,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revoked_at  DATETIME(3) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_api_keys_api_key_id (api_key_id),
    UNIQUE KEY uq_api_keys_key_hash   (key_hash),
    INDEX idx_api_keys_tenant_id      (tenant_id),

    CONSTRAINT fk_api_keys_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);


CREATE TABLE queues (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    queue_id     VARCHAR(36)  NOT NULL,
    tenant_id    BIGINT       NOT NULL,
    name         VARCHAR(100) NOT NULL,
    max_capacity INT          NOT NULL,
    slice_count  INT          NOT NULL,
    waiting_ttl  INT          NOT NULL DEFAULT 7200,
    inactive_ttl INT          NOT NULL DEFAULT 300,
    status       TINYINT      NOT NULL DEFAULT 0,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted_at   DATETIME(3)  NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_queues_queue_id    (queue_id),
    UNIQUE KEY uq_queues_tenant_name (tenant_id, name),
    INDEX idx_queues_tenant_status   (tenant_id, status),

    CONSTRAINT fk_queues_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);


-- ----------------------------------------------------------------
-- tokens (Range 파티션 — issued_at 월별)
--
-- [seq] ADMIT_ISSUED→WAITING 복귀 시 Redis ZADD score 복원 필수
-- [admit_token] verify DB Fallback + Polling Fallback용
-- [redis_sync_needed] Redis 다운 중 INSERT 토큰 추적 → RedisSyncJob
-- [파티션 키] PRIMARY KEY(id, issued_at) — MySQL 제약
--
-- [파티션 유예 전략 — 월말 걸친 토큰 보호]
--   문제: Queue가 2일 운영 시 파티션 경계 걸침
--     예) 4/30 Enqueue (issued_at=4월) → 5/1 complete
--         issued_at 기준 4월 파티션 존재
--         5/1 BillingSnapshotJob 실행 시 아직 WAITING → 미포함
--         5/1 DROP → 토큰 소멸 → 과금 누락
--
--   해결: M월 파티션은 M+2월 초에 DROP (1달 유예)
--     4월 파티션 → 6월 초 집계 + DROP
--     → 5월 중 complete된 토큰도 집계 가능
--     → 파티션 2달치 유지 (스토리지 약 2배, 과금 정확도 보장)
-- ----------------------------------------------------------------
CREATE TABLE tokens (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    token_id          VARCHAR(36)  NOT NULL,
    queue_id          VARCHAR(36)  NOT NULL,
    tenant_id         BIGINT       NOT NULL,
    user_id           VARCHAR(255) NOT NULL,
    seq               BIGINT       NOT NULL DEFAULT 0,
    status            TINYINT      NOT NULL DEFAULT 0,
    expired_reason    TINYINT      NULL,
    admit_token       VARCHAR(36)  NULL,
    redis_sync_needed TINYINT      NOT NULL DEFAULT 0,
    issued_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at      DATETIME(3)  NULL,
    cancelled_at      DATETIME(3)  NULL,
    expired_at        DATETIME(3)  NULL,

    PRIMARY KEY (id, issued_at),
    UNIQUE KEY uq_tokens_token_id         (token_id, issued_at),
    INDEX idx_tokens_token_status         (token_id, status),
    INDEX idx_tokens_queue_status_issued  (queue_id, status, issued_at),
    INDEX idx_tokens_queue_user_status    (queue_id, user_id, status),
    INDEX idx_tokens_status_admit         (status, issued_at),
    INDEX idx_tokens_sync_needed          (redis_sync_needed, status),

    CONSTRAINT fk_tokens_queue
        FOREIGN KEY (queue_id) REFERENCES queues (queue_id)
)
PARTITION BY RANGE (YEAR(issued_at) * 100 + MONTH(issued_at)) (
    PARTITION p2026_01 VALUES LESS THAN (202602),
    PARTITION p2026_02 VALUES LESS THAN (202603),
    PARTITION p2026_03 VALUES LESS THAN (202604),
    PARTITION p2026_04 VALUES LESS THAN (202605),
    PARTITION p2026_05 VALUES LESS THAN (202606),
    PARTITION p2026_06 VALUES LESS THAN (202607),
    PARTITION p2026_07 VALUES LESS THAN (202608),
    PARTITION p2026_08 VALUES LESS THAN (202609),
    PARTITION p2026_09 VALUES LESS THAN (202610),
    PARTITION p2026_10 VALUES LESS THAN (202611),
    PARTITION p2026_11 VALUES LESS THAN (202612),
    PARTITION p2026_12 VALUES LESS THAN (202701),
    PARTITION p_future  VALUES LESS THAN MAXVALUE
);


CREATE TABLE admit_requests (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    request_id    VARCHAR(36)  NOT NULL,
    tenant_id     BIGINT       NOT NULL,
    queue_id      VARCHAR(36)  NOT NULL,
    count         INT          NOT NULL,
    status        TINYINT      NOT NULL DEFAULT 0,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at  DATETIME(3)  NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_admit_request_id        (request_id),
    INDEX idx_admit_requests_queue_status (queue_id, status),
    INDEX idx_admit_requests_tenant       (tenant_id, created_at)
);


-- billing_snapshots: Tenant별 월별 과금 집계 (청구 기준)
-- tokens 원본에서 직접 집계 → billing_events 불필요
CREATE TABLE billing_snapshots (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT      NOT NULL,
    year_month  CHAR(6)     NOT NULL,
    count       BIGINT      NOT NULL DEFAULT 0,
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                            ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uq_billing_tenant_month (tenant_id, year_month)
);


-- queue_daily_stats: 파티션 DROP 후 과금/통계 근거 영구 보존
-- INSERT-only 불변 테이블 (감사 기준)
CREATE TABLE queue_daily_stats (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT      NOT NULL,
    queue_id          VARCHAR(36) NOT NULL,
    stat_date         DATE        NOT NULL,
    total_enqueued    INT         NOT NULL DEFAULT 0,
    total_completed   INT         NOT NULL DEFAULT 0,
    total_cancelled   INT         NOT NULL DEFAULT 0,
    total_expired     INT         NOT NULL DEFAULT 0,
    total_admit_count INT         NOT NULL DEFAULT 0,
    avg_wait_sec      INT         NULL,
    max_wait_sec      INT         NULL,
    created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uq_queue_daily_stat (queue_id, stat_date),
    INDEX idx_queue_daily_tenant   (tenant_id, stat_date)
);


-- ================================================================
-- 파티션 운영 쿼리 (1달 유예 — M월 파티션은 M+2월 초 DROP)
--
-- 예시: 4월 파티션(p2026_04) → 6월 초 처리
-- ================================================================

-- Step 1: queue_daily_stats 집계 (파티션 DROP 전 필수)
INSERT INTO queue_daily_stats
    (tenant_id, queue_id, stat_date,
     total_enqueued, total_completed, total_cancelled,
     total_expired, total_admit_count, avg_wait_sec, max_wait_sec)
SELECT
    tenant_id,
    queue_id,
    DATE(issued_at)                                             AS stat_date,
    COUNT(*)                                                    AS total_enqueued,
    SUM(status = 2)                                             AS total_completed,
    SUM(status = 3)                                             AS total_cancelled,
    SUM(status = 4)                                             AS total_expired,
    0                                                           AS total_admit_count,
    AVG(TIMESTAMPDIFF(SECOND, issued_at, completed_at))         AS avg_wait_sec,
    MAX(TIMESTAMPDIFF(SECOND, issued_at, completed_at))         AS max_wait_sec
FROM tokens
WHERE issued_at >= '2026-04-01'
  AND issued_at <  '2026-05-01'
GROUP BY tenant_id, queue_id, DATE(issued_at)
ON DUPLICATE KEY UPDATE id = id; -- 멱등: 배치 재실행 안전

-- Step 2: billing_snapshots 집계 (tokens 원본에서 직접)
INSERT INTO billing_snapshots (tenant_id, year_month, count)
SELECT tenant_id, '202604', COUNT(*)
FROM tokens
WHERE issued_at >= '2026-04-01'
  AND issued_at <  '2026-05-01'
GROUP BY tenant_id
ON DUPLICATE KEY UPDATE count = VALUES(count), updated_at = NOW(3);

-- Step 3: 파티션 DROP (Step 1,2 완료 후 실행)
ALTER TABLE tokens DROP PARTITION p2026_04;

-- Step 4: 다음 파티션 사전 생성
ALTER TABLE tokens REORGANIZE PARTITION p_future INTO (
    PARTITION p2027_01 VALUES LESS THAN (202702),
    PARTITION p_future  VALUES LESS THAN MAXVALUE
);

-- Partition Pruning 검증
EXPLAIN SELECT * FROM tokens
WHERE queue_id = 'q_xyz'
  AND issued_at >= '2026-04-01'
  AND issued_at <  '2026-05-01';
-- partitions: p2026_04 ← Pruning 성공 확인


-- ================================================================
-- refresh_tokens (Sprint 5 추가)
--
-- Token Rotation + 재사용 감지를 위한 Refresh Token 저장
-- 원본 토큰은 SHA-256 hash로 저장 (DB 유출 방어)
-- revoked_at으로 soft delete (감사 추적용)
-- ================================================================

CREATE TABLE refresh_tokens (
                                id          BIGINT       NOT NULL AUTO_INCREMENT,
                                tenant_id   BIGINT       NOT NULL,
                                token_hash  CHAR(64)     NOT NULL,           -- SHA-256 hex
                                issued_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                                expires_at  DATETIME(3)  NOT NULL,
                                revoked_at  DATETIME(3)  NULL,                -- soft delete

                                PRIMARY KEY (id),
                                UNIQUE KEY uq_refresh_tokens_token_hash (token_hash),
                                INDEX idx_refresh_tokens_tenant_active  (tenant_id, revoked_at),
                                INDEX idx_refresh_tokens_expires        (expires_at),

                                CONSTRAINT fk_refresh_tokens_tenant
                                    FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);