# Queue Platform — 설계 결정 문서

> FRS v1.4 기준 | Entity 설계 / 보안 / 복구 전략 / 아키텍처

---

## 1. 기술 스택 결정 — WebFlux + R2DBC 유지

### 논의 배경
- R2DBC 레퍼런스 부족 (JOIN 쿼리, 트랜잭션, 연관관계 매핑)
- Virtual Thread + JPA 전환 검토

### 결정: WebFlux + R2DBC 유지

| 항목 | 내용 |
|------|------|
| API 서버 | Spring WebFlux + R2DBC 유지 |
| Batch 서버 | Spring MVC (JPA 검토 가능) |
| 이유 | Polling 10,000명 → 2,000 rps. Event loop non-blocking이 핵심 어필 포인트 |

### Virtual Thread 전환 시 문제
```
WebFlux 버리면 "왜 Virtual Thread?" 설명이 약해짐
"수만 명 동시 Polling" 포트폴리오 정체성 상실
```

### R2DBC 어려운 부분 해결 방향

| 문제 | 해결 |
|------|------|
| 연관관계 매핑 | ID 참조만. 조인 필요 시 별도 쿼리 조립 |
| 복잡한 JOIN | `@Query` 네이티브 SQL 또는 `DatabaseClient` |
| 트랜잭션 | `ReactiveTransactionManager` 사용 |

---

## 2. ID 전략 — 이중 ID 분리

### 결정

| 필드 | 타입 | 역할 | 사용 위치 |
|------|------|------|----------|
| `id` | `Long` | DB 내부 PK — 조인/FK | DB 내부에서만 |
| `tenantId` | `String` | 외부 식별자 | API 응답, Redis Key |

### 이유
```
Long PK 외부 노출 시:
  GET /tenants/1 → 몇 번째 가입자인지 추측 가능
  GET /tenants/2 → enumeration 공격 가능

String 랜덤 ID:
  "t_abc123" → 순서 추측 불가, 내부 구조 은닉
```

### Redis Key에 외부 식별자 사용
```
✅ queue:t_abc123:q_xyz789:0   (String 외부 식별자)
❌ queue:42:17:0               (Long PK — DB 구조 노출)
```
Long PK 사용 시 DB 마이그레이션 때 Redis Key 구조도 변경 필요 → 강결합

---

## 3. DATETIME(3) 전체 적용

### 결정
모든 timestamp 컬럼을 `DATETIME(3)` (밀리초 단위) 으로 통일

### 이유 — Redis 장애 복구 정확도

```
초 단위 DATETIME:
  200 rps → 1초에 200명 유입 → score 동일 → 순서 보장 불가

밀리초 단위 DATETIME(3):
  200 rps → 1ms에 0.2명 유입 → 충돌 확률 ≈ 0.2명/ms
  → FIFO 사실상 완전 복구
```

### 복구 코드
```java
// issuedAt → Sorted Set score
double score = token.issuedAt()
    .toInstant(ZoneOffset.UTC)
    .toEpochMilli();
```

### 동점자 처리
극소수 동점자 → tokenId lexicographic 순 → 대기열 서비스 특성상 허용 범위

---

## 4. API Key 설계

### 역할
서버 간 통신 인증 — "이 요청이 인증된 Tenant 서버에서 온 것"을 증명

### 인증 주체별 분리

| 호출 주체 | 인증 수단 | 이유 |
|----------|----------|------|
| Tenant 서버 | X-API-Key | 서버 간 통신 — 장기 자격증명 |
| 유저 | token | Polling 전용 — API Key 불필요 |
| Tenant 개발자 | JWT | 관리 콘솔 — 단기 인증 |

### 보안 3중 레이어

| 레이어 | 방법 | 효과 |
|--------|------|------|
| 전송 보안 | HTTPS | 헤더 암호화, 중간 노출 없음 |
| 저장 보안 | SHA-256 hash만 저장 | DB 털려도 원본 역산 불가 |
| 남용 방지 | per-key 100 rps | 탈취 시 피해 범위 제한 |

### SHA-256 선택 이유 (BCrypt 아닌)

