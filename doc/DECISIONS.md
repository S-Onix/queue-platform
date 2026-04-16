# Queue Platform — 설계 결정 문서

> FRS v1.10 기준 | Entity 설계 / 보안 / 복구 전략 / 아키텍처
>
> v1.10 변경: §28, §35 SDK 전략 개정. §45 "Java SDK 제거 — 언어 중립 REST 전략" 신규 ADR 추가.

---

## 1. 기술 스택 결정 — Spring MVC + Virtual Thread + JPA

### 논의 배경
- 초기: Spring WebFlux + Netty + R2DBC 구성
- R2DBC 레퍼런스 부족 (JOIN 쿼리, 트랜잭션, 연관관계 매핑 어려움)
- Java 21 Virtual Thread 정식 도입 → blocking I/O도 OS Thread 고갈 없이 처리 가능
- WebFlux + Reactor 체인의 코드 복잡도 → 유지보수 어려움

### 결정: Spring MVC + Tomcat + Virtual Thread + JPA

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| API 서버 | Spring WebFlux + Netty | Spring MVC + Tomcat |
| 반환 타입 | Mono<T> / Flux<T> | T (일반 동기 반환) |
| DB 레이어 | R2DBC | JPA (Hibernate) + JDBC |
| Redis 클라이언트 | ReactiveRedisTemplate | RedisTemplate |
| Kafka | reactor-kafka | spring-kafka |
| Thread 격리 | subscribeOn(virtualThreadScheduler) | 불필요 (VT 자동 처리) |
| Batch 서버 | Spring MVC + Tomcat | Spring MVC + Tomcat (동일) |

### Virtual Thread 적용

```yaml
# application.yml — 한 줄로 적용
spring:
  threads:
    virtual:
      enabled: true  # Tomcat의 모든 요청을 Virtual Thread로 처리
```

```
설정 한 줄로 모든 요청이 Virtual Thread에서 처리됨.
JPA blocking I/O → OS Thread를 점유하지 않고 대기
→ 기존 WebFlux Event Loop 없이도 Polling 2,000 rps 달성 가능
BCrypt → 별도 스케줄러 격리 불필요 (VT가 처리)
@Transactional + ThreadLocal → Virtual Thread에서 정상 동작
```

### 전환 이유 (면접 서사)

```
"처음엔 Polling 2,000 rps를 위해 WebFlux를 선택했습니다.
그런데 Java 21 Virtual Thread가 정식 도입되면서
blocking I/O도 OS Thread를 점유하지 않아
Spring MVC로도 동일한 동시성을 달성할 수 있습니다.
WebFlux + R2DBC 조합은 레퍼런스가 적고 JPA 대비 생산성이 낮으며
Reactor 체인이 코드 가독성을 떨어뜨립니다.
spring.threads.virtual.enabled=true 한 줄로
기존 Spring 생태계를 그대로 활용하면서
Polling 2,000 rps 목표를 달성했습니다."
```

### OS Thread vs Virtual Thread 비교

```
OS Thread (Spring MVC 기본):
  Tomcat 기본 스레드 수: 200개
  200명 동시 blocking → 스레드 고갈 → 503

Virtual Thread (spring.threads.virtual.enabled=true):
  요청마다 새 Virtual Thread 생성 (수백 KB)
  blocking 시 OS Thread 반납 → 다른 요청 처리
  → Tomcat 기본 스레드풀(200개)로 수천 rps 처리 가능
```

### 동시 VT 수 계산 (실제 부하 기준)

VT는 요청마다 생성되지만 처리 시간 동안만 존재한다.
**동시 VT 수 = rps × 평균 처리 시간(초)**

```
10,000 rps Enqueue + 2,000 rps Polling + 3,000 rps 업데이트 상황:

Enqueue VT:
  Bulk Worker CompletableFuture.get() 대기: 평균 5ms
  + Kafka produce: ~3ms → 총 ~8ms
  10,000 × 0.008 = 80개

Polling VT:
  Redis 순위 계산 + token-info 캐시: ~5ms
  (캐시 히트 시 DB 접근 없음)
  2,000 × 0.005 = 10개

업데이트 VT (admit/complete/cancel):
  DB UPDATE + Redis ZREM + Kafka produce: ~20~50ms
  3,000 × 0.05 = 150개

합산 동시 VT: ~240개 → 메모리 수십 MB → 문제 없음
```

```
VT가 2,000개 동시 존재하는 게 아닌 이유:
  2,000 rps × 처리 시간 5ms = 2,000 × 0.005 = 10개
  "초당 2,000개 생성"과 "동시에 2,000개 존재"는 다름

OS Thread 점유 시간:
  VT가 Redis/DB/Kafka I/O 대기 중 → OS Thread 반납
  실제 CPU 연산 구간만 점유 (수 μs ~ 수 ms)
  → Tomcat 200개 OS Thread로 수만 rps 처리 가능
```

### 면접 포인트
> "Virtual Thread는 요청마다 생성되지만
> 처리 시간이 10ms라면 동시에 존재하는 VT는
> 10,000 rps × 0.01초 = 100개 수준입니다.
> Polling 2,000 rps, 업데이트 3,000 rps를 더해도
> 동시 VT는 약 240개로 메모리 부담이 거의 없습니다.
> VT는 I/O 대기 중 OS Thread를 반납하므로
> Tomcat 기본 스레드풀(200개)로 충분히 처리됩니다.
> 실제 병목은 VT가 아니라 Redis 싱글스레드입니다."

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
double score = token.getIssuedAt()
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

---

## 5. Redis 장애 복구 전략

### 복구 가능 항목

| 항목 | 복구 여부 | 방법 |
|------|----------|------|
| WAITING 토큰 목록 | ✅ 완전 복구 | DB tokens WHERE status=WAITING |
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
  tokens WHERE tenant_id = ? AND status = WAITING
  → 조인 제거, 인덱스 단순화
```

Batch가 10초마다 전체 WAITING 토큰을 탐색하는 구조에서 조인 비용 제거가 중요

---

## 7. 인덱스 설계 근거

| 인덱스 | 대상 쿼리 |
|--------|----------|
| `token_id + status` | Polling 인증 — 가장 빈번 (2,000 rps) |
| `queue_id + status + issued_at` | Batch TTL 만료 탐색 (10초 주기) |
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
public CompleteResult complete(CompleteCommand command) {
    TokenEntity token = tokenRepository.findByTokenId(command.token())
        .orElseThrow();
    if (token.getStatus() != WAITING) {          // 판단이 Service에
        throw new QueueException(INVALID_STATUS);
    }
    token.setStatus(COMPLETED);                  // 상태 변경이 Service에
    token.setCompletedAt(LocalDateTime.now());
    return CompleteResult.from(tokenRepository.save(token));
}

// ✅ Rich Domain — Service는 흐름만
public CompleteResult complete(CompleteCommand command) {
    TokenEntity token = tokenRepository.findByTokenId(command.token())
        .orElseThrow();
    token.complete(LocalDateTime.now());         // 판단 + 상태 변경이 Token 안에
    return CompleteResult.from(tokenRepository.save(token));
}
```

### 도메인 Entity 책임

| Entity | 메서드 | 역할 |
|--------|--------|------|
| `Token` | `complete()` | ADMIT_ISSUED 확인 + COMPLETED 전환 |
| `Token` | `cancel()` | WAITING 확인 + CANCELLED 전환 |
| `Token` | `expire(reason)` | WAITING 확인 + EXPIRED 전환 |
| `Token` | `returnToWaiting()` | admitToken TTL 만료 → WAITING 복귀 |
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
// queue-common
dependencies {
    // 외부 의존 최소화
    // 모든 모듈이 이 모듈을 직접 의존
}

// queue-domain
dependencies {
    implementation(project(":queue-common"))
    // Spring 의존 없음 (순수 Java)
}

// queue-infrastructure
dependencies {
    implementation(project(":queue-domain")) // Port 인터페이스 구현
    implementation(project(":queue-common"))
}

// queue-api
dependencies {
    implementation(project(":queue-domain"))
    implementation(project(":queue-infrastructure"))
    implementation(project(":queue-common")) // 직접 의존 (명시적)
}

// queue-batch
dependencies {
    implementation(project(":queue-domain"))
    implementation(project(":queue-infrastructure"))
    implementation(project(":queue-common")) // 직접 의존 (명시적)
}
```

```
queue-common을 각 모듈에서 직접 선언하는 이유:
  implementation은 컴파일 타임 전파 안 됨
  api(project(":queue-common"))으로 전파할 수 있지만
  명시적 직접 선언이 의존성 추적에 더 명확함
  ErrorCode, ApiResponse 등 어느 모듈에서나 직접 사용
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
  COMPLETED 기록됨 → Batch 10초 내 ZREM 재실행 (멱등) ✅
  Tenant 입장: 유저 이미 입장 허용 → 서비스 이용 중 → 피해 없음
  Platform 입장: Sorted Set에 잔류 → 10초 내 정리

avgWaitingTime 마지막인 이유:
  Admit 확정 후에야 정확한 대기시간(issuedAt ~ completedAt) 계산 가능
  다음 유저 ETA 계산에 사용 → 실제 Admit 데이터만 반영해야 정확
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
두 명이 동시에 같은 token으로 complete 호출
→ DB UPDATE WHERE status=ADMIT_ISSUED (1번만 성공)
→ 먼저 성공한 쪽만 COMPLETED
→ 나머지 → 409 QE_006_INVALID_STATUS
```

### 면접 포인트
> "정상 흐름에서 rank=1은 항상 1명입니다.
> ZREM 실패 시 일시적 잔류가 가능하지만
> DB UPDATE WHERE status=1로 complete 동시성을 제어하고
> Batch 10초 내 자동 정리되므로 실제 피해는 없습니다."

---

## 11. 재입장 재시도 로직

### 결정
Platform 관여 없음. Tenant ↔ 유저 클라이언트 사이의 문제.

```
Platform 역할: globalRank=1 → ready:true 반환 (끝)
Tenant 역할:   슬롯 여유 판단 → 없으면 유저에게 "대기" 응답
유저 클라이언트: Tenant 응답 보고 재시도
```

### 설계 원칙과의 일치
```
Platform이 슬롯 여유를 판단하면:
  → Platform이 Tenant 내부 구조에 의존 → 커플링 ❌

