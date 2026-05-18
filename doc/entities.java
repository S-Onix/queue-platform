// ================================================================
// queue-infrastructure / entity
// JPA Entity 설계  |  FRS v1.10 기준
//
// [기술 스택]
//   Spring MVC + Tomcat + Virtual Thread
//   JPA (Hibernate) + JDBC
//   spring.threads.virtual.enabled=true → 모든 요청 Virtual Thread 처리
//
// [변경 이력]
//   - R2DBC @Table/@Id → JPA @Entity/@Id 전환
//   - Mono/Flux 반환 제거 → 일반 동기 반환
//   - status 필드 전체 String → int (TINYINT 매핑)
//   - TokenEntity: redis_sync_needed 필드 추가
//   - AdmitRequestEntity: 흐름 주석 Kafka 기준으로 변경
//   - billing_events, stats_events Entity 제거
//     (tokens 원본 집계로 대체 / avgWaitingTime 직접 갱신)
//   - BillingSnapshotEntity, QueueDailyStatsEntity 추가
//
// [설계 원칙]
// 1. id (Long)      : DB 내부 PK — 조인/FK 전용. 외부 노출 안 함
// 2. xxxId (String) : 외부 식별자 — API 응답 / Redis Key 노출용
//    → Long PK 노출 시 enumeration 공격 가능 (순차 증가 추측)
//    → String 랜덤 ID로 내부 구조 은닉
// 3. DATETIME(3)    : 밀리초 단위 저장
//    → Redis 장애 복구 시 issuedAt.toEpochMilli() → Sorted Set score
//    → 200 rps 기준 1ms 내 충돌 확률 ≈ 0.2명 → FIFO 사실상 완전 복구
// 4. status int     : TINYINT 매핑 (VARCHAR 대비 저장공간 절약 + 비교 성능)
//    → Java 상수로 변환해 가독성 유지
// ================================================================


// ----------------------------------------------------------------
// TenantEntity
// ----------------------------------------------------------------
@Entity
@Table(name = "tenants")
public class TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;               // DB 내부 PK — 외부 노출 안 함

    @Column(nullable = false, unique = true)
    private String tenantId;       // 외부 식별자 "t_abc123"
                                   // Redis Key: queue:{tenantId}:{queueId}:{slice}

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;   // BCrypt — Virtual Thread에서 처리 (별도 격리 불필요)

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int status;            // 0=ACTIVE, 1=DEACTIVATED (TINYINT)

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt; // DATETIME(3)

    protected TenantEntity() {}

    // 생성자, getter 생략
}


// ----------------------------------------------------------------
// ApiKeyEntity
//
// [역할] 서버 간 통신 인증 — "이 요청이 인증된 Tenant 서버에서 온 것"을 증명
// [인증 흐름]
//   X-API-Key → SHA-256 → Redis 캐시 60s (DB QPS ≈ 0)
//   캐시 미스 → DB key_hash 조회 → 캐시 갱신
// [보안 레이어]
//   전송: HTTPS / 저장: SHA-256 hash만 / 남용: per-key 100 rps
// [분실] Revoke 후 재발급. tenantId 영향 없음
// ----------------------------------------------------------------
@Entity
@Table(name = "api_keys")
public class ApiKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String apiKeyId;       // 외부 식별자 "ak_abc123"

    @Column(nullable = false)
    private Long tenantId;         // FK — Long으로 조인 (VARCHAR FK보다 인덱스 효율적)

    @Column(nullable = false, unique = true)
    private String keyHash;        // SHA-256(rawKey) — 64자 hex. 원본 저장 안 함

    @Column(nullable = false)
    private int status;            // 0=ACTIVE 1=REVOKED (TINYINT 매핑)

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt; // DATETIME(3)

    private LocalDateTime revokedAt; // DATETIME(3) — nullable

    protected ApiKeyEntity() {}

    // status 값 상수
    public static final int ACTIVE  = 0;
    public static final int REVOKED = 1;

    public boolean isActive() { return status == ACTIVE; }

    public void revoke() {
        this.status = REVOKED;
        this.revokedAt = LocalDateTime.now();
    }
}