| | 비밀번호 | API Key |
|---|---|---|
| 입력 | 사람이 타이핑 → 예측 가능 | 랜덤 256bit → 엔트로피 충분 |
| 해시 속도 | 느려야 함 (brute force 방어) | 빨라야 함 (요청마다 계산) |
| 알고리즘 | BCrypt | SHA-256 |

### 분실 처리
```
SHA-256은 단방향 — 복호화 불가
→ 원본 복구 불가능
→ Revoke 후 재발급이 유일한 방법
→ Tenant 계정(tenantId)에는 영향 없음
  (API Key는 tenantId에 종속된 자격증명)

Stripe, GitHub, AWS 동일 방식:
  "이 키는 생성 시 한 번만 표시됩니다"
```

### DB 테이블이 필요한 이유

| 기능 | Redis만으로 가능? | 이유 |
|------|-----------------|------|
| 인증 속도 | ✅ (캐시) | 60s TTL, DB QPS ≈ 0 |
| Revoke | ❌ | 취소할 대상 목록이 없음 |
| 목록 조회 | ❌ | Redis는 단건 조회만 가능 |
| 장애 복구 | ❌ | Redis 재시작 시 소멸 |

→ Redis는 캐시(속도), DB는 원본(관리)

### 이상 트래픽 감지
```
Rate limit(100 rps) → 1차 차단
모니터링 → Prometheus + Grafana 운영 레이어
  http_requests_total{status="429"} 메트릭 수집
  (별도 감지 로직 구현은 포트폴리오 범위 초과)
```

---

## 5. Redis 장애 복구 전략

### 복구 가능 항목

| 항목 | 복구 여부 | 방법 |
|------|----------|------|
| WAITING 토큰 목록 | ✅ 완전 복구 | DB tokens WHERE status='WAITING' |
| 큐 설정 | ✅ 완전 복구 | DB queues 테이블 |
| 대기열 순서 | ✅ 사실상 완전 | issued_at.toEpochMilli() → score |
| userId 역인덱스 | ✅ 재구성 가능 | WAITING 토큰에서 재구성 |
| global-seq | ⚠️ 근사 복구 | 최대 score를 seq로 설정 |
| 비활동 TTL | ❌ 복구 불가 | 전원 inactiveTtl 리셋 |
| avgWaitingTime | ❌ 복구 불가 | ETA null 반환 |

### 복구 순서
```
1단계: queue-meta Hash 재구성 (큐 설정)
2단계: WAITING 토큰 → Sorted Set 재구성 (issued_at 밀리초 → score)
3단계: global-seq 재구성 (최대 score)
4단계: queue-user 역인덱스 재구성 (userId → tokenId)
```

### 면접 답변
> "Redis 장애 시 DB를 원본으로 대기열을 재구성합니다.
> DATETIME(3) 밀리초 단위로 issued_at을 저장하므로
> 200 rps 기준 1ms 내 충돌 확률이 0.2명 수준으로
> FIFO를 사실상 완전 복구할 수 있습니다.
> 비활동 TTL과 avgWaitingTime은 복구 불가하며
> 각각 inactiveTtl 리셋, ETA null로 처리합니다."

---

## 6. tenantId 비정규화 (tokens 테이블)

### 결정
`tokens` 테이블에 `tenant_id` 컬럼 추가 (queues 테이블의 tenant_id 복사)

### 이유
```
비정규화 전: Batch TTL 탐색 시
  tokens JOIN queues ON tokens.queue_id = queues.queue_id
  WHERE queues.tenant_id = ?

비정규화 후:
  tokens WHERE tenant_id = ? AND status = 'WAITING'
  → 조인 제거, 인덱스 단순화
```

Batch가 30초마다 전체 WAITING 토큰을 탐색하는 구조에서 조인 비용 제거가 중요

---

## 7. 인덱스 설계 근거

| 인덱스 | 대상 쿼리 |
|--------|----------|
| `token_id + status` | Polling 인증 — 가장 빈번 (2,000 rps) |
| `queue_id + status + issued_at` | Batch TTL 만료 탐색 (30초 주기) |
| `queue_id + user_id + status` | userId 중복 체크 보조 |
| `key_hash` (unique) | API Key 인증 DB fallback |
| `tenant_id + name` (unique) | Tenant 내 큐 이름 중복 방지 |
| `tenant_id + status` (queues) | 활성 큐 목록 조회 |