Tenant가 판단하고 Platform에 Admit 호출:
  → Platform은 순서만 관리 ✅
```

---

## 12. Admit 방식 전면 변경

### 변경 방식
```
Tenant → Platform POST /queues/:queueId/admit { count: N }
Platform → 앞 N명 입장 토큰(admitToken) 발급 (TTL 60초)
유저 → Polling으로 admitToken 수신
유저 → Tenant에 admitToken 전달
Tenant → Platform POST /admit-tokens/:admitToken/verify
→ COMPLETED
```

### 변경 이유
```
Backpressure 패턴 적용:
  Publisher  = 대기열
  Subscriber = Tenant (request(N) = admit { count: N })
  → Tenant가 소화 가능한 만큼만 요청 → 과부하 방지
```

---

## 13. 입장 토큰(admitToken) 설계

### TTL = 60초

```
근거:
  Polling 주기(최소 2s) + 네트워크(1~2초) + 유저 행동(수초) + 여유
  → 60초 (여유분 충분히 확보)

30초에서 60초로 변경한 이유:
  30초: 여유 부족 → WAITING 복귀 빈번 발생
  60초: 정상 흐름에서 만료 거의 없음
```

### 만료 시 우선순위 유지 (WAITING 복귀)

```
admitToken TTL 60초 초과
  → WAITING 복귀 (EXPIRED 아님)
  → seq(Sorted Set score) 그대로 유지
  → 다음 admit 호출 시 앞순서면 재발급

이유:
  유저 귀책 아닌 네트워크 지연으로 만료 가능
  순위 박탈은 UX상 불합리
```

---

## 14. admit 요청 순서 보장 — Kafka

### 해결: Kafka enqueue-admit + AdmitConsumer

```
Tenant 요청 → admit_requests DB INSERT (PENDING) — 영속성 기준점
             → Kafka enqueue-admit 발행
AdmitConsumer → Kafka 지속 구독
             → 메시지 수신 시 즉시 처리
             → DB PENDING 확인 (멱등성)
             → Lua Dequeue + admitToken 발급
             → DB COMPLETED
```

### 멀티 서버 환경
```
Kafka Consumer Group:
  같은 Consumer Group → 파티션별 하나의 Consumer에만 전달
  → 자동으로 중복 처리 방지
  → Consumer 추가 시 파티션 리밸런싱으로 선형 확장
```

---

## 15. Token 상태 추가 — ADMIT_ISSUED

### 변경
```
기존: WAITING → COMPLETED
변경: WAITING → ADMIT_ISSUED → COMPLETED
              → WAITING (admitToken 만료 시, 순위 유지)
```

### 면접 포인트
> "admitToken 만료 시 EXPIRED가 아닌 WAITING으로 복귀하는 이유는
> 네트워크 지연 등 유저 귀책이 아닌 사유로 만료될 수 있기 때문입니다.
> seq를 유지함으로써 우선순위를 보존하고
> 다음 admit 호출 시 자동으로 재발급됩니다."

---

## 16. 대용량 처리 — DB

### INSERT (Enqueue)
```
묶음 크기: 1000건 (Kafka Consumer 버퍼링)
재시도: Kafka At-Least-Once 보장
최종 실패: Consumer Offset 미커밋 → 재처리

Bulk INSERT:
  INSERT INTO tokens VALUES (tok1,...),(tok2,...),...(tok1000,...)
  → DB 왕복 횟수 1/1000으로 감소
```

### SELECT (Polling) — Read/Write 분리
```
2,000 rps → DB 한계 초과 가능

해결:
  Read Replica → Polling SELECT
  Master       → INSERT/UPDATE

token Redis 캐싱:
  SET token-info:{tokenId} {status, queueId} EX nextPollAfterSec+2s
  상태 변경 시 즉시 갱신 (TTL 기다리지 않음)
  → DB QPS ≈ 0 (캐시 히트 시)
```

### UPDATE (complete / Batch)
```
청크 크기: 100건
청크 간 대기: 10ms (서비스 쿼리에 DB 양보)
순서: 순차 처리

Batch UPDATE:
  for (List<String> chunk : partition(tokenIds, 100)) {
      tokenRepository.bulkExpire(chunk);
      Thread.sleep(10); // Virtual Thread → OS Thread 반납
  }
```

---

## 17. 대용량 처리 — Redis

### Enqueue Lua — Bulk Worker 항상 활성

```
분기 없이 항상 Bulk Worker 경로만 사용

이유:
  적응형 분기(rps 측정 → 모드 전환)는 복잡도만 높음
  낮은 rps에서도 Bulk Worker는 문제없이 동작
  일관된 코드 경로 → 테스트/디버깅 단순

낮은 rps(200)에서 Bulk Worker 동작:
  10ms마다 flush → 10ms 동안 유입: 200 × 0.01 = 2건
  2건씩 Lua 실행 → INCRBY 2, ZADD 2건
  p99 지연: 최대 10ms (flush 대기) → 허용 범위

높은 rps(10,000)에서:
  500건이 0.05초 만에 채워짐 → 즉시 flush
  Lua 20번/초 → Redis ops 대폭 감소
```

### Bulk Worker 구현

```java
@Component
public class EnqueueBulkWorker {

    private final BlockingQueue<PendingEnqueue> queue =
        new LinkedBlockingQueue<>();

    // 항상 Bulk Worker 경로
    public EnqueueResult enqueue(EnqueueCommand cmd) {
        CompletableFuture<EnqueueResult> future = new CompletableFuture<>();
        queue.offer(new PendingEnqueue(cmd, future));
        return future.get(5, TimeUnit.SECONDS); // VT → blocking OK
    }

    @Scheduled(fixedDelay = 10) // 10ms마다 flush
    public void flush() {
        List<PendingEnqueue> batch = new ArrayList<>();
        queue.drainTo(batch, 500); // 최대 500건 (조건 1)
        if (batch.isEmpty()) return;

        // INCRBY N → seq 블록 채번
        // 슬라이스별 ZADD multi-member NX
        List<EnqueueResult> results = redisPort.executeBulkLua(batch);

        for (int i = 0; i < batch.size(); i++) {
            batch.get(i).future().complete(results.get(i));
        }
    }
}
```

```
flush 조건:
  조건 1: 500건 모이면 즉시 (drainTo 상한)     ← 높은 rps
  조건 2: 10ms 경과 시 그냥 flush (@Scheduled) ← 낮은 rps

Spring MVC에서 Reactor bufferTimeout을 대체하는 방식:
  bufferTimeout(500건 or 100ms) ← Reactor 스트림 기반 (WebFlux)
  Bulk Worker(500건 or 10ms)    ← BlockingQueue 기반 (Spring MVC + VT)
  → 역할 동일. 구현 방식만 다름
```

### Batch 주기
```
10초 (변경: 30초 → 10초)
  TTL 만료 토큰 빠른 정리 + admit 불일치 감소
```

### 면접 포인트
> "Spring MVC 환경에서는 Reactor bufferTimeout을 사용할 수 없습니다.
> 대신 BlockingQueue와 @Scheduled를 조합한 Bulk Worker로
> 동일한 역할을 구현하고 항상 이 경로만 사용합니다.
> 낮은 rps에서는 10ms마다 소량 flush되고
> 높은 rps에서는 500건이 채워지면 즉시 flush됩니다.
> 분기 로직 없이 동일한 코드 경로로 모든 rps를 처리합니다."

---

## 18. 대용량 처리 — 로직

### 멱등성 — Redis idempotency key
```
채택: Redis idempotency key
  SET admit-idem:{requestId} {result} EX 300 NX
  → 이미 처리된 requestId → 저장된 결과 반환
  → 멀티 서버 보장
```

### 비동기 INSERT 유실
```
Kafka At-Least-Once 보장:
  Consumer 장애 → Offset 미커밋 → 재시작 시 재처리
  DB UNIQUE KEY → 중복 INSERT 자동 방어
```

### ADMIT_ISSUED → WAITING 복귀 (seq 복원)
```
문제:
  admitToken TTL 60초 초과 → WAITING 복귀
  Redis ZADD 시 원래 seq(score) 필요

해결:
  tokens 테이블에 seq 컬럼 저장 ✅
  Enqueue 시 INCRBY로 받은 seq → DB 저장

복구 흐름:
  Batch: EXISTS admit-token-by-token:{tokenId} = 0 감지
  DB SELECT WHERE status=ADMIT_ISSUED AND tokenId=?
  → seq 조회
  → Redis ZADD queue:{t}:{q}:{slice} {seq} {tokenId}
  → DB UPDATE status=WAITING
```

---

## 19. 대용량 처리 — 병렬 처리

### Batch 병렬화
```
큐별 독립 처리
동시 처리 큐 수: 10개
큐별 타임아웃: 8초 (10초 주기 내 완료)

ExecutorService batchExecutor = Executors.newVirtualThreadPerTaskExecutor();

for (QueueEntity queue : activeQueues) {
    batchExecutor.submit(() -> processQueue(queue)); // Virtual Thread
}
```

### admit 워커 병렬화
```
Kafka Consumer Group:
  queueId 기준 파티셔닝
  → 같은 큐의 admit → 같은 Consumer 처리 → 순서 보장
  → 다른 큐 → 다른 Consumer → 병렬 처리
```

### admit 1000건 한 번에 처리

admit count=1000 시 세 구간이 병목이 된다.

**① Redis ZREM 1000건**
```
문제:
  ZREM multi-member 1000개 → Lua 실행 시간 수십 ms
  Redis 싱글스레드 블로킹 → Polling 등 다른 요청 대기

해결: 슬라이스별 분할 처리 (이미 설계됨 ✅)
  슬라이스 3개 → Lua 1회당 ~333건
  블로킹 시간 1/3로 감소
  sliceCount가 클수록 자동으로 분산
```

**② DB UPDATE 1000건**
```
해결: 100건 청크 순차 처리 (이미 설계됨 ✅)
  청크 간 10ms 대기 → DB 서비스 쿼리에 양보
  Gap Lock 방지
  전체 소요: ~100ms + DB 처리 → admitToken TTL 60초 내 충분
