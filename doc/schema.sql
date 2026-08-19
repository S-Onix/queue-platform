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
-- [v1.11] tenant_id, api_key_id, queue_id 크기를 36에서 50으로 변경 (prefix + UUIDv7 의 길이가 총 38임)
-- ================================================================

CREATE TABLE tenants (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     VARCHAR(50)  NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    status        TINYINT      NOT NULL DEFAULT 0,
    plan          TINYINT      NOT NULL DEFAULT 0,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uq_tenants_tenant_id (tenant_id),
    UNIQUE KEY uq_tenants_email     (email)
);


CREATE TABLE api_keys (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    api_key_id  VARCHAR(50) NOT NULL,
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
    queue_id     VARCHAR(50)  NOT NULL,
    tenant_id    BIGINT       NOT NULL,
    name         VARCHAR(100) NOT NULL,
    max_capacity INT          NOT NULL,
    waiting_ttl  INT          NOT NULL DEFAULT 7200,
    inactive_ttl INT          NOT NULL DEFAULT 300,
    status       TINYINT      NOT NULL DEFAULT 0,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted_at   DATETIME(3)  NULL,
    -- 이 큐의 Redis 상태(waiting/seq/tokens/last-active)가 사는 클러스터 번호 (DECISIONS §75).
    --   네 키는 반드시 같은 클러스터에 있다 — 해시태그로 슬롯을 묶어도 클러스터가 갈리면 무의미하다.
    --   INSERT 시점에 한 번 정해지고 이후 불변이다 (D27-2: 큐는 다른 클러스터로 옮기지 않는다).
    --   MAX(redis_cluster_no)가 신규 배정의 단조증가 가드로도 쓰인다 (§75 D29).
    -- ⚠️ 인덱스를 붙이지 않는다. 읽는 쿼리가 queue_id const 조회 하나뿐이라
    --    SELECT에 공짜로 딸려온다. 인덱스는 쓰기 비용만 늘린다.
    redis_cluster_no TINYINT  NOT NULL DEFAULT 1,

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
-- ================================================================
-- [시각 규약] ⚠️ 이 스키마의 시각 컬럼은 전부 UTC다. 예외 없다.
-- ================================================================
--   2026-08-12부로 전 테이블을 UTC로 통일했다. 그 전에는 tokens만 UTC이고
--   나머지는 KST여서 두 규약이 공존했다. 근거·대안·실측은 DECISIONS §77 (§76을 대체).
--
--   어떻게 UTC가 되는가 — 세 개가 맞물린다. 하나라도 어긋나면 조용히 9시간 밀린다:
--     1) JVM 기본 TZ = UTC       (각 Application의 main()에서 TimeZone.setDefault)
--        → LocalDateTime.now() 가 UTC 벽시계를 낸다. 도메인 코드는 그대로 둔다.
--     2) JDBC connectionTimeZone=UTC
--        → 드라이버가 렌더하는 타임존. (1)과 반드시 같아야 항등으로 저장된다.
--          Hibernate가 LocalDateTime을 Timestamp.valueOf(JVM TZ 해석) → setTimestamp 로
--          바인딩하므로, 둘이 다르면 그 차이만큼 밀린다. 실측표는 DECISIONS §77.
--     3) forceConnectionTimeZoneToSession=true
--        → 세션 time_zone 까지 +00:00 으로 만든다. 이게 없으면 저장은 UTC인데
--          NOW()/CURDATE() 만 KST로 남는다.
--
--   DEFAULT CURRENT_TIMESTAMP(3) 는 세션 TZ를 따르므로 앱 커넥션에서는 UTC를 낳는다.
--   기존 테이블의 DEFAULT는 그대로 두되, tokens.issued_at 은 제거된 상태를 유지한다
--   (값은 반드시 애플리케이션이 넣는다 — 파티션 키라 누락 시 조용히 통과하면 안 된다).
--
--   ⚠️ mysql CLI 는 여전히 KST다. forceConnectionTimeZoneToSession 은 앱의 JDBC 커넥션에만
--      적용되고, 서버 default-time-zone 은 '+09:00' 이다(미변경 — 재기동 필요).
--      셸에서 붙으면 @@session.time_zone = +09:00 이라 NOW()/CURDATE() 가 KST다.
--      → 운영 쿼리는 UTC_DATE()/UTC_TIMESTAMP() 를 쓰거나 앞에 SET time_zone='+00:00'.
--
--   ⚠️ 집계 경계도 전부 UTC다. DATE(issued_at) · 파티션 표현식 YEAR*100+MONTH ·
--      billing의 월 범위가 모두 UTC 기준이므로, KST 5/1 03:00 발행 토큰은
--      4월 파티션 / 4월 청구 / stat_date 4/30 에 들어간다.
--      테넌트가 KST 기준 청구서를 기대하면 월 경계 9시간분이 어긋난다.
--      통일로 사라진 문제가 아니다 — 표시·청구 계층에서 변환할지는 별도 판단이 필요하다.
--      (미해결, DECISIONS §77 Consequences)
-- ----------------------------------------------------------------
--
-- [seq] ADMIT_ISSUED→WAITING 복귀 시 Redis ZADD score 복원 필수
-- [admit_token] verify DB Fallback + Polling Fallback용
-- [admitted_at] admit 시각. verify·complete의 유효 창 판정 기준 (DECISIONS §80)
--   ⚠️ issued_at을 그 판정에 쓰면 안 된다 — 줄을 선 시각이라 두 시간 전일 수 있다.
--   ✅ 파티션 테이블 ADD COLUMN에서 ALGORITHM=INSTANT 실증 완료 (2026-08-17 22:26:27 KST, MySQL 8.0.46).
--      실행문: ALTER TABLE tokens ADD COLUMN admitted_at DATETIME(3) NULL AFTER issued_at, ALGORITHM=INSTANT
--      근거: master binlog master-bin.000427 에 error_code=0 으로 남아 있다. ALGORITHM=INSTANT 는
--            미지원이면 ER 1845/1846 으로 실패해 binlog에 기록되지 않으므로 기록 자체가 성공의 증명이다.
--            master 3306 · replica 3307 양쪽에서 컬럼 존재 확인(information_schema.COLUMNS).
--      → 13개 파티션 재구축·replica 지연은 발생하지 않는다.
--      ⚠️ 전제 둘: (a) 마지막이 아닌 위치(AFTER issued_at)의 INSTANT ADD COLUMN 은 MySQL 8.0.29+ 다.
--            8.0.12~8.0.28 서버에서는 이 문장 그대로 실패한다.
--            (b) 실증 당시 tokens 는 0행이었다. INSTANT 지원 여부는 행 수와 무관하지만,
--            대용량에서의 MDL 배타 락 대기(장기 트랜잭션 뒤에서 대기)까지 잰 것은 아니다.
--            테이블당 row version 64 상한을 넘기면 그때는 INSTANT가 아니라 재구축이다.
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
    token_id          VARCHAR(50)  NOT NULL,
    queue_id          VARCHAR(50)  NOT NULL,
    tenant_id         BIGINT       NOT NULL,
    user_id           VARCHAR(255) NOT NULL,
    seq               BIGINT       NOT NULL DEFAULT 0,
    status            TINYINT      NOT NULL DEFAULT 0,
    expired_reason    TINYINT      NULL,
    admit_token       VARCHAR(50)  NULL,
    redis_sync_needed TINYINT      NOT NULL DEFAULT 0,
    -- ⚠️ 아래 5개는 전부 UTC 벽시계다. 위 [시각 규약] 참조. DEFAULT를 붙이지 마라.
    issued_at         DATETIME(3)  NOT NULL,
    admitted_at       DATETIME(3)  NULL,     -- admit 시각 (DECISIONS §80)
    completed_at      DATETIME(3)  NULL,
    cancelled_at      DATETIME(3)  NULL,
    expired_at        DATETIME(3)  NULL,

    PRIMARY KEY (id, issued_at),
    UNIQUE KEY uq_tokens_token_id         (token_id, issued_at),
    INDEX idx_tokens_token_status         (token_id, status),          -- ⚠️ 삭제 후보: uq_tokens_token_id가 (token_id, ...) 접두로 커버
    INDEX idx_tokens_queue_status_issued  (queue_id, status, issued_at),
    INDEX idx_tokens_queue_user_status    (queue_id, user_id, status), -- ⚠️ 삭제 후보: 중복 판정은 Lua HSETNX(tokens Hash)가 한다. 6개 중 가장 넓다
    INDEX idx_tokens_status_admit         (status, issued_at),         -- ⚠️ 삭제 후보(Sprint 9 확정 후). 이름의 admit은 admit_token과 무관 — 오해를 부른다
    INDEX idx_tokens_sync_needed          (redis_sync_needed, status)

    -- 🔴 fk_tokens_queue 삭제 (2026-08-17)
    --   InnoDB는 파티션 테이블에 FK를 지원하지 않는다. 이 제약이 남아 있으면
    --   이 파일을 그대로 실행할 때 CREATE TABLE 자체가 실패한다.
    --   (구: CONSTRAINT fk_tokens_queue FOREIGN KEY (queue_id) REFERENCES queues (queue_id))
    --   queue_id 정합성은 애플리케이션이 보장한다 — enqueue가 queues 행을 먼저 읽는다.
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