---

## 8. Rich Domain Model + Hexagonal Architecture

### 결정
```
Service → 얇게 (흐름 조합만)
Domain Entity → 두껍게 (비즈니스 규칙 집중)
```

### Anemic vs Rich Domain 비교

```java
// ❌ Anemic — Service가 모든 걸 판단
public Mono<AdmitResult> admit(AdmitCommand command) {
    return tokenRepository.findByTokenId(command.token())
        .flatMap(token -> {
            if (token.getStatus() != WAITING) {  // 판단이 Service에
                return Mono.error(new QueueException(INVALID_STATUS));
            }
            token.setStatus(COMPLETED);          // 상태 변경이 Service에
            token.setCompletedAt(LocalDateTime.now());
            return tokenRepository.save(token);
        });
}

// ✅ Rich Domain — Service는 흐름만
public Mono<AdmitResult> admit(AdmitCommand command) {
    return tokenRepository.findByTokenId(command.token())
        .flatMap(token ->
            queueRedis.getGlobalRank(token)
                .map(token::admit)       // 판단 + 상태 변경이 Token 안에
                .flatMap(tokenRepository::save)
        )
        .map(AdmitResult::from);
}
```

### 도메인 Entity 책임

| Entity | 메서드 | 역할 |
|--------|--------|------|
| `Token` | `admit()` | WAITING 확인 + COMPLETED 전환 |
| `Token` | `cancel()` | WAITING 확인 + CANCELLED 전환 |
| `Token` | `expire(reason)` | WAITING 확인 + EXPIRED 전환 |
| `Token` | `isAdmittable(rank)` | rank=1 + WAITING 판단 |
| `Token` | `waitingSeconds()` | issuedAt ~ completedAt 계산 |
| `Queue` | `isEnqueueable()` | ACTIVE 상태 판단 |
| `Queue` | `isCapacityExceeded(count)` | maxCapacity 초과 판단 |
| `Queue` | `assignSlice(seq)` | seq % sliceCount 계산 |
| `ApiKey` | `isActive()` | ACTIVE 상태 판단 |
| `ApiKey` | `revoke()` | REVOKED 전환 |

### 패키지 구조 (queue-domain)

```
queue-domain
├── domain/
│   ├── Token.java          ← 핵심 도메인. 비즈니스 규칙 집중
│   ├── Queue.java
│   ├── Tenant.java
│   └── ApiKey.java
├── service/                ← 얇음. 흐름 조합만 (5~10줄 수준)
│   ├── AdmitService.java
│   ├── EnqueueService.java
│   └── PollingService.java
├── port/
│   ├── in/                 ← UseCase 인터페이스 (Adapter In이 호출)
│   │   ├── AdmitUseCase.java
│   │   ├── EnqueueUseCase.java
│   │   └── PollingUseCase.java
│   └── out/                ← Repository 인터페이스 (Domain이 정의, Infrastructure가 구현)
│       ├── TokenRepositoryPort.java
│       ├── QueueRepositoryPort.java
│       └── QueueRedisPort.java
└── exception/
    ├── QueueException.java
    └── ErrorCode.java
```

### 의존성 방향 원칙

```
Controller → UseCase(interface) ← Service → Port(interface) ← Adapter
    (api)      (domain/in)       (domain)    (domain/out)    (infrastructure)

핵심: 모든 화살표가 Domain을 향함
      Infrastructure가 Domain을 의존 (역방향 절대 금지)
      queue-domain은 Spring 의존 금지 (build.gradle에서 exclude)
```

### Gradle 모듈 의존성

```groovy
// queue-api
dependencies {
    implementation(project(":queue-domain"))
    implementation(project(":queue-infrastructure"))
}

// queue-infrastructure
dependencies {
    implementation(project(":queue-domain")) // Port 인터페이스 구현
}

// queue-domain
dependencies {
    implementation(project(":queue-common")) // Spring 의존 없음
}
```