```

**③ admitToken SET 1000건 → Pipeline**
```
문제:
  SET admit-token-by-token:{tokenId} × 1000
  SET admit-token-by-admit:{admitToken} × 1000
  → Redis 2,000번 왕복

해결: RedisTemplate.executePipelined()
  Spring MVC + RedisTemplate에서도 Pipeline 사용 가능
  (Pipeline은 Redis 클라이언트(Lettuce) 수준 기능 → WebFlux/MVC 무관)
  → 2,000건을 네트워크 왕복 1회로 처리
```

```java
// admitToken 1000건 Pipeline SET
redisTemplate.executePipelined((RedisCallback<?>) conn -> {
    for (AdmitResult r : results) {
        byte[] tokenKey = ("admit-token-by-token:" + r.tokenId()).getBytes();
        byte[] admitKey = ("admit-token-by-admit:" + r.admitToken()).getBytes();

        conn.stringCommands().set(tokenKey, r.admitToken().getBytes(),
            Expiration.seconds(60), SetOption.UPSERT);
        conn.stringCommands().set(admitKey, r.tokenId().getBytes(),
            Expiration.seconds(60), SetOption.UPSERT);
    }
    return null;
});
// Virtual Thread에서 blocking 호출 → OS Thread 반납 → 문제 없음
```

**admit 1000건 전체 처리 시간 요약**

| 구간 | 처리 방식 | 소요 시간 |
|------|----------|----------|
| Redis ZREM 1000건 | 슬라이스 분할 Lua | ~수십 ms |
| DB UPDATE 1000건 | 100건 청크 × 10번 | ~100ms |
| admitToken SET 2000건 | Pipeline 1회 왕복 | ~수 ms |
| 합계 | | ~150ms 이내 |

```
admitToken TTL 60초 → 150ms 처리 → 충분한 여유
```

### 면접 포인트
> "admit 1000건에서 세 구간이 병목입니다.
> Redis ZREM은 슬라이스별로 분할해 Lua 1회당 처리 건수를 줄이고,
> DB UPDATE는 100건 청크로 나눠 Gap Lock을 방지합니다.
> admitToken 2,000건 SET은 RedisTemplate.executePipelined()로
> 네트워크 왕복을 1회로 줄입니다.
> Pipeline은 Lettuce 클라이언트 수준 기능이라
> Spring MVC에서도 ReactiveRedisTemplate 없이 동일하게 사용할 수 있습니다.
> 전체 처리 시간은 150ms 이내로 admitToken TTL 60초 안에 충분합니다."

---

## 20. 메모리 압박 해결

```
inactiveTtl 기본값: 300s (5분 무응답 = 사실상 이탈)
Batch 주기: 10초 (EXPIRED 토큰 메모리 점유 최소화)
Redis maxmemory: 4GB / maxmemory-policy: noeviction
```

---

## 21. 이탈(CANCELLED) 정책

```
이탈 허용 상태:
  WAITING      → CANCELLED ✅
  ADMIT_ISSUED → 409 QE_006_INVALID_STATUS ❌

ADMIT_ISSUED에서 이탈하려면:
  admitToken TTL 60초 대기
  → WAITING 자동 복귀
  → DELETE /tokens/:token → CANCELLED
```

---

## 22. verify / complete 분리

### 결정
```
verify  → 유효성 확인만 (상태 변경 없음, ADMIT_ISSUED 유지)
complete → Tenant가 입장 완료 후 명시적 통보 → COMPLETED + ZREM
```

### complete 처리 순서
```
① admitToken 유효성 재확인
② DB status = COMPLETED (먼저 — 원자성 전략)
③ Redis ZREM + DEL admit-token + DEL token-info (나중)
④ Kafka token-status-changed 발행

DB 먼저 이유:
  잔류(Redis에 남음) > 유실(DB 미반영)
  잔류 → Batch 10초 내 정리
  유실 → 복구 불가
```

### 면접 포인트
> "verify와 complete를 분리한 이유는
> Tenant가 입장 완료를 명시적으로 통보하게 함으로써
> ZREM 타이밍을 Tenant가 제어할 수 있도록 하기 위해서입니다.
> verify만으로 ZREM하면 입장 실패 시 복구가 불가능하지만
> complete 분리 시 admitToken이 유효한 동안 재시도가 가능합니다."

---

## 23. Redis Key 설계 이유

### 설계 원칙

```
1. 테넌트 격리: 모든 Key에 tenantId 포함
2. 외부 식별자: Long PK 대신 String ID (DB 구조 은닉)
3. TTL 기준:
   캐시 → 갱신 주기보다 약간 길게
   임시 토큰 → 사용 완료 예상 시간 + 여유
   활동 감지 → 비활동 허용 시간
   멱등성 → 재시도 예상 시간
4. 원자성: 중요 연산은 Lua Script 안에 포함
```

### Key별 설계 이유

| Key | 자료구조 | TTL | 선택 이유 |
|-----|----------|-----|----------|
| `queue:{t}:{q}:{slice}` | Sorted Set | 없음 | score로 FIFO 보장. ZCOUNT O(log N). 슬라이스 분산으로 경합 감소 |
| `global-seq:{t}:{q}` | String | 없음 | INCRBY 원자 연산. 슬라이스 간 전체 순번 채번. TTL 없음 = Queue 수명과 동일 |
| `queue-meta:{t}:{q}` | Hash | 없음 | 큐 설정 여러 필드를 Key 1개로 관리. HGET으로 필요 필드만 조회 |
| `queue-stats:{t}:{q}` | Hash | 없음 | HINCRBYFLOAT으로 float 누적. complete 시 직접 갱신. avgWaitingTime 실시간 계산 |
| `queue-user:{t}:{q}:{userId}` | String | waitingTtl | O(1) 중복 체크. TTL=waitingTtl로 대기 중 자동 보호. CANCELLED 시 즉시 DEL |
| `token-last-active:{tokenId}` | String | inactiveTtl | Key 존재 여부로 활동 감지. Polling마다 TTL 갱신. EXISTS=0이면 EXPIRED |
| `token-info:{tokenId}` | String | nextPollAfterSec+2s | Polling DB SELECT 대체. 상태 변경 시 즉시 갱신. 갱신 실패 시 DEL로 폴백 |
| `admit-token-by-token:{tokenId}` | String | 60s | Polling 응답에 admitToken 포함용. tokenId→admitToken 조회 |
| `admit-token-by-admit:{admitToken}` | String | 60s | verify/complete 시 admitToken→tokenId 조회 |
| `admit-idem:{requestId}` | String | 300s | admit 중복 요청 멱등성. NX로 최초 1회만 처리 |
| `verified-token:{tokenId}` | String | 60s | 중복 입장 방지. verify 후 admit 대상 제외. complete 시 DEL |
| `apikey-cache:{sha256}` | String | 60s | API Key 인증 DB 조회 대체. SHA-256 hash를 Key로 → rawKey 노출 방지 |
| `batch-lock:{t}:{q}` | String | 15s | Batch 서버 분산 시 큐별 처리 서버 지정. SET NX로 중복 처리 방지 |

> **제거된 Key**
> `queue-count:{t}:{q}` → ZCARD Pipeline으로 대체. 카운터 불일치 위험 제거
> `billing-count:{t}:{yyyyMM}` → tokens 원본 직접 집계로 대체. Redis 의존 제거

---

## 24. 실서비스 대용량 처리 문제 및 해결

### P0 — 서비스 중단 / 데이터 손실

#### ① admit 처리 장애 시 요청 유실

```
해결: Kafka At-Least-Once + DB PENDING 멱등성
  Consumer 재시작 → Offset 미커밋 메시지부터 재처리
  DB admit_requests PENDING 확인 → 중복 처리 방지
```

#### ② DB INSERT 비동기 유실

```
해결 (Kafka 도입 후):
  Enqueue → Kafka enqueue-events 발행
  TokenEnqueueConsumer → DB INSERT (At-Least-Once)
  Consumer 장애 → Offset 미커밋 → 재시작 시 재처리
  DB UNIQUE KEY → 중복 INSERT 자동 방어
```

### P1 — 유저 직접 피해

#### ③ complete 누락 시 중복 입장

```
해결: verified-token 플래그
  verify 시: SET verified-token:{tokenId} EX 60
  admit 시: verified 토큰 제외 + ZREM 정리
  complete 시: DEL verified-token
```

#### ④ 용량 초과 (maxCapacity 위반)

```
해결: ZCARD Pipeline + Lua 원자 체크
  Lua Script 안에서 ZCARD 합산 → 체크 → INCRBY
  → 동시 요청에도 maxCapacity 절대 초과 없음
```

#### ⑤ FIFO 순서 위반

```
해결: 추가 추출 시 전체 재정렬
  기존 선택분 + 추가분 합쳐서 seq 기준 정렬
  상위 N명만 선택 → 완벽한 FIFO 보장
```

### P2 — 운영 문제

#### ⑥ 과금 누락

```
해결: tokens 원본 직접 집계 (billing_events 불필요)
  BillingSnapshotJob (M+2월 초):
    SELECT COUNT(*) FROM tokens
    WHERE issued_at BETWEEN M월 AND M+1월
    GROUP BY tenant_id
    → billing_snapshots UPSERT (ON DUPLICATE KEY)
  tokens가 원본 → 항상 정확
  집계 시점에 원본 조회 → 중복 처리 개념 없음
```

#### ⑦ Batch 처리 지연

```
해결: Redis Lock 기반 Batch 서버 분산
  SET batch-lock:{t}:{q} {serverId} NX EX 15
  → 큐별 처리 서버 지정
  → Batch 서버 추가 시 선형 확장
```

#### ⑧ avgWaitingTime ETA 왜곡

```
해결: complete 시 직접 Redis 갱신 (StatsConsumer 불필요)
  waitingSeconds = completedAt - issuedAt
  이상치 필터: waitingSeconds > waitingTtl × 0.8 → 스킵
  HINCRBYFLOAT queue-stats:{t}:{q} waitingTimeSum {seconds}
  HINCRBY queue-stats:{t}:{q} waitingTimeCount 1

  Kafka 재처리 중복 반영 가능하나:
  ETA는 보조 정보 → 일시적 왜곡 허용 범위
  설계 단순화 효과가 더 큼