-- ================================================================
-- 🔴 admit_requests 테이블 삭제 (2026-08-17, DECISIONS §80)
--
--   폐기 이유:
--     ① 존재 이유가 "요청은 받았는데 아직 처리 안 됨(PENDING)"이었는데,
--        admit이 동기 + Lua 하나가 되어 그 상태 자체가 없다. 성공했거나 예외거나다.
--     ② 감사 기록으로도 반쪽이다 — 흐름이 'Lua 성공 → INSERT'라
--        실패한 요청은 애초에 남지 않는다. 성공 기록은 Kafka 이벤트·메트릭이 이미 갖는다.
--     ③ 과금 근거가 아니다 (과금은 enqueue 수 기준).
--   대체:
--     멱등 → Redis queue:{q}:admit-idem:{requestId} (결과 payload 저장 → REPLAY)
--     장기 이력 → queue_daily_stats.total_admit_count
--   ⚠️ 대가: Redis 멱등키가 유실되면 중복 admit을 감지할 수단이 없다.
--      UNIQUE (request_id)가 마지막 방어선이었다. §80이 명시적으로 수용한 비용이다.
-- ================================================================


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
    queue_id          VARCHAR(50) NOT NULL,
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
    -- ⚠️ issued_at·completed_at 둘 다 UTC일 때만 맞다. completed_at은 Sprint 7 미구현이다.
    --    규약이 어떻게 UTC를 보장하는지(JVM TZ + JDBC 2개)는 위 [시각 규약] 참조 —
    --    그 셋 중 하나라도 어긋난 채 값이 들어가면 이 두 줄이 통째로 32,400초 어긋난다.
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