// ----------------------------------------------------------------
// QueueEntity
// ----------------------------------------------------------------
@Entity
@Table(name = "queues")
public class QueueEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String queueId;        // 외부 식별자 "q_xyz789"

    @Column(nullable = false)
    private Long tenantId;         // FK

    @Column(nullable = false)
    private String name;           // Tenant 내 유일

    @Column(nullable = false)
    private int maxCapacity;       // 대기열 최대 인원

    @Column(nullable = false)
    private int sliceCount;        // ceil(maxCapacity ÷ 100,000) — Platform 자동 계산

    @Column(nullable = false)
    private int waitingTtl;        // 대기 절대 만료 시간 (기본 7200s)

    @Column(nullable = false)
    private int inactiveTtl;       // 비활동 만료 시간 (기본 300s)

    @Column(nullable = false)
    private int status;            // 0=ACTIVE 1=PAUSED 2=DRAINING 3=DELETED (TINYINT 매핑)

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt; // DATETIME(3)

    private LocalDateTime deletedAt; // DATETIME(3) — nullable

    protected QueueEntity() {}

    public static final int ACTIVE   = 0;
    public static final int PAUSED   = 1;
    public static final int DRAINING = 2;
    public static final int DELETED  = 3;

    public boolean isEnqueueable() { return status == ACTIVE; }
}


// ----------------------------------------------------------------
// TokenEntity
//
// [상태 전환]
//   WAITING(0) → ADMIT_ISSUED(1) : admit API — N명 입장 토큰 발급
//   ADMIT_ISSUED(1) → COMPLETED(2): complete API — 입장 완료 통보
//   ADMIT_ISSUED(1) → WAITING(0)  : admitToken TTL 60초 초과 → Batch 복귀, seq 유지
//   WAITING(0) → CANCELLED(3)     : DELETE — WAITING만 허용. ADMIT_ISSUED → 409
//   WAITING(0) → EXPIRED(4)       : Batch — waitingTtl / inactiveTtl 초과
//
// [seq 컬럼]
//   Enqueue 시 INCRBY로 받은 global-seq 값 저장
//   ADMIT_ISSUED → WAITING 복귀 시 Redis ZADD score 복원에 사용
//   DB에 없으면 순위 복원 불가 → 필수 컬럼
//
// [redis_sync_needed]
//   Redis 다운 중 DB INSERT 됐지만 Redis Sorted Set 미반영 토큰 추적
//   0 = Redis 반영 완료 (정상)
//   1 = Redis 미반영 (Redis 다운 중 INSERT됨)
//   복구 배치 완료 후 0으로 초기화
//
// [Redis 장애 복구]
//   WAITING 토큰 목록 → DB 완전 복구
//   순서 복구 → issuedAt.toEpochMilli() → score (DATETIME(3) 밀리초)
//     충돌 확률 ≈ 0.2명/ms (200 rps 기준) → FIFO 사실상 완전 복구
//   비활동 TTL → 복구 불가 → 전원 inactiveTtl 리셋
//   avgWaitingTime → 복구 불가 → ETA null
//
// [tenant_id 비정규화]
//   queues 조인 없이 tenantId 기준 쿼리 가능
//   Batch TTL 탐색, 과금 집계 조인 제거
//
// [파티셔닝]
//   issued_at 기준 월별 Range 파티셔닝
//   PRIMARY KEY (id, issued_at) — MySQL 파티션 키 PK 포함 필수
// ----------------------------------------------------------------
@Entity
@Table(name = "tokens")
public class TokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String tokenId;        // 외부 식별자 "tok_Kx9mZ3" — Polling 인증 키

    @Column(nullable = false)
    private String queueId;        // FK (queue.queue_id 외부 식별자 참조)

    @Column(nullable = false)
    private Long tenantId;         // 비정규화 — queues 조인 없이 tenantId 쿼리 가능

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private Long seq;              // global-seq 값. ADMIT_ISSUED→WAITING 복귀 시 ZADD score 복원

    @Column(nullable = false)
    private int status;            // 0=WAITING 1=ADMIT_ISSUED 2=COMPLETED 3=CANCELLED 4=EXPIRED

    private String admitToken;     // nullable. admit 시 SET.
                                   // 용도 1: Polling ADMIT_ISSUED 응답 시 admitToken 반환
                                   //   → admit-token-by-token Redis 미스 시 DB fallback
                                   // 용도 2: verify DB Fallback 시 조회 기준 (issued_at 60초 이내)
                                   // complete 후 그대로 유지 (불필요한 UPDATE 제거)

    private Integer expiredReason; // null 허용. 0=WAITING_TTL 1=INACTIVE_TTL 2=ADMIT_TOKEN_TTL

    @Column(nullable = false)
    private int redisSyncNeeded;   // 0=Redis 반영완료 1=미반영 (Redis 다운 중 INSERT됨)

    // DATETIME(3) 핵심 필드
    // Redis 복구 시: issuedAt.toInstant(ZoneOffset.UTC).toEpochMilli() → score
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime issuedAt;  // DATETIME(3)

    private LocalDateTime completedAt; // DATETIME(3) — nullable (complete 시점)
    private LocalDateTime cancelledAt; // DATETIME(3) — nullable
    private LocalDateTime expiredAt;   // DATETIME(3) — nullable

    protected TokenEntity() {}

    // status 상수
    public static final int WAITING      = 0;
    public static final int ADMIT_ISSUED = 1;
    public static final int COMPLETED    = 2;
    public static final int CANCELLED    = 3;
    public static final int EXPIRED      = 4;

    // expiredReason 상수
    public static final int REASON_WAITING_TTL     = 0;
    public static final int REASON_INACTIVE_TTL    = 1;
    public static final int REASON_ADMIT_TOKEN_TTL = 2;

    public boolean isWaiting()     { return status == WAITING; }
    public boolean isAdmitIssued() { return status == ADMIT_ISSUED; }
    public boolean needsRedisSync() { return redisSyncNeeded == 1; }

    public double redisScore() {
        return issuedAt.toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
    }

    // 도메인 메서드
    public void complete(LocalDateTime completedAt) {
        if (status != ADMIT_ISSUED) throw new QueueException(ErrorCode.QE_006_INVALID_STATUS);
        this.status = COMPLETED;
        this.completedAt = completedAt;
    }

    public void cancel(LocalDateTime cancelledAt) {
        if (status != WAITING) throw new QueueException(ErrorCode.QE_006_INVALID_STATUS);
        this.status = CANCELLED;
        this.cancelledAt = cancelledAt;
    }

    public void expire(int reason, LocalDateTime expiredAt) {
        this.status = EXPIRED;
        this.expiredReason = reason;
        this.expiredAt = expiredAt;
    }

    public void returnToWaiting() {
        // admitToken TTL 만료 → WAITING 복귀 (seq 유지 → 순위 보존)
        this.status = WAITING;
        this.admitToken = null;
    }
}