```

### P3 — 잠재적 위험

#### ⑨ Redis 메모리 단편화

```
해결:
  activedefrag yes (자동 정리)
  Prometheus redis_mem_fragmentation_ratio 수집
```

#### ⑩ Network Partition (Split Brain)

```
해결:
  Sentinel 쿼럼 = 2 (3대 중 2대 동의)
  min-replicas-to-write 1 (Slave 없으면 Master 쓰기 거부)
  Circuit Breaker → Redis 장애 시 503 반환
```

---

## 25. Spring MVC + Virtual Thread 전환 (WebFlux → MVC)

### 결정
```
기존: Spring WebFlux + Netty + R2DBC + ReactiveRedisTemplate + reactor-kafka
변경: Spring MVC + Tomcat + JPA + RedisTemplate + spring-kafka
     spring.threads.virtual.enabled=true
```

### 전환 이유

```
WebFlux + R2DBC 문제점:
  R2DBC 레퍼런스 부족 → JOIN 쿼리, 트랜잭션, 연관관계 매핑 어려움
  Reactor 체인(Mono/Flux) → 코드 복잡도 증가, 디버깅 어려움
  ReactiveTransactionManager 복잡도
  커뮤니티/문서 부족 → 개발 속도 저하

Spring MVC + Virtual Thread 장점:
  spring.threads.virtual.enabled=true 한 줄로 전환 완료
  JPA + @Transactional → 친숙한 패턴 그대로 사용
  일반 동기 코드 → 가독성/유지보수성 대폭 향상
  Java 21 VT: blocking I/O → OS Thread 점유 없이 대기
  Polling 2,000 rps → Virtual Thread 2,000개 동시 → 수십 MB → 허용 범위
```

### Virtual Thread 동작 원리

```
OS Thread (기존 Spring MVC):
  Thread 200개 → 200개 요청만 동시 처리
  JPA blocking → Thread 점유 → 다른 요청 대기

Virtual Thread (spring.threads.virtual.enabled=true):
  요청마다 새 Virtual Thread 생성
  JPA blocking → OS Thread 반납 → 다른 요청이 OS Thread 사용
  → 수천 개 동시 요청 처리 가능
```

### @Transactional + Virtual Thread

```
Java ThreadLocal 기반 @Transactional:
  Virtual Thread도 Thread의 일종
  → ThreadLocal 정상 동작
  → @Transactional 어노테이션 그대로 사용 가능

// 정상 패턴 (Spring MVC + VT)
@Service
public class TokenService {

    @Transactional
    public CompleteResult complete(String tokenId) {
        TokenEntity token = tokenRepository.findByTokenId(tokenId)
            .orElseThrow();
        token.complete(LocalDateTime.now()); // 도메인 메서드
        return CompleteResult.from(tokenRepository.save(token));
        // @Transactional이 VT에서 정상 동작
        // Redis 정리는 Service 반환 후 Controller에서 처리
    }
}
```

### WebFlux와의 코드 비교

```java
// ❌ 기존 WebFlux 패턴
public Mono<CompleteResult> complete(String tokenId) {
    return Mono.fromCallable(() ->
            tokenRepository.findByTokenId(tokenId).orElseThrow()
        )
        .subscribeOn(virtualThreadScheduler)  // 격리 필요
        .flatMap(token -> {
            token.complete(LocalDateTime.now());
            return Mono.fromCallable(() -> tokenRepository.save(token))
                .subscribeOn(virtualThreadScheduler);
        })
        .flatMap(saved ->
            redisPort.removeFromSortedSet(saved)  // Redis non-blocking
        )
        .map(CompleteResult::from);
}

// ✅ 변경 후 Spring MVC + VT 패턴
@Transactional
public CompleteResult complete(String tokenId) {
    TokenEntity token = tokenRepository.findByTokenId(tokenId)
        .orElseThrow();
    token.complete(LocalDateTime.now());
    tokenRepository.save(token);
    redisPort.removeFromSortedSet(token); // RedisTemplate — VT에서 blocking OK
    return CompleteResult.from(token);
}
```

### 성능 비교

```
Polling 2,000 rps 기준:
  WebFlux: Event Loop 소수 스레드 → non-blocking으로 처리
  MVC + VT: Virtual Thread 2,000개 동시 생성
            메모리: 2,000 × ~100KB ≈ 200MB → 허용 범위
            OS Thread 점유 없이 blocking 대기

성능 차이: 미미
MVC + VT 코드 단순성 획득이 더 가치 있음
```

### 기술 스택 변경 요약

```
Reactor Kafka    → spring-kafka (@KafkaListener)
ReactiveRedis    → RedisTemplate (Lettuce 동기 클라이언트)
R2DBC Repository → JPA Repository (JpaRepository)
Mono/Flux 반환   → 일반 반환 타입 (T, List<T>, Optional<T>)
subscribeOn()    → 불필요 (VT가 자동 처리)
```

### 면접 포인트
> "WebFlux 대신 Spring MVC + Virtual Thread를 선택한 이유는
> Java 21 Virtual Thread가 blocking I/O에서도
> OS Thread를 점유하지 않아 기존 Event Loop와 동일한 동시성을 달성하기 때문입니다.
> spring.threads.virtual.enabled=true 한 줄로 적용되고
> JPA + @Transactional을 그대로 사용할 수 있어
> 코드 복잡도가 크게 줄었습니다.
> R2DBC는 레퍼런스 부족과 Reactor 체인의 복잡도로 생산성이 낮았습니다."

---

## 26. DB 파티셔닝 전략

### 결정
```
샤딩: 미적용 (복잡도 급증)
파티셔닝: tokens 테이블에 Range 파티션 (issued_at 기준 월별)
```

### 파티셔닝 선택 이유
```
Range 파티션 (월별):
  파티션 DROP = 해당 월 토큰 전체 삭제
  → 일반 DELETE보다 수십~수백배 빠름 (락 없음)

Partition Pruning:
  TokenExpiryJob이 issued_at 조건으로 조회
  → 해당 월 파티션만 스캔 → I/O 대폭 감소
```

### MySQL 파티션 제약
```
파티션 키가 PK/Unique Key에 포함되어야 함
해결:
  PRIMARY KEY (id, issued_at)
  UNIQUE KEY uq_tokens_token_id (token_id, issued_at)
```

### 면접 포인트
> "샤딩은 복잡도가 급격히 올라가므로 적용하지 않았습니다.
> tokens 테이블에 issued_at 기준 월별 Range 파티션을 적용해
> 오래된 파티션을 DROP으로 빠르게 정리하고
> TokenExpiryJob이 Partition Pruning으로 해당 월만 스캔합니다.
> Polling SELECT는 Read Replica로 분산하고
> 인덱스는 최소화해 write 성능을 보호합니다."

---

## 27. 수평 확장 설계

### 핵심: Stateless 서버 설계
```
모든 상태를 Redis / DB에 저장
→ 서버 추가/제거 자유롭게 가능
→ 로드 밸런서 뒤에 N개 인스턴스 배치
```

### 스케줄러 중복 실행 방지
```
TokenExpiryJob이 여러 서버에서 동시 실행되면?
  → 같은 토큰을 중복 EXPIRED 처리

해결: Redis batch-lock:{t}:{q} NX EX 15
  → 큐별 처리 서버 지정
  → Batch 서버 추가 시 선형 확장
```

---

## 28. SDK 전략 — REST API + OpenAPI (Tenant) / JS SDK (브라우저)

### 초기 결정 (v1.9까지)
```
Java SDK + JS SDK 이중 제공
Java SDK: Tenant 서버가 verify 순서, complete 재시도를 자동으로
JS SDK:   브라우저 Polling, 탭 비활성화 자동 처리
```

### 변경 (v1.10) — Java SDK 제거
```
Tenant 서버 언어 다양성 (Java/Node/Python/Go/PHP/Ruby 등)
→ Java SDK만 제공하면 차별적 지원 문제
→ 언어별 SDK 전체 제공은 유지보수 비용 과다 (CVE 대응, 버전 관리)
→ OpenAPI 3.0 명세 + REST 직접 호출이 보편적 통합 방식

JS SDK는 유지:
  브라우저는 탭 비활성화/네트워크 offline 등 클라이언트 특화 문제
  Polling 빈도 관리(nextPollAfterSec 자동) → UX 핵심
```

### 최종 통합 방식

| 대상 | 방식 | 제공 자료 |
|------|------|----------|
| Tenant 서버 | REST API 직접 호출 | OpenAPI 3.0 명세 (Springdoc) + Workflow 문서 + Postman Collection |
| 브라우저 | JS SDK | npm + CDN 배포 (`queue-platform-sdk-js`) |

> 상세 ADR은 §45 참조 ("Java SDK 제거 — 언어 중립 REST 전략")

---

## 29. MySQL Read/Write 분리 설계

### 구조

```
Write (INSERT/UPDATE/DELETE) → Master
Read  (SELECT)               → Read Replica

@Transactional(readOnly = true) → Replica 자동 라우팅
@Transactional                  → Master 자동 라우팅
```

### Spring 설정

```yaml
spring:
  datasource:
    master:
      url: jdbc:mysql://master-host:3306/queue
      driver-class-name: com.mysql.cj.jdbc.Driver
    replica:
      url: jdbc:mysql://replica-host:3306/queue
      driver-class-name: com.mysql.cj.jdbc.Driver
  threads:
    virtual:
      enabled: true
```

```java
// ReplicationRoutingDataSource.java
public class ReplicationRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            ? "replica"
            : "master";
    }
}

// 사용 패턴
@Service
public class TokenReadService {

    @Transactional(readOnly = true)  // → Replica 자동 라우팅
    public Optional<TokenEntity> findByTokenId(String tokenId) {
        return tokenRepository.findByTokenId(tokenId);
    }
}

@Service
public class TokenWriteService {