---

## 9. Admit = Dequeue + 통계 갱신

### 결정
별도 Dequeue API 없음. Admit 한 번에 세 가지 처리.

```
Admit ──┬──▶ DB COMPLETED  (상태 확정 — 먼저)
        ├──▶ Redis ZREM    (Dequeue — 나중)
        └──▶ avgWaitingTime 갱신 (통계 — 마지막)
```

### 순서가 중요한 이유

```
ZREM 먼저 → DB 실패:
  대기열에서 제거됐는데 WAITING 상태 → 유저 영원히 대기 ❌

DB 먼저 → ZREM 실패:
  COMPLETED 기록됨 → Batch 30초 내 ZREM 재실행 (멱등) ✅
  Tenant 입장: 유저 이미 입장 허용 → 서비스 이용 중 → 피해 없음
  Platform 입장: Sorted Set에 잔류 → 30초 내 정리

avgWaitingTime 마지막인 이유:
  Admit 확정 후에야 정확한 대기시간(issuedAt ~ completedAt) 계산 가능
  다음 유저 ETA 계산에 사용 → 실제 Admit 데이터만 반영해야 정확
```

### ZREM 실패 시 잔류 상황

```
다음 유저가 rank=1 수신 → Tenant에 입장 요청
→ Tenant: GET /tokens/:token/status
  → status = COMPLETED (이미 입장됨) → ready: false
  → 스킵 후 다음 슬롯 대기
→ Batch 30초 내 ZREM 재실행 → 정리 완료

실제 피해: 없음
DB가 진실의 원본 (Source of Truth)
Redis 잔류 = 단순 정리 지연 (최대 30초)
```

---

## 10. rank=1 중복 불가 보장

### 정상 흐름
```
Sorted Set score 가장 낮은 1명 = rank 1
ZREM으로 제거 → 다음 score가 자동으로 rank 1
→ 항상 1명만 rank=1
```

### 동시 Admit 방어
```
두 명이 동시에 같은 token으로 Admit 호출
→ DB UPDATE WHERE status='WAITING' (1번만 성공)
→ 먼저 성공한 쪽만 COMPLETED
→ 나머지 → 409 QE_008_NOT_READY
```

### 면접 포인트
> "정상 흐름에서 rank=1은 항상 1명입니다.
> ZREM 실패 시 일시적 잔류가 가능하지만
> Status 확인 API로 COMPLETED 여부를 사전 검증하고
> Batch 30초 내 자동 정리되므로 실제 피해는 없습니다."

---

## 11. 재입장 재시도 로직

### 결정
Platform 관여 없음. Tenant ↔ 유저 클라이언트 사이의 문제.

```
Platform 역할: globalRank=1 → ready:true 반환 (끝)
Tenant 역할:   슬롯 여유 판단 → 없으면 유저에게 "대기" 응답
유저 클라이언트: Tenant 응답 보고 재시도
```

### Tenant 구현 선택지

| 방법 | 장점 | 단점 |
|------|------|------|
| 클라이언트 Polling (10초 간격) | 구현 단순, Platform 변경 없음 | Tenant 서버 부하 |
| SSE (Tenant → 유저 푸시) | 슬롯 생기면 즉시 입장, UX 좋음 | Tenant 구현 복잡 |
| Webhook | Tenant 완전 제어 | 유저 측 엔드포인트 필요 |

### 설계 원칙과의 일치
```
Platform이 슬롯 여유를 판단하면:
  → Platform이 Tenant 내부 구조에 의존 → 커플링 ❌

Tenant가 판단하고 Platform에 Admit 호출:
  → Platform은 순서만 관리 ✅
  → 설계 원칙 "Platform은 순서만 관리" 준수
```

---

## 12. token-status Redis Key 설계

### 결정
`token-status:{tokenId}` String key를 별도로 관리.

### 이유

Hash에 status 필드를 포함시키는 방식 대신 별도 String key를 선택한 이유:

| 항목 | Hash 방식 | String 별도 key |
|------|----------|----------------|
| 조회 | HGETALL (전체 읽기) | GET 한 번 |
| 메모리 | 더 사용 | 적음 |
| TTL 관리 | Hash 전체에 단일 TTL | 상태별 독립 TTL 가능 |
| rank 저장 | Hash에 저장 시 전체 업데이트 필요 | ZRANK로 실시간 계산 |

rank는 누군가 빠질 때마다 변경되는 값. Hash에 rank를 저장하면 뒤에 있는 모든 토큰을 업데이트해야 함. 대기자 1,000명이면 1명 빠질 때 1,000번 업데이트 발생 → ZRANK 실시간 계산이 현실적.

### TTL 전략

| 상태 | TTL | 이유 |
|------|-----|------|
| `WAITING` | waitingTtl | 대기 만료 시간과 동일하게 |
| `CAN_ENTER` | inactiveTtl | 비활동 감지 기준과 동일하게 |
| `COMPLETED` / `CANCELLED` / `EXPIRED` | 300s | 클라이언트 마지막 폴링 대응 |

---

## 13. CAN_ENTER 상태 설계

### 결정
Token 상태에 `CAN_ENTER` 추가. rank 1 도달 시 전이. 클라이언트에는 `isFirst: true`로만 노출.

### 이유

```
rank 1 도달을 클라이언트에 알리는 방법 두 가지:

방법 A: 상태값 직접 노출
  status: "CAN_ENTER" → 클라이언트가 상태값으로 판단
  → 내부 상태값 변경 시 클라이언트 코드도 수정 필요
  → Tenant마다 상태값 해석이 달라질 수 있음

방법 B: isFirst 플래그 + 상태값 숨김 (채택)
  CAN_ENTER → isFirst: true 변환 (PollingResponseDto)
  → 내부 상태값 변경이 클라이언트에 영향 없음
  → 클라이언트는 isFirst 하나만 보면 됨
  → breaking change 방지
```

### 클라이언트 응답 매핑

| 내부 상태 | isFirst | message (한국어) |
|----------|---------|----------------|
| `WAITING` | false | "대기 중입니다. 현재 {rank}번째입니다." |
| `CAN_ENTER` | true | "입장 가능합니다." |
| `COMPLETED` | false | "입장이 완료됐습니다." |
| `CANCELLED` | false | "대기열이 취소됐습니다." |
| `EXPIRED` | false | "대기 시간이 초과됐습니다." |

### 에러 케이스 처리

| 상황 | 처리 |
|------|------|
| CAN_ENTER 상태에서 inactiveTtl 초과 | Batch가 EXPIRED 처리 + 다음 1등 CAN_ENTER 전이 |
| CAN_ENTER 상태에서 유저 이탈 | CANCELLED 처리 + 다음 1등 CAN_ENTER 전이 |
| Lua Script 중 장애 | DB 우선 저장 → Batch 싱크 스케줄러 5분 내 복구 |

---

## 14. Batch 스케줄러 2개 분리

### 결정
만료 처리(30초)와 Redis 싱크(5분)를 별도 스케줄러로 분리.

### 이유

```
단일 Batch로 합치면:
  만료 처리 + Redis 불일치 복구를 30초마다 전부 실행
  → DB 전체 스캔이 30초마다 발생 → 부하

분리하면:
  TokenExpiryJob (30초): Redis 기준 만료 감지 → 빠름
  RedisSyncJob   (5분):  DB 기준 불일치 복구 → 주기 길어도 OK
  → 역할과 주기를 각각 최적화
```

### 처리 순서 원칙

두 스케줄러 모두 동일한 원칙 적용:

```
DB 먼저 → Redis 나중
Redis 실패 시 → RedisSyncJob이 5분 내 복구
DB가 Source of Truth
```

### 다국어 메시지 관리

### 결정
`messages.properties`로 상태별 메시지를 관리. Accept-Language 헤더 기반 자동 Locale 감지.

### 이유
- 내부 상태값을 노출하지 않으므로 클라이언트가 판단할 텍스트가 필요
- Tenant마다 언어가 다를 수 있음
- 언어 추가 시 파일만 추가하면 됨 → 코드 변경 없음
- Spring MessageSource가 Accept-Language 헤더 기반 Locale 자동 감지