// ----------------------------------------------------------------
// AdmitRequestEntity
//
// [역할] Tenant admit 요청 영속성 + 멱등성 보장
// [흐름 — Kafka 도입 후]
//   Tenant → POST /queues/:queueId/admit { count: N }
//   → admit_requests INSERT (status=PENDING)    ← 영속성 기준점
//   → Kafka enqueue-admit topic produce         ← 처리 트리거
//   → Kafka Consumer 처리 완료
//   → admit_requests UPDATE (status=COMPLETED)
//
// [멱등성]
//   request_id UNIQUE → 중복 INSERT 차단
//   Kafka Consumer: DB PENDING 확인 후 처리 → 중복 방지
//
// [정리] 7일 후 COMPLETED 건 배치 삭제 (LIMIT 10000으로 부하 제어)
//
// [왜 Redis-only 불가?]
//   Redis 장애 시 요청 유실 → 과금 근거 소멸
//   서버 200 OK 응답 후 미처리 → Tenant 감지 불가
//   재요청 시 중복 처리 위험 (멱등성 보장 불가)
// ----------------------------------------------------------------
@Entity
@Table(name = "admit_requests")
public class AdmitRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String requestId;      // 외부 식별자 + 멱등성 키

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String queueId;

    @Column(nullable = false)
    private int count;             // 요청 인원 수 (최대 1000)

    @Column(nullable = false)
    private int status;            // 0=PENDING 1=PROCESSING 2=COMPLETED (TINYINT 매핑)

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt; // DATETIME(3)

    private LocalDateTime completedAt; // DATETIME(3) — nullable

    protected AdmitRequestEntity() {}

    public static final int PENDING    = 0;
    public static final int PROCESSING = 1;
    public static final int COMPLETED  = 2;

    public boolean isPending() { return status == PENDING; }
}