-- ================================================================
-- 🔴 Partition Pruning — 위 Step 1·2의 범위 조건은 프루닝되지 않는다 (2026-08-17 정정)
-- ================================================================
--   파티션 표현식이 YEAR(c)*100 + MONTH(c) 인데, MySQL 옵티마이저는 이 식을
--   issued_at에 대해 단조(monotonic)라고 인식하지 못한다. 그래서
--   `issued_at >= ... AND issued_at < ...` 같은 범위 조건에서는 프루닝이 안 걸리고
--   13개 파티션을 전부 훑는다.
--
--   실측: 범위 조건·표현식 → partitions: 전체 13개
--         등치(=)만       → 해당 파티션 1개
--
--   ⚠️ 아래는 "성공 확인"이 아니라 반례다. 실행해 보면 partitions 칸에 13개가 나온다:
--     EXPLAIN SELECT * FROM tokens
--      WHERE queue_id = 'q_xyz'
--        AND issued_at >= '2026-04-01' AND issued_at < '2026-05-01';
--
--   해결(무료): 월말 집계는 파티션을 직접 지목한다. 어차피 배치가 대상 월을 알고 있다.
--     SELECT ... FROM tokens PARTITION (p2026_04) WHERE ...
--   → 위 Step 1·2의 FROM tokens 를 FROM tokens PARTITION (p2026_04) 로 바꾸면
--     범위 조건을 그대로 두고도 한 파티션만 읽는다.
--
--   ※ 파티션 표현식 자체를 바꾸는 것(예: RANGE COLUMNS(issued_at))은 전면 재구축이라
--     지금 하지 않는다. 집계는 배치 경로뿐이고 PARTITION 절로 해결된다.


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