    @Transactional  // → Master 자동 라우팅
    public TokenEntity complete(String tokenId) {
        TokenEntity token = tokenRepository.findByTokenId(tokenId).orElseThrow();
        token.complete(LocalDateTime.now());
        return tokenRepository.save(token);
    }
}
```

### 면접 포인트
> "Polling 2,000 rps를 Master에 집중시키면 쓰기 병목이 생깁니다.
> @Transactional(readOnly=true)는 Read Replica로
> @Transactional은 Master로 자동 라우팅되도록 설계했습니다.
> token-info Redis 캐시 TTL이 nextPollAfterSec+2s이므로
> 캐시 히트 시 Replica 조회 자체가 없어 lag 영향이 최소화됩니다."

---

## 30. Redis Master/Replica (Sentinel) 설계

### Redis는 Read/Write 분리 적용 안 함

```
MySQL과 다르게 Redis는 분리하지 않습니다.

이유:
  Redis 핵심 연산이 Lua Script (원자적)
  Lua Script는 Master에서만 실행 가능
  Redis는 In-Memory → 응답 속도 이미 충분히 빠름

Slave 용도: ① Failover 대기 ② 데이터 백업
```

### Spring 설정

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes:
          - sentinel1:26379
          - sentinel2:26379
          - sentinel3:26379
      password: ${REDIS_PASSWORD}
      lettuce:
        pool:
          max-active: 50
```

### MySQL vs Redis Read/Write 분리 비교

| 항목 | MySQL | Redis |
|------|-------|-------|
| Read/Write 분리 | ✅ 적용 | ❌ 미적용 |
| 이유 | SELECT 2,000 rps 분산 필요 | Lua Script 원자성 → Master 전용 |
| Replica 역할 | 읽기 부하 분산 | Failover 대기 + 백업 |

---

## 31. 대용량 Enqueue 시나리오 분석

### rps별 전략 요약

| rps | Redis ops/초 | 동시 VT | 판정 | 비고 |
|-----|-------------|---------|------|------|
| 200 | ~600 | ~2개 | ✅ 여유 | 설계 목표 |
| 2,000 | ~6,000 | ~20개 | ✅ 안정 | |
| 10,000 | ~20,000 | ~80개 | ✅ 가능 | HikariCP 조정 |
| 20,000 | ~40,000 | ~160개 | ⚠️ 위험 | Redis 40% |
| 30,000 | ~60,000 | ~240개 | ❌ 한계 | Redis Cluster 필요 |

> Bulk Worker 항상 활성 (결정 17번) → 모든 rps에서 동일 코드 경로

### 시나리오 — 10,000 Enqueue + 2,000 Polling + 3,000 업데이트 (15,000 rps)

**동시 VT 수**
```
Enqueue VT:  10,000 × 0.008초 = 80개  (Bulk Worker 대기 ~8ms)
Polling VT:   2,000 × 0.005초 = 10개  (Redis 캐시 ~5ms)
업데이트 VT:  3,000 × 0.050초 = 150개 (DB UPDATE ~50ms)
합산: ~240개 → 메모리 수십 MB → 문제 없음

핵심: "초당 10,000개 생성" ≠ "동시에 10,000개 존재"
      VT는 처리 완료 즉시 소멸 → 동시 존재 수는 처리 시간에 비례
```

**Redis ops**
```
Bulk Lua:           20번/초 (10,000 ÷ 500건 묶음)
Polling 순위 계산:  12,000 ops/초 (2,000 × 6 ops)
업데이트 ZREM/DEL:  15,000 ops/초 (3,000 × 5 ops)
API Key 캐시:       15,000 ops/초 (전체 요청)
합산: ~42,000 ops/초 → Redis 한계(100,000)의 42% ✅
```

**DB**
```
Master (쓰기):
  업데이트 UPDATE 3,000건/초, 처리 시간 ~10ms
  동시 커넥션 = 3,000 × 0.01 = 30개
  커넥션 풀 50개 → 충분 ✅

Replica (읽기):
  Polling token-info 캐시 히트율 ~90%
  실제 Replica 조회: 200건/초 → 부담 없음 ✅
```

**OS Thread**
```
동시 VT ~240개 중 실제 CPU 연산 중인 VT만 OS Thread 점유
VT의 Redis/DB/Kafka I/O 대기 중 → OS Thread 반납
→ Tomcat 기본 200개 스레드풀로 충분 ✅
```

**종합 판정**

| 레이어 | 부하 | 한계 | 판정 |
|--------|------|------|------|
| 동시 VT | ~240개 | 사실상 무제한 | ✅ |
| Redis ops | ~42,000/초 | 100,000/초 | ✅ 42% |
| DB Master 커넥션 | ~30개 | 50개 | ✅ |
| DB Replica 조회 | ~200건/초 | 수천/초 | ✅ |
| Kafka | ~13,000건/초 | 수십만/초 | ✅ |
| OS Thread | ~수십개 활성 | 200개 | ✅ |

**병목 순서: Redis > DB Master > OS Thread**

**필수 설정**

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50
      minimum-idle: 10
      connection-timeout: 3000
  threads:
    virtual:
      enabled: true
server:
  tomcat:
    threads:
      max: 200  # 기본값. CPU 집약적 작업 많으면 400으로 조정
```

### 면접 포인트
> "10,000 Enqueue + 2,000 Polling + 3,000 업데이트
> 총 15,000 rps 상황에서 동시 VT는 처리 시간 기준으로
> 약 240개 수준입니다.
> 초당 15,000개 생성되지만 처리 시간이 짧아
> 동시 존재 수는 그 비율만큼 줄어듭니다.
> VT는 I/O 대기 중 OS Thread를 반납하므로
> Tomcat 기본 200개 스레드풀로 충분합니다.
> Redis ops는 약 42,000 ops/초로 한계의 절반 이하이고
> 실질 병목은 Redis이며 30,000 rps 이상 시 Cluster를 검토합니다."

---

## 32. Kafka 도입 설계

### 도입 용도

```
① Enqueue 버퍼
   Redis Lua 즉시 처리 → 202 즉시 응답
   DB INSERT는 Kafka Consumer가 비동기 처리
   → Enqueue p99 50ms 이하 달성

② Token 상태 변경 이벤트
   COMPLETED / CANCELLED / EXPIRED 시 발행
   → BillingConsumer: tokens 원본 집계 → billing_snapshots UPSERT

avgWaitingTime은 Kafka 없이 complete API에서 직접 갱신:
   complete 시 HINCRBYFLOAT queue-stats:{t}:{q}
   → StatsConsumer 불필요 → 설계 단순화
```

### spring-kafka 설정

```java
// Producer
@Component
public class KafkaEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public void produceEnqueueEvent(EnqueueEvent event) {
        kafkaTemplate.send("enqueue-events", event.queueId(),
            objectMapper.writeValueAsString(event));
    }

    public void produceStatusChanged(StatusChangedEvent event) {
        kafkaTemplate.send("token-status-changed", event.queueId(),
            objectMapper.writeValueAsString(event));
    }
}

// EnqueueConsumer
@Component
public class TokenEnqueueConsumer {

    @KafkaListener(topics = "enqueue-events", groupId = "enqueue-consumer")
    public void consume(List<ConsumerRecord<String, String>> records,
                        Acknowledgment ack) {
        List<TokenEntity> tokens = records.stream()
            .map(r -> objectMapper.readValue(r.value(), EnqueueEvent.class))
            .map(TokenEntity::from)
            .toList();

        tokenRepository.saveAll(tokens); // JPA Bulk INSERT
        ack.acknowledge();               // 수동 커밋 → At-Least-Once
    }
}

// BillingConsumer: tokens 원본 집계 (billing_events 불필요)
@Component
public class BillingConsumer {

    @KafkaListener(topics = "token-status-changed", groupId = "billing-consumer")
    public void consume(StatusChangedEvent event) {
        if (event.status() != COMPLETED) return;

        String yearMonth = event.occurredAt().format(DateTimeFormatter.ofPattern("yyyyMM"));

        // tokens 원본에서 직접 집계 (중복 처리 개념 없음)
        long count = tokenRepository.countCompletedByTenantAndMonth(
            event.tenantId(), yearMonth);

        billingSnapshotRepository.upsert(event.tenantId(), yearMonth, count);
    }
}
```

### Consumer 구성 요약

| Consumer | 토픽 | 역할 |
|----------|------|------|
| `TokenEnqueueConsumer` | enqueue-events | DB Bulk INSERT + 수동 커밋 |
| `AdmitConsumer` | enqueue-admit | Lua Dequeue + admitToken 발급 |
| `BillingConsumer` | token-status-changed | tokens 집계 → billing_snapshots |

### 면접 포인트
> "Kafka를 두 가지 용도로 도입했습니다.
> 첫째, Enqueue 버퍼입니다.
> Redis Lua로 즉시 응답 후 DB INSERT는 Kafka Consumer가 처리합니다.
> 수동 커밋으로 At-Least-Once를 보장합니다.
>
> 둘째, 상태 변경 이벤트입니다.
> BillingConsumer가 tokens 원본을 직접 집계해
> billing_snapshots를 갱신합니다.
> tokens가 원본이므로 별도 중복 방지 테이블이 불필요합니다.
>
> avgWaitingTime은 Kafka Consumer 없이
> complete API에서 직접 Redis HINCRBYFLOAT으로 갱신합니다.
> ETA는 보조 정보이므로 Kafka 재처리 중복 허용이 가능합니다."

---

## 33. verify API 제거 검토 (v1.8) → v1.9에서 유지로 번복

### v1.9 결정: verify 유지

```
verify API 유지 이유:
  verify: 유저가 admitToken 들고 왔을 때 (Tenant가 호출)
  complete: 실제 입장 처리 완료 후 (Tenant가 호출)

  둘을 합치면:
  → verify 없이 바로 complete → 입장 실패 시 복구 불가

verify DB Fallback 추가 (v1.9):
  Redis admit-token-by-admit 미스 시
  DB admit_token 컬럼으로 안전하게 조회
  → Redis 장애 상황에서도 verify 정상 동작
```

---

## 34. admitToken TTL 만료 처리 (v1.8 EXPIRED → v1.9 WAITING 복귀)

### v1.9 최종 결정: WAITING 복귀 + seq 유지 + TTL 60초

```
WAITING 복귀 장점:
  seq DB 저장 → Redis ZADD score 복원 → 우선순위 보존
  다음 admit 호출 시 앞순서이면 재발급