// ----------------------------------------------------------------
// BillingSnapshotEntity
//
// [역할] Tenant별 월별 과금 집계 — 청구 기준
// [집계 방식]
//   tokens 원본에서 직접 집계 (billing_events 불필요)
//   BillingSnapshotJob (M+2월 초):
//     SELECT COUNT(*) FROM tokens WHERE issued_at BETWEEN M월
//     GROUP BY tenant_id
//     → UPSERT (ON DUPLICATE KEY UPDATE)
//
// [billing_events 제거 이유]
//   tokens가 원본 → 중복 처리 개념 없음
//   집계 시점에 원본 조회 → 항상 정확
// ----------------------------------------------------------------
@Entity
@Table(name = "billing_snapshots")
public class BillingSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 6)
    private String yearMonth;       // "202604" 형식

    @Column(nullable = false)
    private long count;             // 해당 월 Enqueue 건수 (= 과금 기준)

    @Column(nullable = false)
    private LocalDateTime updatedAt; // ON UPDATE CURRENT_TIMESTAMP(3)

    protected BillingSnapshotEntity() {}
}


// ----------------------------------------------------------------
// QueueDailyStatsEntity
//
// [역할] tokens 파티션 DROP 후 과금/통계 근거 영구 보존
//        INSERT-only 불변 테이블 (감사 기준)
//
// [집계 시점]
//   BillingSnapshotJob (M+2월 초, 파티션 DROP 전):
//     issued_at 기준 M월 데이터 집계 → INSERT
//     ON DUPLICATE KEY UPDATE id = id (멱등: 배치 재실행 안전)
//
// [tokens와의 관계]
//   논리적: tokens → (집계) → queue_daily_stats
//   물리적: FK 없음 (의도적)
//   이유: tokens 파티션 DROP 시 FK 무결성 위반 방지
//         queue_daily_stats의 목적 = tokens 소멸 후 영구 보존
//
// [파티션 유예 전략]
//   M월 파티션은 M+2월 초 DROP (1달 유예)
//   월말 걸친 토큰이 complete될 때까지 보존 → 과금 누락 없음
// ----------------------------------------------------------------
@Entity
@Table(name = "queue_daily_stats")
public class QueueDailyStatsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String queueId;

    @Column(nullable = false)
    private LocalDate statDate;         // DATE — issued_at 기준 집계 날짜

    @Column(nullable = false)
    private int totalEnqueued;

    @Column(nullable = false)
    private int totalCompleted;

    @Column(nullable = false)
    private int totalCancelled;

    @Column(nullable = false)
    private int totalExpired;

    @Column(nullable = false)
    private int totalAdmitCount;

    private Integer avgWaitSec;         // nullable (완료 건 없으면 null)
    private Integer maxWaitSec;         // nullable

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;    // DATETIME(3)

    protected QueueDailyStatsEntity() {}
}


// ================================================================
// ApiKeyHasher — queue-domain / util
//
// [SHA-256 vs BCrypt]
//   비밀번호 → BCrypt: 사람 입력 → 예측 가능 → 느린 해시 필요
//   API Key  → SHA-256: 랜덤 256bit → 엔트로피 충분 → 빠른 해시 필요
//
// [단방향 해싱]
//   복호화 불가. 인증은 "동일하게 해싱 후 비교"로 처리
//   분실 시 Revoke 후 재발급. tenantId 영향 없음
//   발급 시 rawKey는 딱 한 번만 반환 (Platform도 원본 모름)
// ================================================================
public class ApiKeyHasher {

    public static String hash(String rawApiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(
                rawApiKey.getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(hashBytes); // 64자 hex
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // Decrypt ❌ — 필요 없음. 비교만 함
    public static boolean matches(String rawApiKey, String storedHash) {
        return hash(rawApiKey).equals(storedHash);
    }
}


// ================================================================
// ApiKeyService 발급 플로우 — 참고용
// Virtual Thread 환경: blocking JPA 호출 → 별도 격리 불필요
// ================================================================
//
// @Service
// @Transactional
// public class ApiKeyService {
//
//     public ApiKeyIssueResponse issueApiKey(Long tenantId) {
//         String rawKey = generateRawKey();        // "sk_live_" + 랜덤 256bit
//         String hash   = ApiKeyHasher.hash(rawKey);
//
//         ApiKeyEntity saved = apiKeyRepository.save(
//             new ApiKeyEntity(hash, tenantId, ...)
//         );
//
//         return new ApiKeyIssueResponse(
//             saved.getApiKeyId(),
//             rawKey,   // ← 딱 여기서만 반환. 이후 Platform도 원본 모름
//             "이 키는 지금만 표시됩니다. 안전한 곳에 보관하세요."
//         );
//     }
// }