TTL 30초 → 60초:
  30초: 여유 부족 → WAITING 복귀 빈번
  60초: 충분한 여유 → 정상 흐름에서 만료 거의 없음
```

---

## 35. SDK 설계 (v1.10 — JS SDK만 제공)

### Tenant 서버 통합 — REST API + OpenAPI

v1.9까지 있던 Java SDK는 제거되었다. Tenant 서버는 REST API를 직접 호출한다.

```
제공 자료:
  1. OpenAPI 3.0 명세 (Springdoc 자동 생성)
     - /v3/api-docs (JSON) · /swagger-ui.html
  2. Workflow 문서 (verify 순서, complete 재시도 가이드)
     - OpenAPI description에 명시 → Swagger UI로 시각화
  3. Postman Collection

Tenant가 직접 구현해야 하는 부분:
  - verify 호출 순서 (내부 처리 전에 먼저)
  - complete 재시도 (3회, backoff 100/500/1500ms)
  - 동시 verify 제한 (기본 100, per-key 100 rps 고려)
  - API Key 환경변수 관리

→ OpenAPI 명세의 description에 각 규칙을 Workflow로 명시
→ Swagger UI에서 시각적으로 유도
```

### JS SDK 핵심 기능

```
QueueSDK.init() + startPolling():
  nextPollAfterSec 타이밍 자동 적용 (setTimeout 관리)
  탭 비활성화 → Polling 중단 (배터리/서버 부하 절약)
  탭 복귀 → 즉시 재개
  네트워크 offline/online 자동 처리
```

| 컴포넌트 | 역할 |
|----------|------|
| `PollingManager` | nextPollAfterSec 자동 적용 / setTimeout 관리 |
| `StateManager` | IDLE → WAITING → READY → COMPLETED → EXPIRED |
| `VisibilityHandler` | visibilitychange 자동 감지 |
| `NetworkHandler` | offline/online 자동 처리 |

### 면접 포인트
> "Tenant 서버는 언어 제약이 없는 B2B 통합 대상이므로
> 단일 언어 SDK 대신 OpenAPI 명세 기반 REST 직접 호출 전략을 선택했습니다.
> verify 순서 강제와 complete 재시도 같은 규칙은 OpenAPI의 Workflow로 문서화하고
> Swagger UI로 시각화해 모든 언어의 Tenant가 동등하게 참고할 수 있도록 했습니다.
> 반면 브라우저는 탭 비활성화/네트워크 offline 같은 공통 문제가 명확해서
> JS SDK로 제공합니다. 이를 통해 SDK 유지보수 비용을 최소화하면서도
> 브라우저 UX는 손해 보지 않는 균형을 맞췄습니다."

---

## 36. admitToken TTL 만료 → WAITING 복귀 (상세)

### Redis Key 최종 구성

```
유지:
  admit-token-by-token:{tokenId}   → admitToken (Polling 응답용)
  admit-token-by-admit:{admitToken} → tokenId (verify/complete용)
  verified-token:{tokenId}          (중복 입장 방지)

DB:
  tokens.admit_token 컬럼
    → Redis 미스 시 Fallback용
    → verify DB Fallback 시 조회 기준
```

---

## 37. schema/entity 개선사항 (v1.9)

### status TINYINT 매핑

```
tokens 테이블 대용량 INSERT/SELECT 빈번
VARCHAR(20) vs TINYINT: 저장공간 20배 차이
인덱스 크기 감소 → 쿼리 성능 향상
TINYINT 비교 연산이 VARCHAR보다 빠름

Java 매핑:
  static final int 상수로 가독성 유지
  isWaiting(), isAdmitIssued() 헬퍼 메서드
```

### redis_sync_needed 컬럼

```
용도: Redis 다운 중 DB INSERT됐지만 Sorted Set 미반영 토큰 추적
값: 0 = Redis 반영완료, 1 = 미반영

흐름:
  정상 Enqueue: Kafka Consumer → INSERT 시 redis_sync_needed=0
  Redis 다운 중: INSERT 시 redis_sync_needed=1
  복구 배치(RedisSyncJob): redis_sync_needed=1 → Sorted Set 재삽입 → 0으로 초기화
```

### admit_token 컬럼

```
용도:
  1. Polling ADMIT_ISSUED 응답 시 admitToken 반환
     Redis admit-token-by-token 미스 시 DB Fallback
  2. verify DB Fallback 시 조회 기준
     (issued_at 60초 이내 + admit_token 일치 확인)

complete 후에도 컬럼 값 유지:
  불필요한 UPDATE 제거 → write 부하 감소
```

---

## 38. FLOW 개선사항 (v1.9)

### nextPollAfterSec 적응형 Polling

```
globalRank > 500 → 30s (서버 부하 절약)
globalRank > 100 → 10s
globalRank > 10  → 5s
globalRank ≤ 10  → 2s (곧 입장)

token-info 캐시 TTL: nextPollAfterSec + 2s
```

### ZCARD Pipeline (queue-count 제거)

```
기존: queue-count Redis Key (원자 카운터)
변경: ZCARD Pipeline으로 현재 인원 조회

이유:
  queue-count 카운터 불일치 위험 (CANCELLED/EXPIRED 시 DECR 누락 가능)
  ZCARD는 Sorted Set의 실제 크기 → 항상 정확

Pipeline:
  ZCARD slice:0, ZCARD slice:1, ZCARD slice:2
  → 한번의 네트워크 왕복으로 합산
```

### verify DB Fallback

```
Redis admit-token-by-admit 미스 시:
  DB SELECT WHERE status=ADMIT_ISSUED
               AND admit_token=?
               AND issued_at > NOW()-60s

이유:
  Redis 장애 또는 TTL 경계에서 캐시 미스 가능
  DB admit_token 컬럼으로 안전하게 fallback
```

---

## 39. RedisSyncJob 상세 흐름

### 역할
Redis 다운 중 Kafka Consumer가 DB INSERT는 완료했지만
Redis ZADD는 못 한 토큰(redis_sync_needed=1)을 복구한다.

### 처리 흐름

```
① DB SELECT
   WHERE redis_sync_needed = 1
   AND status = WAITING
   100건씩 청크 처리 (Replica 조회)

② 슬라이스 계산 + Redis ZADD
   slice = (seq - 1) % sliceCount
   ZADD queue:{t}:{q}:{slice} {seq} {tokenId} NX
   NX: 이미 있으면 무시 (멱등)

③ queue-user 역인덱스 재구성
   SET queue-user:{t}:{q}:{userId} {tokenId} EX waitingTtl
   → 중복 Enqueue 방지 복원

④ DB UPDATE
   SET redis_sync_needed = 0
   WHERE token_id IN (처리 완료 목록)
   (Master 쓰기)

⑤ 실패 시
   redis_sync_needed = 1 유지
   → 다음 5분 주기에 자동 재처리
   ZADD NX → 중복 삽입 없음 (멱등)
```

### 코드

```java
@Component
public class RedisSyncJob {

    @Scheduled(fixedDelay = 300_000) // 5분 주기
    @Transactional(readOnly = true)
    public void sync() {
        // ① redis_sync_needed=1 토큰 조회 (100건 청크)
        List<TokenEntity> tokens = tokenRepository
            .findByRedisSyncNeeded(1, PageRequest.of(0, 100));

        if (tokens.isEmpty()) return;

        for (TokenEntity token : tokens) {
            try {
                // ② Redis ZADD (NX: 멱등)
                int slice = (int) ((token.getSeq() - 1) % token.getSliceCount());
                String key = RedisKeyFactory.queue(token.getTenantId(),
                                                   token.getQueueId(), slice);
                redisTemplate.opsForZSet()
                    .addIfAbsent(key, token.getTokenId(), token.getSeq());

                // ③ queue-user 역인덱스 재구성
                String userKey = RedisKeyFactory.queueUser(
                    token.getTenantId(), token.getQueueId(), token.getUserId());
                redisTemplate.opsForValue()
                    .set(userKey, token.getTokenId(),
                         Duration.ofSeconds(token.getWaitingTtl()));

            } catch (Exception e) {
                log.error("Redis sync failed: {}", token.getTokenId(), e);
                // 실패 시 redis_sync_needed=1 유지 → 다음 주기 재처리
                continue;
            }
        }

        // ④ 성공한 토큰 DB UPDATE
        List<String> succeeded = tokens.stream()
            .map(TokenEntity::getTokenId).toList();
        tokenRepository.bulkUpdateRedisSyncNeeded(succeeded, 0);
    }
}
```

### 면접 포인트
> "Redis 다운 중 DB에만 저장된 토큰은 redis_sync_needed=1로 표시합니다.
> 5분마다 RedisSyncJob이 이 토큰들을 Redis Sorted Set에 재삽입합니다.
> ZADD NX로 멱등성을 보장하고
> 실패 시 redis_sync_needed=1을 유지해 다음 주기에 자동 재처리됩니다."

---

## 40. Kafka Consumer 설정 상세

### Consumer 설정

```yaml
spring:
  kafka:
    consumer:
      max-poll-records: 1000       # 한 번에 최대 1000건 수신
      auto-offset-reset: earliest
      enable-auto-commit: false    # 수동 커밋 → At-Least-Once 보장
    listener:
      type: batch                  # 배치 수신 모드
      ack-mode: MANUAL_IMMEDIATE   # 처리 완료 후 명시적 커밋
```

### 수동 커밋이 핵심인 이유

```
자동 커밋(enable-auto-commit=true):
  일정 주기마다 자동으로 Offset 커밋
  DB INSERT 완료 전에 커밋 가능
  → 서버 장애 시 INSERT 안 됐는데 Offset은 넘어감 → 유실

수동 커밋(MANUAL_IMMEDIATE):
  saveAll() 완료 후 ack.acknowledge() 명시적 호출
  → INSERT 완료 확인 후 커밋
  → 장애 시 Offset 미커밋 → 재시작 시 재처리 → At-Least-Once 보장
```

### Consumer 구현

```java
@Component
public class TokenEnqueueConsumer {

    @KafkaListener(
        topics = "enqueue-events",
        groupId = "enqueue-consumer",
        containerFactory = "batchKafkaListenerContainerFactory"
    )
    public void consume(List<ConsumerRecord<String, String>> records,
                        Acknowledgment ack) {
        List<TokenEntity> tokens = records.stream()
            .map(r -> objectMapper.readValue(r.value(), EnqueueEvent.class))
            .map(TokenEntity::from)
            .toList();

        tokenRepository.saveAll(tokens); // JPA Bulk INSERT
        ack.acknowledge();               // 완료 후 수동 커밋
    }
}

// 배치 컨테이너 팩토리 설정
@Bean
public ConcurrentKafkaListenerContainerFactory<String, String>
        batchKafkaListenerContainerFactory(ConsumerFactory<String, String> cf) {

    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(cf);
    factory.setBatchListener(true);
    factory.getContainerProperties().setAckMode(
        ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    return factory;
}
```

### Consumer Group 설계

```
enqueue-events 토픽:
  파티션 수: 12개 (queueId 해시 기준)
  Consumer Group: enqueue-consumer
  → Consumer 추가 시 파티션 리밸런싱 → 선형 확장

enqueue-admit 토픽:
  파티션 수: 12개
  Consumer Group: admit-consumer
  → 같은 큐의 admit → 같은 파티션 → 같은 Consumer → 순서 보장

token-status-changed 토픽:
  Consumer Group: billing-consumer, stats-consumer
  → 동일 토픽을 두 Consumer Group이 독립적으로 소비
```

### 면접 포인트
> "enable-auto-commit=false + MANUAL_IMMEDIATE로
> DB INSERT 완료 후에만 Offset을 커밋합니다.
> 서버 장애 시 Offset이 미커밋 상태로 남아
> 재시작 시 미처리 메시지부터 재처리됩니다.
> DB UNIQUE KEY가 중복 INSERT를 자동 방어해
> At-Least-Once를 안전하게 보장합니다."

---

## 41. HikariCP 커넥션 풀 계산 근거

### 계산

```
15,000 rps 상황 (Enqueue 10,000 + Polling 2,000 + 업데이트 3,000)

실제 DB 접근 비율:
  Enqueue:  Redis Lua + Kafka produce → DB 접근 안 함
  Polling:  token-info 캐시 히트율 ~90% → DB 접근 ~10%
  업데이트: 모두 DB UPDATE 필요 → 100%

실제 동시 DB 접근:
  Polling:   2,000 × 0.1 = 200건/초
  업데이트:  3,000 × 1.0 = 3,000건/초
  합산:      ~3,200건/초

동시 커넥션 수 = 3,200 × 처리 시간(~10ms)
              = 3,200 × 0.01 = 32개

최종: maximum-pool-size: 50 (32개 + 여유 18개)
```

### OS Thread 기반과 VT의 차이

```
OS Thread 기반이었다면:
  3,200건/초 × 처리 시간 동안 Thread 점유
  → Thread 3,200개 필요 → 불가능

Virtual Thread:
  DB 대기 중 OS Thread 반납
  → 커넥션 50개로 3,200건/초 처리 가능
  → VT의 실질적 장점

connection-timeout: 3,000ms
  VT는 대기 중 OS Thread 반납하므로
  커넥션 대기 시간이 길어도 서버 전체에 영향 없음
```

### 설정

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50
      minimum-idle: 10
      connection-timeout: 3000    # 커넥션 대기 최대 3초
      idle-timeout: 600000        # 유휴 커넥션 10분 후 반납
      max-lifetime: 1800000       # 커넥션 최대 수명 30분
```

### 면접 포인트
> "OS Thread 기반이라면 동시 DB 접근 수만큼 Thread가 필요하지만
> Virtual Thread는 DB 대기 중 OS Thread를 반납합니다.
> 실제 동시 DB 접근이 약 32개이므로
> 커넥션 풀 50개면 충분하고
> VT가 대기 중 OS Thread를 반납하므로
> connection-timeout 3초 대기도 서버 전체에 영향이 없습니다."

---

## 42. JWT 설계

### 인증 주체별 분리

```
JWT      → 관리 API (Queue CRUD, API Key 발급/Revoke)
           대상: Tenant 개발자
X-API-Key → 서비스 API (Enqueue, admit, verify, complete)
           대상: Tenant 서버

분리 이유:
  관리 API: 사람이 호출 → 단기 토큰 + 갱신이 적합
  서비스 API: 서버 간 통신 → 장기 자격증명이 적합
```

### 토큰 설계

```
Access Token:  15분 (짧게 → 탈취 피해 최소화)
Refresh Token: 7일

Access Token 페이로드:
{
  "sub": "t_abc123",   // tenantId (외부 식별자)
  "email": "...",
  "jti": "uuid-v4",   // 블랙리스트 대비 고유 ID
  "iat": 1234567890,
  "exp": 1234568790   // 15분
}

주의:
  passwordHash 등 민감 정보 절대 미포함
  서명 알고리즘: HS256 (포트폴리오)
  실무에서는 RS256 권장 (공개키로 검증 → 서비스 간 공유 가능)
```

### Refresh Token 저장 전략

```
Redis + DB 이중 저장

Redis (빠른 검증):
  Key: refresh:{tenantId}
  Value: { token, version }
  TTL: 7일
  → 매 요청마다 빠른 검증 (O(1))

DB refresh_tokens 테이블 (영속성):
  Redis 전체 장애 → DB에서 복구
  Revoke 이력 관리
  Redis만 저장 시 장애 → 모든 Tenant 강제 로그아웃 → B2B에서 치명적

Redis 우선, DB는 폴백:
  검증: Redis 조회 → 없으면 DB 조회 후 Redis 재구성
```

### Refresh Token Rotation + 재사용 감지

```
Rotation만으로는 부족:
  공격자가 먼저 재발급 → 정상 유저 토큰 무효화
  정상 유저 재로그인 → 공격자 감지 불가

버전 관리로 재사용 감지:
  Redis: refresh:{tenantId} = { token: "xxx", version: 1 }

재발급 요청 시:
  version 일치 → 정상
    → version: 2로 갱신
    → 새 Access + Refresh Token 발급

  version 불일치 → 이미 사용된 토큰으로 재시도 = 탈취 감지
    → 해당 Tenant 전체 세션 강제 만료 (Redis DEL)
    → DB 이력 기록
    → 보안 알림 (이메일 등)
```

### 블랙리스트 (설계만, 구현 생략)

```
Access Token 즉시 무효화가 필요한 경우:
  SET jwt-blacklist:{jti} "1" EX 900 (Access Token 잔여 TTL)
  매 요청마다 블랙리스트 확인 (Redis O(1))

포트폴리오 수준에서는 구현 생략:
  15분 Access Token → 탈취 피해 제한적
  "jti를 페이로드에 포함해 필요 시 블랙리스트로 즉시 무효화 가능"
  → 설계만 언급
```

### API Key 탈취 방어

```
탈취된 JWT로 새 API Key 발급 가능
→ JWT 만료 후에도 API Key는 살아있음

대응:
  API Key 발급 시 비밀번호 재확인 (추가 인증)
  API Key 발급 이력 로깅 (tenant_id, created_at, ip)
  짧은 시간 내 다수 발급 → 이상 패턴 감지
```

### 면접 포인트
> "JWT는 관리 API용, X-API-Key는 서비스 API용으로 역할을 분리했습니다.
> Refresh Token은 Redis와 DB에 이중 저장해
> Redis 장애 시에도 세션이 유지됩니다.
> Rotation에 버전 관리를 추가해
> 이미 사용된 Refresh Token으로 재발급 시도 시
> 탈취로 감지하고 해당 Tenant의 모든 세션을 강제 만료합니다."

---

## 43. Queue 삭제 흐름 (DRAINING → DELETED)

### 삭제 요청

```
DELETE /queues/:queueId

① DB status = DRAINING
② 신규 Enqueue → 503 차단
③ 기존 대기자 유지 (자연 소멸 대기)

DRAINING 중 admit → 허용
  이유: DRAINING 목적 = 잔여 대기자 빠른 정리
       admit 막으면 최대 waitingTtl(7200초 = 2시간) 대기
       → Tenant 입장에서 불합리
```

### DRAINING → DELETED 전환 (TokenExpiryJob 10초 주기)

```
① 잔여 토큰 수 확인
   ZCARD 슬라이스 합산 → 0이면 DELETED 전환 시작
   batch-lock:{t}:{q} NX EX 15 → 분산 환경 중복 방지

② DB status = DELETED, deletedAt 기록 (먼저)
   원자성 전략: DB 먼저 → Redis 나중
   (잔류가 유실보다 안전)

③ Redis Key 일괄 DEL (TTL 없는 Key 명시적 정리)
   queue:{t}:{q}:0 ~ queue:{t}:{q}:{sliceCount-1}
   global-seq:{t}:{q}
   queue-meta:{t}:{q}
   queue-stats:{t}:{q}

④ Redis DEL 실패 시
   DB DELETED 상태 유지
   ConsistencyChecker (1시간 주기) 가 DELETED 큐의
   잔존 Redis Key 감지 → DEL (기존 역할 확장)
```

### AdmitConsumer Queue 상태 체크

```
현재 누락된 처리:
  Queue가 DELETED됐는데 AdmitConsumer가 admitToken 발급 시도

AdmitConsumer 처리 전 Queue 상태 확인:
  DELETED → admit_requests status = CANCELLED
           → Kafka token-status-changed 발행 없음
           → 처리 종료

DRAINING → admit 허용 (잔여 대기자 빠른 정리)
DELETED  → admit 차단
```

```java
@KafkaListener(topics = "enqueue-admit", groupId = "admit-consumer")
public void processAdmit(String requestId) {
    AdmitRequest req = admitRequestRepository
        .findByRequestId(requestId).orElseThrow();

    if (req.getStatus() != PENDING) return; // 멱등 체크

    // Queue 상태 확인 (추가)
    QueueEntity queue = queueRepository.findByQueueId(req.getQueueId())
        .orElseThrow();

    if (queue.getStatus() == DELETED) {
        admitRequestRepository.updateStatus(requestId, CANCELLED);
        return; // DELETED → admit 중단
    }
    // DRAINING → admit 허용 (통과)

    admitService.process(req);
    admitRequestRepository.updateStatus(requestId, COMPLETED);
}
```

### DRAINING 최대 대기 시간

```
잔여 대기자가 있는 경우:
  waitingTtl = 7200초 (2시간) → 최대 2시간 후 DELETED
  inactiveTtl = 300초 (5분) → 비활동 유저는 5분 내 EXPIRED

admit 허용으로 빠른 소진:
  Tenant가 admit 호출 → 잔여 대기자 빠르게 처리
  → DELETED 전환 시간 단축
```

### 면접 포인트
> "Queue 삭제 시 DRAINING 상태로 전환해 신규 Enqueue를 차단하고
> 기존 대기자는 자연 소멸을 기다립니다.
> DRAINING 중 admit은 잔여 대기자를 빠르게 처리하기 위해 허용합니다.
> ZCARD가 0이 되면 DELETED로 전환하고 Redis Key를 일괄 삭제합니다.
> AdmitConsumer는 처리 전 Queue 상태를 확인해
> DELETED 큐에 admitToken이 발급되지 않도록 합니다.
> Redis DEL 실패 시 ConsistencyChecker가 주기적으로 잔존 Key를 정리합니다."

---

## 44. 파티션 유예 전략 (월말 걸친 토큰 보호)

### 문제

```
Queue가 월말에 걸쳐 운영되는 경우:
  4/30 23:50 Enqueue → issued_at = 4월 → p2026_04 파티션
  5/1  00:10 complete

5/1 00:00 BillingSnapshotJob 실행:
  4월 파티션 집계 시 해당 토큰 = 아직 WAITING → 미포함
  p2026_04 DROP → 토큰 소멸
  5/1 complete → 이미 파티션 없음 → 과금 누락

파티션 키가 issued_at이므로:
  completedAt 기준 집계 → Partition Pruning 불가 → 전체 풀스캔
  issued_at 기준 집계 → Pruning 가능하지만 월말 걸침 문제 발생
```

### 결정: 1달 유예 (M월 파티션은 M+2월 초 DROP)

```
4월 파티션(p2026_04):
  5/1  집계 시도 → 일부 토큰 아직 WAITING → 집계만 (DROP 보류)
  6/1  재집계 → 4월 issued_at 토큰 전부 complete/expired → 집계 완료
  6/1  p2026_04 DROP

항상 파티션 2달치 유지:
  현재 달 + 이전 달
  스토리지 약 2배 증가
  → B2B 과금 정확도 > 스토리지 비용
```

### BillingSnapshotJob 흐름 (M+2월 초 실행)

```
Step 1: queue_daily_stats 집계
  SELECT issued_at 기준 M월 데이터
  GROUP BY tenant_id, queue_id, DATE(issued_at)
  → Partition Pruning으로 M월 파티션만 스캔
  ON DUPLICATE KEY UPDATE id = id (멱등)

Step 2: billing_snapshots 집계
  SELECT COUNT(*) FROM tokens
  WHERE issued_at BETWEEN M월 AND M+1월
  GROUP BY tenant_id
  → tokens 원본 직접 집계 (billing_events 불필요)
  ON DUPLICATE KEY UPDATE count = VALUES(count)

Step 3: p2026_M DROP
  Step 1, 2 완료 확인 후 실행
  수 밀리초 → 일반 DELETE 대비 수백배 빠름

Step 4: 다음 파티션 사전 생성
  REORGANIZE PARTITION p_future INTO (새 파티션, p_future)
```

### queue_daily_stats와 tokens의 관계

```
논리적 관계: tokens → (집계) → queue_daily_stats
물리적 FK:  없음 (의도적 설계)

FK 없는 이유:
  tokens 파티션 DROP 시 FK 무결성 위반 → DROP 불가
  queue_daily_stats의 목적 자체가 tokens 소멸 후 보존이므로
  외부 의존 최소화

queues → queue_daily_stats FK도 미적용:
  Queue 삭제(DELETED) 후에도 과금 근거 영구 보존 필요
  queues row는 남지만 의존 최소화 원칙 유지
```

### 트레이드오프

| 항목 | 내용 |
|------|------|
| 파티션 보유 수 | 항상 2달치 (현재 달 + 이전 달) |
| 스토리지 | 약 2배 증가 |
| 과금 정확도 | 월말 걸친 토큰도 누락 없음 |
| DROP 타이밍 | M+2월 초 (1달 유예) |
| 집계 방식 | issued_at 기준 Partition Pruning 활용 |

### 면접 포인트
> "Queue가 월말에 걸쳐 운영되면
> issued_at 기준 파티션에서 complete가 다음 달에 발생할 수 있습니다.
> 당월 말 바로 DROP하면 아직 WAITING인 토큰이 소멸되어
> 과금 누락이 발생합니다.
> 1달 유예를 적용해 M월 파티션을 M+2월 초에 DROP함으로써
> 월말 걸친 토큰이 complete될 때까지 보존합니다.
> 스토리지는 약 2배지만 B2B 과금 정확도가 더 중요합니다."

---

## 45. Java SDK 제거 — 언어 중립 REST 전략 (v1.10)

### 배경

v1.9까지 Queue Platform은 Tenant 서버용 **Java SDK**와 브라우저용 **JS SDK** 두 종류를 제공할 계획이었다. 그러나 Tenant 서버는 **언어 제약이 없는 B2B 통합 대상**이다 — 고객사가 어떤 언어로 서버를 운영할지 Platform이 통제할 수 없다.

```
Tenant 서버 스택은 본질적으로 언어 다양성을 전제
→ 단일 언어 SDK만 제공하면 해당 언어 외 Tenant는 차별적 지원
→ 결국 상당수 Tenant가 REST 직접 호출 방식으로 통합
→ 단일 언어 SDK의 존재 의의 약화
```

### 고려한 대안

| 대안 | 장점 | 단점 | 채택 여부 |
|------|------|------|----------|
| **A. 모든 언어 SDK 제공** | 완벽한 지원 | 언어별 CVE 대응·버전 관리 비용 선형 증가, 1인 포트폴리오 스코프 초과 | ❌ |
| **B. Java SDK만 제공** | Java Tenant 편의 | 다른 언어 차별 → 공평성 훼손 | ❌ |
| **C. REST + OpenAPI만 제공** | 언어 중립·유지보수 비용 최소 | Tenant가 verify 순서/재시도 직접 구현 | ✅ |
| **D. gRPC** | 언어 중립 + 스키마 | 브라우저 지원 애매·도입 비용 | ❌ |

### 결정: 대안 C (REST + OpenAPI)

```
Tenant 서버 통합 방식:
  1. OpenAPI 3.0 명세 (Springdoc 자동 생성)
     - /v3/api-docs (JSON)
     - /swagger-ui.html (인터랙티브 테스트)
  2. Workflow 문서 (verify 순서, complete 재시도, 동시성 가이드)
     - OpenAPI description + Swagger UI에 명시
  3. Postman Collection

JS SDK는 유지:
  브라우저는 탭 비활성화/네트워크 offline이 공통 문제
  Polling 빈도 자동 관리가 UX 핵심
  한 언어만 유지보수 → 비용 감당 가능
```

### 트레이드오프

| 항목 | Java SDK 있을 때 | REST + OpenAPI |
|------|-----------------|----------------|
| Tenant 진입 장벽 | Java만 낮음 | 모든 언어 균등하게 낮음 |
| verify 순서 강제 | SDK 코드 레벨 | 문서(OpenAPI description) 레벨 |
| complete 재시도 | SDK 자동 | Tenant 직접 구현 |
| 버그/CVE 대응 | SDK 배포 필요 | Platform만 수정 → 자동 반영 |
| Platform 정책 변경 시 | SDK 업데이트 배포 | 명세/문서 업데이트 |
| 유지보수 비용 | 고 (언어 종속) | 저 (OpenAPI는 Springdoc 자동 생성) |

### Tenant 부담 경감 방안

Java SDK가 자동 처리하던 책임을 Tenant가 구현하되, **명확한 가이드로 부담을 최소화**한다.

```
A. verify 순서 강제
   OpenAPI spec의 description에 "Workflow: verify → process → complete" 명시
   Swagger UI에서 시각적으로 확인 가능

B. complete 재시도
   README/Workflow 문서에 "3회 retry, backoff 100/500/1500ms" 권장 패턴 명시
   재시도 금지 조건(404/409) 문서화

C. 동시 verify 수 가이드
   계산 공식 문서화: concurrency = admit_count × verify_ms / (ttl_ms × 0.5)
   "admit 1,000 기준 동시 100 권장" 구체 수치 제시

D. API Key 보안
   "환경변수 저장, 하드코딩 금지, HTTPS 필수" 체크리스트
```

### 면접 답변

> "초기엔 Java SDK를 제공해 verify 순서를 SDK 레벨에서 강제하려 했습니다.
> 하지만 Tenant 서버는 언어 제약이 없는 B2B 통합 대상이고
> Platform이 Tenant의 언어 선택을 통제할 수 없는 구조입니다.
> 단일 언어 SDK만 제공하면 해당 언어 외 Tenant는 차별적 지원이 되고
> 언어 전체 SDK는 1인 프로젝트 유지보수 한계를 넘어서므로
> OpenAPI 3.0 명세와 Workflow 문서로
> 모든 언어의 Tenant가 균등하게 통합할 수 있도록 전략을 변경했습니다.
> verify 순서 같은 규칙은 OpenAPI의 Workflow description에 명시하고
> Swagger UI로 시각화해 문서 레벨에서 유도합니다.
> 반면 브라우저는 탭 비활성화/네트워크 offline이 공통 문제이므로
> JS SDK는 유지해 UX를 보장했습니다."

### 추후 확장 가능성

```
수요가 검증되면 언어별 오픈소스 SDK를 커뮤니티 프로젝트로 분리:
  queue-platform-sdk-java, -python, -go 등
  Platform 본체와 분리 → 유지보수 책임 분산
  OpenAPI spec 기반 자동 생성(openapi-generator) 활용 가능
```
