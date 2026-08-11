# Queue Platform — 설계 결정 문서

> FRS v1.9 기준 | Entity 설계 / 보안 / 복구 전략 / 아키텍처

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
  ErrorCode, BusinessException, IdGenerator 등 어느 모듈에서나 직접 사용
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

## 28. SDK 제공 계획

### SDK가 필요한 이유
```
Tenant가 직접 구현해야 하는 것들:
  HTTP 클라이언트 설정
  X-API-Key SHA-256 해싱
  재시도 로직 (verify 순서 강제, complete 재시도)
  nextPollAfterSec 타이밍 관리, 탭 비활성화 처리

→ Tenant마다 직접 구현 → 실수 가능성 높음
→ Platform 정책 변경 시 모든 Tenant가 수정
→ SDK가 정책을 코드 레벨에서 강제
```

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

## 35. SDK 설계

### Java SDK 핵심 기능

```
QueueClient.admitAndVerify(queueId, count):
  verify를 내부 처리 전에 먼저 호출 → SDK가 순서 강제
  BulkVerifier: admitToken N개 동시 최대 100개 병렬 처리
  onSuccess: verify 완료 후 Tenant 내부 처리 콜백
  complete 자동 호출 (3회 backoff 재시도)

QueueClient.complete(token, admitToken):
  3회 자동 재시도 (100ms → 500ms → 1500ms backoff)
  admitToken TTL(60초) 내 완료 보장 설계
```

### JS SDK 핵심 기능

```
QueueSDK.init() + startPolling():
  nextPollAfterSec 타이밍 자동 적용 (setTimeout 관리)
  탭 비활성화 → Polling 중단 (배터리/서버 부하 절약)
  탭 복귀 → 즉시 재개
  네트워크 offline/online 자동 처리
```

### 면접 포인트
> "Java SDK의 admitAndVerify()가 verify 호출 순서를 코드 레벨에서 강제합니다.
> verify를 내부 처리 전에 먼저 호출하지 않으면 TTL 초과 위험이 있는데
> SDK가 이 순서를 보장합니다.
> JS SDK는 nextPollAfterSec 타이밍을 자동 적용하고
> 탭 비활성화 시 Polling을 자동 중단해 서버 부하를 줄입니다."

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

## 45. Sprint 1 — Gradle 멀티모듈 + Virtual Thread 전략

### 결정

| 항목 | 결정 |
|------|------|
| 모듈 구조 | 5개 (queue-api, queue-batch, queue-common, queue-domain, queue-infrastructure) |
| 실행 방식 | Spring MVC + Tomcat + Virtual Thread (WebFlux 대신) |
| 인프라 비활성화 | autoconfigure.exclude로 DataSource/Redis/Kafka 단계적 활성화 |

### 근거
- 헥사고날 아키텍처 물리적 분리: domain은 순수 Java, infrastructure는 Spring 의존
- autoconfigure.exclude: Sprint별 인프라 도입 시점에 해당 라인만 제거 → 빌드 에러 방지
- Java SDK 제거 → REST + OpenAPI 전략 전환 (Tenant 언어가 다양해 SDK 커스터마이징 비현실적)

---

## 46. Sprint 2 — LazyConnectionDataSourceProxy 필수 적용

### 문제
Spring의 기본 동작: @Transactional 시작 → getConnection() → readOnly 설정 순서.
커넥션 획득 시점에 readOnly 여부를 아직 모르므로 determineCurrentLookupKey()가 항상 "master" 반환.

### 결정: LazyConnectionDataSourceProxy 적용
커넥션 획득을 실제 SQL 실행 시점까지 지연 → readOnly 판단 후 올바른 DataSource 선택.

```
Bean 구성: masterDS → replicaDS → routingDS → LazyProxy (@Primary)
```

### 근거
- LazyProxy 없이는 R/W 분리가 동작하지 않음 (실증 확인)
- DataSourceAutoConfiguration exclude 유지 (커스텀 DataSource 4개 직접 생성)
- HibernateJpaAutoConfiguration 활성화 (@Primary DataSource를 자동 사용)

---

## 47. Sprint 2 — JpaConfig를 infrastructure 모듈에 배치

### 결정
@EnableJpaRepositories + @EntityScan을 queue-infrastructure의 JpaConfig.java에 배치.

### 근거
- queue-api에 spring-boot-starter-data-jpa 의존성 추가 불필요 (헥사고날 원칙)
- JPA는 infrastructure의 관심사
- scanBasePackages="com.sonix.queue"가 infrastructure의 JpaConfig를 자동 스캔

---

## 48. Sprint 2 — schema.sql 수동 관리 (ddl-auto 미동작 대응)

### 문제
LazyConnectionDataSourceProxy 환경에서 Hibernate ddl-auto=update가 테이블을 생성하지 않음.
커넥션 획득이 지연되어 DDL 실행이 스킵되는 알려진 이슈.

### 결정: schema.sql 수동 실행
- ddl-auto=update 유지하되, 실제 테이블 생성은 schema.sql로 수동 관리
- Sprint 4 이후 스키마 안정화 시 Flyway 도입 예정

---

## 49. Sprint 3 — Adapter 네이밍 xxxRepositoryImpl → xxxJpaAdapter

### 결정
TenantRepositoryImpl → TenantJpaAdapter로 네이밍 변경.

### 근거
- "Impl"은 "단순 구현체"처럼 보여 헥사고날의 Adapter 역할이 안 느껴짐
- "JpaAdapter"는 "JPA를 사용하는 어댑터"라는 역할이 명확
- 인프라 교체 시 네이밍이 자연스러움: TenantJpaAdapter → TenantMyBatisAdapter → TenantMongoAdapter

---

## 50. Sprint 3 — Tenant status 확장 (FRS에 없는 필드)

### 결정
FRS의 tenants 테이블에 status 컬럼이 없지만, ACTIVE(0)/DEACTIVATED(1) 상태를 추가.

### 근거
- 실무에서 Tenant 비활성화 없는 SaaS는 거의 없음 (계정 정지/탈퇴 처리)
- schema.sql에 `status TINYINT NOT NULL DEFAULT 0` 추가
- 면접에서 "FRS에 없던 건데 왜 추가했나?" → "계정 관리에 필수라 확장했습니다"

---

## 51. Sprint 3 — Queue update 전략 (name만 변경 허용)

### 문제
운영 중 Queue의 maxCapacity/TTL 변경 시:
- maxCapacity 변경 → sliceCount 변경 → Redis 슬라이스 파티셔닝 정합성 붕괴
- TTL 변경 → 기존 토큰과의 소급 적용 문제

### 결정
- name만 in-place 변경 허용 (Redis Key에 미사용, 안전)
- maxCapacity/TTL 변경 필요 시: Pause → Delete → 재생성 (Drain+재생성 패턴)

### 근거
데이터 정합성 > 설정 편의성

---

## 52. Sprint 3 — Queue delete는 PAUSED 상태에서만 허용

### 결정
기존 DRAINING/PAUSED 둘 다 허용 → PAUSED에서만 허용으로 변경.

### 근거
- ACTIVE에서 삭제하면 대기자가 즉시 소실
- 정지(PAUSED) → 대기자 처리 → 삭제 흐름을 강제
- DRAINING 상태 처리는 Sprint 5 이후 Redis 레벨에서 처리

---

## 53. Sprint 4 — PasswordHasher Port/Adapter 분리 (BCrypt)

### 결정
BCrypt를 domain에 직접 두지 않고 Port(domain) + Adapter(infrastructure)로 분리.

### 근거
- BCryptPasswordEncoder는 Spring Security 의존 → domain에 둘 수 없음
- ApiKeyHasher(SHA-256)는 java.security.MessageDigest(순수 Java) → domain OK
- 같은 "해싱"이지만 의존성 차이로 배치가 다름

```
domain:         PasswordHasher (interface)    ← Port
infrastructure: BcryptPasswordHasher          ← Adapter (Spring Security 의존)

domain:         ApiKeyHasher (class)          ← 순수 Java, domain 직접 배치
```

---

## 54. Sprint 4 — JWT를 api 계층에 배치 (domain 아님)

### 결정
JwtProvider, JwtAuthenticationFilter, SecurityConfig 전부 queue-api에 배치.

### 근거
- JWT는 "HTTP API 인증"이지 "비즈니스 규칙"이 아님
- Tenant 도메인 입장에서 JWT를 알 필요 없음 (create, deactivate에 JWT 불필요)
- infrastructure도 아님 — "외부 시스템 접근"이 아니라 "들어오는 요청 인증"

```
domain:         JWT 관련 코드 없음 ✅
infrastructure: JWT 관련 코드 없음 ✅
api:            JWT 전부 여기 ✅
```

---

## 55. Sprint 4 — API Key prefix "sk_live_" (Stripe 관례)

### 결정
API Key 원본 형식: "sk_live_" + SecureRandom 16byte hex (총 40자)

### 근거
- Stripe의 API Key 네이밍 컨벤션 참고 (업계 표준)
- sk = Secret Key
- rawKey는 발급 시 1회만 반환, DB에는 SHA-256 해시만 저장
- Platform도 원본을 모름 → 분실 시 Revoke 후 재발급

---

## 56. Sprint 4 — GlobalExceptionHandler를 api 모듈에 배치

### 결정
@RestControllerAdvice를 queue-api에 배치. queue-common이 아님.

### 근거
- GlobalExceptionHandler는 "예외 → HTTP 응답 변환" 역할
- HTTP는 API 계층 전용 관심사
- queue-batch는 같은 예외를 다른 방식으로 처리 (로그/재시도)
- 예외 정의(ErrorCode, BusinessException)는 common, 처리 방식은 각 모듈에서 결정

```
queue-common: BusinessException, ErrorCode   ← 예외 정의 (공유)
queue-api:    GlobalExceptionHandler          ← HTTP 응답 변환
queue-batch:  BatchExceptionHandler           ← 로그/재시도 (Sprint 9)
```

## 57. 동시성 제어 우선순위 정책

**Status**: Accepted  
**Date**: 2026-05  
**Context**: Sprint 5 진입 시점, Redis Sentinel 도입과 함께 동시성 제어 전반 정책화 필요

### Decision

동시성 문제가 발생하는 경우, 다음 우선순위로 도구를 선택한다.

1. DB 제약조건 (UNIQUE, FK, CHECK)
2. Redis 단일 키 원자 연산 (`SETNX`, `INCR`, Lua Script)
3. Kafka partition 순서 보장
4. DB 비관적 락 (`SELECT ... FOR UPDATE`) — 짧은 트랜잭션 한정
5. 분산 락 (`@DistributedLock`, Redisson 기반) — 위로 안 될 때만

상위 단계로 해결되면 하위 단계는 도입하지 않는다.

### Rationale

- **단순성 우선**: DB 제약은 추가 코드 없이 충돌 방지. 락 메커니즘은 최후 수단.
- **인프라 의존성 최소화**: 분산 락은 Redis 의존성과 fencing 문제를 도입하므로 비용 큼.
- **핫패스 보호**: enqueue/admit은 처리량이 핵심. 락 회피 가능한 Lua Script 방식으로 설계.
- **콜드패스 안전성**: createQueue 등 관리성 API는 충돌 빈도 낮음 → DB 비관적 락으로 충분.

### Consequences

**긍정**
- 도구 선택 기준이 명확 → 코드 리뷰 시 일관성
- Redis 장애 시 대부분의 핵심 흐름이 영향 받지 않음 (DB 제약 + Kafka)
- 신규 기능 도입 시 "분산 락 우선 도입" 같은 안일한 판단 차단

**부정**
- 1~3단계 도구 선택을 위한 도메인 이해 필요 (학습 곡선)
- 외부 시스템 결합 작업은 4~5단계로 fall back → 별도 검토 필요

### Related

- DEC-XX (Queue 생성 동시성), DEC-XX (`@DistributedLock` 도입)
- `docs/CONCURRENCY.md` §2

### Interview Point

> "동시성 제어는 항상 최소한의 도구로 풀어야 합니다. DB 제약, Redis 원자 연산, Kafka 순서 보장으로 풀 수 있다면 락은 안 쓰는 게 좋습니다. 분산 락은 fencing token, TTL 만료, GC pause 같은 함정이 많아 신중하게 적용해야 합니다."

---

## 58. Queue 생성 동시성 처리 방식

**Status**: Accepted  
**Date**: 2026-05  
**Context**: 동일 tenant로 동시 `POST /queues` 요청 시 (1) queueName 중복 (2) tenant quota 초과 두 가지 동시성 문제 발생 가능

### Decision

`createQueue`는 **DB 비관적 락 + UNIQUE 제약 조합**으로 처리한다. 분산 락은 도입하지 않는다.

```java
@Transactional
public Queue createQueue(Long tenantId, CreateQueueCommand cmd) {
    Tenant tenant = tenantRepository.findByIdForUpdate(tenantId).orElseThrow();
    long count = queueRepository.countByTenantId(tenantId);
    if (count >= tenant.getPlan().maxQueues()) {
        throw new QuotaExceededException(tenantId);
    }
    try {
        return queueRepository.save(Queue.create(tenantId, cmd));
    } catch (DataIntegrityViolationException e) {
        throw new QueueAlreadyExistsException(tenantId, cmd.queueName());
    }
}
```

- `findByIdForUpdate`: `SELECT ... FOR UPDATE`로 tenant row 잠금 (quota 검증 직렬화)
- `UNIQUE(tenant_id, queue_name)`: queueName 중복 자동 차단
- Redis/Kafka 초기화는 `@TransactionalEventListener(AFTER_COMMIT)`으로 분리

### Rationale

**왜 분산 락이 아닌가**:
- createQueue는 관리성 API → 요청 빈도 낮음 (충돌 거의 없음)
- 트랜잭션이 짧음 (count + insert) → 락 점유 시간 무시 가능
- DB row만 보호 대상 → 분산 락의 "여러 시스템 보호" 장점 불필요
- Redis 분산 락 도입 시 fencing/TTL 문제 추가 → 비용 대비 효익 낮음

**왜 비관적 락 + UNIQUE 조합인가**:
- quota 검증은 `count → check → insert` 사이 TOCTOU 윈도우 존재 → row 락 필요
- queueName 중복은 단일 row UNIQUE로 해결 → 락 범위 좁히기

### Consequences

**긍정**
- 추가 인프라 의존성 없음 (DB만 사용)
- API 서버 장애 시 DB 커넥션 종료로 락 자동 해제 → 안전
- 트랜잭션과 락 해제가 자연 결합 → 누락 위험 없음

**부정**
- Tenant row가 createQueue, createApiKey 등 여러 작업의 잠금 대상이 될 수 있음 → 핫스팟화 가능성 (현재 빈도 낮아 무시)
- 외부 시스템 호출이 추가되면 트랜잭션 안에 둘 수 없음 → 후속 검토 필요

### Alternatives Considered

| 대안 | 기각 사유 |
|------|---------|
| Redis `@DistributedLock` | 인프라 의존성 추가, fencing 문제, 빈도 대비 과설계 |
| 낙관적 락 (`@Version`) | quota 검증 실패 시 재시도 복잡, count 쿼리와 결합 안 됨 |
| Application 레벨 idempotency key | 좋은 보조 수단이나 동시성 자체는 못 풀음 |

### Related

- DEC-XX (동시성 제어 우선순위 정책)
- `docs/CONCURRENCY.md` §2.4, §3

### Interview Point

> "createQueue 같은 관리성 API는 요청 빈도가 낮고 트랜잭션이 짧아서 비관적 락의 단점이 거의 드러나지 않습니다. 반면 Redis 분산 락은 TTL 만료, fencing 같은 문제가 추가됩니다. 그래서 DB 비관적 락으로 quota 검증을 직렬화하고, queueName 중복은 UNIQUE 제약으로 처리했습니다. Redis 초기화 같은 외부 작업은 `@TransactionalEventListener(AFTER_COMMIT)`으로 분리해서 트랜잭션 안에서 외부 호출을 하지 않도록 했습니다."

---

## 59. `@DistributedLock` 도입 및 모듈 배치

**Status**: Accepted  
**Date**: 2026-05  
**Context**: 일부 작업(외부 시스템 호출 포함, 추상 자원 보호)은 비관적 락으로 풀 수 없음. 사내 분산 락 어노테이션 필요.

### Decision

Redisson 기반 `@DistributedLock` 어노테이션을 사내에서 정의해 사용한다.

**모듈 배치**:
- `queue-common`: `@DistributedLock` 어노테이션 (pure Java, Redisson 의존성 없음)
- `queue-infrastructure`: `DistributedLockAspect` 구현 (Redisson 의존)

**적용 규칙**:
- Key 형식: `lock:{domain}:{id}:{action}` (예: `lock:tenant:{tenantId}:queue-create`)
- 전역 락 금지, 항상 tenant/queue 단위 이하로 좁힘
- 락은 반드시 `@Transactional`보다 바깥 (`@Order(HIGHEST_PRECEDENCE)`)
- 부수 작업은 `@TransactionalEventListener(AFTER_COMMIT)`로 분리

**기본 파라미터**: `waitTime=3s`, `leaseTime=10s`

### Rationale

**왜 표준 어노테이션이 아닌가**:
- Java/Spring 표준에는 분산 락 어노테이션 없음 (`@Transactional`은 트랜잭션 한정, Spring Data JPA `@Lock`은 단일 DB 한정)
- ShedLock의 `@SchedulerLock`은 스케줄러 전용
- 따라서 사내 정의가 사실상 표준 패턴

**왜 모듈 분리인가**:
- 헥사고날 원칙: 도메인/애플리케이션은 인프라(Redisson)에 의존하지 않아야 함
- 어노테이션은 인터페이스 → `queue-common`이 적절
- Aspect는 Redisson 구현 → `queue-infrastructure`

**왜 `@Order(HIGHEST_PRECEDENCE)`인가**:
- `@Transactional`과 `@DistributedLock`이 같은 메서드에 붙으면 AOP 실행 순서가 동시성에 영향
- 락이 트랜잭션보다 안쪽이면 락 해제 후 커밋 전에 다음 요청 진입 → 중복 생성
- 명시적 Order로 락이 바깥임을 보장

### Consequences

**긍정**
- 외부 시스템 호출 포함 작업의 동시성 보호 가능
- 헥사고날 원칙 유지 (도메인이 Redisson을 모름)
- AOP로 비즈니스 코드와 락 로직 분리

**부정**
- Redisson 의존성 추가
- TTL 만료, GC pause, fencing 등 분산 락 함정 학습 필요
- AOP 순서 디버깅 비용 (Order 명시로 완화)

### Anti-patterns to Avoid

- `synchronized`, `ReentrantLock`을 락 대용으로 사용 (단일 JVM 한정)
- 트랜잭션 안에 분산 락 (`@Transactional` 메서드 안에서 `@DistributedLock` 메서드 호출)
- 전역 락 (`lock:queue-create-global`처럼 tenant 무관)
- 트랜잭션 안에서 외부 시스템 호출 (Kafka 발행, Redis 초기화 등)

### Related

- DEC-XX (동시성 제어 우선순위 정책)
- DEC-XX (Queue 생성 동시성)
- `docs/CONCURRENCY.md` §4

### Interview Point

> "분산 락은 표준 어노테이션이 없어서 Redisson을 AOP로 감싸 `@DistributedLock`을 직접 정의했습니다. 헥사고날 원칙을 지키기 위해 어노테이션은 queue-common, Aspect는 queue-infrastructure에 두었습니다. 가장 큰 함정은 `@Transactional`과의 순서로, 락이 트랜잭션보다 안쪽이면 락 해제 후 커밋 전에 다음 요청이 들어와 보호가 깨집니다. `@Order(HIGHEST_PRECEDENCE)`로 락이 트랜잭션보다 바깥임을 명시했고, 더 안전하게는 락 메서드와 트랜잭션 메서드를 별도 빈으로 분리하는 패턴도 권장합니다."


## 60. Sprint 5 — Rate Limiter 알고리즘 선택 (Token Bucket)

**Status**: Accepted
**Date**: 2026-06
**Context**: 멀티 테넌시 환경에서 Tenant별 SLA 차등 한도 적용 필요. 5개 알고리즘 비교 후 선택.

### Decision

Tenant SLA 한도용 알고리즘으로 **Token Bucket**을 채택.

| 알고리즘 | 정확성 | Burst | 메모리 | Queue Platform 적합도 |
|---------|--------|-------|--------|---------------------|
| Fixed Window | 낮음 | 시간 경계 | O(1) | △ (시간 경계 burst 문제) |
| Sliding Window Log | 매우 높음 | 없음 | O(N) | ✗ (메모리 부담) |
| Sliding Window Counter | 높음 | 작음 | O(1) | △ (burst 처리 부족) |
| **Token Bucket** ⭐ | 높음 | 제어 가능 | O(1) | ✓ |
| Leaky Bucket | 매우 높음 | 없음 | O(1) | ✗ (burst 거부) |

### Rationale

**Token Bucket 선택 이유**:
- capacity (burst 허용량) + refillRatePerSecond (평균 처리량) 분리 제어
- 콘서트 티켓팅 같은 burst 트래픽 흡수 (양동이 가득찬 상태 → 즉시 통과)
- SaaS Plan과 자연스러운 매핑 (capacity = refillRate × 60)
- O(1) 메모리 (Hash 필드 in-place 갱신)

**다른 알고리즘 기각 사유**:
- Fixed Window: 시간 경계 burst (12:59 + 13:00 = 한도의 2배 통과 가능)
- Sliding Window Log: 요청마다 O(N) 메모리 → 대규모 트래픽에 부적합
- Leaky Bucket: 일정 속도 강제, burst 거부 → SLA의 burst 허용과 충돌

### Consequences

**긍정**
- Tenant Plan(FREE/STARTER/PRO/ENTERPRISE)별 capacity/refillRate 차등 가능
- 콘서트 시작 시 폭증 흡수 (양동이 일시 비움 후 회복)
- 단순 구현 (Redis Hash 2필드 + Lua Script)

**부정**
- 양동이 가득찬 상태가 항상 보장되지 않음 (장시간 미사용 후 큰 burst 가능)
- → 운영 시 burst 도달 빈도 모니터링 필요

### Interview Point

> "Rate Limiter 알고리즘은 비즈니스 요구사항에 따라 다릅니다. Queue Platform은 콘서트 티켓팅 같은 burst 트래픽을 흡수해야 하므로 Token Bucket을 선택했습니다. capacity는 1분치 burst를 허용하도록 refillRate × 60으로 설정했고, 양동이 모델이 SaaS Plan과 자연스럽게 매핑됩니다. 단순 Fixed Window는 시간 경계 burst 문제가 있고, Sliding Window Log는 O(N) 메모리 부담이 있어서 부적합했습니다."

### Related

- §61 (알고리즘 분리), §62 (Tenant Plan 도입)
- `doc/sprint-5/RATE_LIMITER.md`

---

## 61. Sprint 5 — Rate Limiter 알고리즘 분리 (Token Bucket + Fixed Window)

**Status**: Accepted
**Date**: 2026-06
**Context**: 인증 후(Tenant SLA)와 인증 전(보안) 한도의 의도가 다름. 같은 알고리즘으로 처리 시 의미 모호.

### Decision

용도별로 다른 알고리즘 적용. 인터페이스도 분리.

| 용도 | 알고리즘 | 인터페이스 |
|------|---------|----------|
| Tenant SLA (인증 후) | Token Bucket | `RateLimiter` |
| 인증 전 보안 (signup/login/refresh) | Fixed Window | `FixedWindowRateLimiter` |

### Rationale

**왜 Token Bucket이 인증 전에 부적합한가**:
- Token Bucket = burst 허용용 (capacity까지 즉시 통과)
- 인증 전 endpoint는 정상 사용자가 분당 1-5회 → burst 불필요
- burst 허용이 오히려 공격자에게 유리 (예: signup capacity 5 → 초기 5회 burst 가능)

**왜 Fixed Window가 보안에 적합한가**:
- "1분에 N회만 허용" 명확한 의미
- Burst 불허 → 정상 사용자엔 영향 없음 (도달 X)
- 한도 도달 = 비정상 신호 (공격/봇)
- 시간 경계 burst 문제도 한도가 작아 영향 미미 (5/분이 잠시 10/2분이 되는 정도)

**왜 인터페이스를 분리하는가**:
- 다른 책임 (SLA vs 보안)
- 다른 시그니처 (capacity + refillRate vs limit + windowSize)
- 다른 호출 자리 (Filter에서 인증 후/전 분기)
- 다형성 불필요 (같은 자리에서 교체 X)

**인터페이스 분리해도 인터페이스의 가치 유지**:
- 의존성 역전 (도메인이 Redis 모름) ✓
- 테스트 용이성 (Mock 가능) ✓
- 명세 (Contract) ✓
- → 다형성만이 인터페이스의 유일한 가치가 아님

### Consequences

**긍정**
- 의미 명확 (알고리즘 이름에서 의도 추론 가능)
- 면접 답변 자산 (왜 분리했나)
- 각자 최적화 가능

**부정**
- 클래스 수 증가 (도메인 포트 2개, 구현 2개)
- 단순함은 약간 손해

### Alternatives Considered

| 대안 | 기각 사유 |
|------|---------|
| 단일 RateLimiter (Token Bucket 강제) | 인증 전 burst 허용이 보안 목적과 충돌 |
| 단일 RateLimiter (어색한 매핑) | capacity = limit, refillRate 무시 → 의미 불명 |

### Interview Point

> "인증 후와 인증 전 한도는 의도가 다릅니다. Tenant SLA는 burst 처리(콘서트 티켓팅)가 핵심이라 Token Bucket을 썼고, 인증 전 보안 한도는 burst가 불필요해서 Fixed Window를 썼습니다. 한 인터페이스로 통합하려 했지만 시그니처가 달라 어색했고, 두 인터페이스로 분리해도 의존성 역전, 테스트 용이성, 명세라는 인터페이스의 다른 가치는 유지됩니다. 다형성만이 인터페이스의 유일한 가치는 아닙니다."

### Related

- §60 (Token Bucket 채택), §62 (Tenant Plan)
- `doc/sprint-5/RATE_LIMITER.md`

---

## 62. Sprint 5 — Tenant Plan 도입 (SaaS 등급)

**Status**: Accepted
**Date**: 2026-06
**Context**: Tenant별 동적 Rate Limit 한도 필요. SaaS Plan과 매핑.

### Decision

Tenant 도메인에 `Plan` enum 추가. 각 Plan이 Rate Limiter 파라미터 정의.

```java
public enum Plan {
    FREE(100, 1.67),            // 분당 100 RPS
    STARTER(1_000, 16.67),      // 분당 1,000 RPS
    PRO(10_000, 166.67),        // 분당 10,000 RPS
    ENTERPRISE(100_000, 1_666.67); // 분당 100,000 RPS

    private final int capacity;
    private final double refillRatePerSecond;
}
```

**비율**: `capacity = refillRatePerSecond × 60` (1분치 burst 허용)

**DB 저장**: `plan TINYINT NOT NULL DEFAULT 0` (TenantStatus 패턴과 동일)

### Rationale

**왜 capacity = refillRate × 60인가**:
- 콘서트 티켓팅: 시작 1분 안에 매진 (현실 시나리오)
- 1분 후 트래픽 안정화
- 1분치 burst가 비즈니스와 맞음

**왜 enum인가**:
- Plan은 고정된 셋 (확장 가능하지만 동적 추가 X)
- 코드에서 Plan.PRO처럼 명시적 사용
- DB에는 ordinal 저장 (TINYINT)

**왜 Tenant 도메인에 두는가**:
- Plan은 비즈니스 개념 (SaaS 등급 = 약속)
- Rate Limit은 그 약속의 한 구현
- 도메인 객체로 자연스러움

### Consequences

**긍정**
- Tenant별 SLA 차등 적용
- Plan 변경 시 다음 요청부터 자동 반영
- 비즈니스 모델과 자연스러운 매핑

**부정**
- Filter에서 매 요청 Tenant 조회 → DB 부담
- → Sprint 5-D에서 Redis 캐시 도입 예정

### Interview Point

> "Tenant Plan은 SaaS 비즈니스 모델의 핵심입니다. Free 100 RPS, Pro 10,000 RPS 같은 약속을 Rate Limiter에 매핑했습니다. capacity = refillRate × 60으로 1분치 burst를 허용해서 콘서트 시작 같은 폭증을 처리합니다. Plan은 Tenant 도메인에 enum으로 두고, DB에는 TINYINT로 저장합니다. TenantStatus 패턴과 동일하게 ordinal 매핑이라 추가 컬럼 없이 확장 가능합니다."

### Related

- §60 (Token Bucket), §61 (알고리즘 분리)
- `doc/sprint-5/RATE_LIMITER.md`

---

## 63. Sprint 5 — RateLimitFilter HTTP 통합

**Status**: Accepted
**Date**: 2026-06
**Context**: Rate Limit을 HTTP Filter에 통합. Filter 체인 위치, Tenant 식별, 키 패턴 결정 필요.

### Decision

`RateLimitFilter`를 `JwtAuthenticationFilter` **뒤**에 등록.

```java
.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
.addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);
```

**키 패턴**:
- 인증 후: `rl:tenant:{tenantId}` + Tenant Plan 한도 (Token Bucket)
- 인증 전: `rl:{action}:ip:{ip}` + 고정 한도 (Fixed Window)
    - `rl:signup:ip:127.0.0.1`
    - `rl:login:ip:127.0.0.1`
    - `rl:refresh:ip:127.0.0.1`

**응답**:
- 한도 초과 시 HTTP 429
- `Retry-After` 헤더 (초 단위)
- Body: `{ "error": "RL001", "message": "...", "retryAfter": 60 }`

### Rationale

**왜 JwtAuthenticationFilter 뒤인가**:
- JWT 파싱이 먼저 → SecurityContext에 TenantAuth 저장
- RateLimitFilter가 그 정보로 Tenant Plan 한도 적용
- 순서 반대면 Tenant 식별 불가 → 모든 요청이 IP 기반 (의도 X)

**왜 IP 기반 인증 전 한도인가**:
- Tenant 식별 불가 (인증 안 됨)
- IP가 유일한 식별자
- X-Forwarded-For 헤더 우선 (Nginx 프록시 대응)

**왜 NAT 공유 IP에도 한도 너무 엄격하지 않은가**:
- 회사 100명 동시 가입 = 같은 IP
- 한도 분당 5회 → 정상 사용자도 차단 가능
- → 분당 5-10회는 정상 사용자에겐 충분 (하루 1-2회 가입)

**왜 인증 필요 endpoint를 인증 없이 호출 시 Rate Limit 안 적용하는가**:
- SecurityConfig가 401로 차단 → 시스템 자원 거의 안 씀
- Filter에서 정확한 endpoint별 키 패턴 모호
- 단순화 우선 (책임 분리: 인증/인가 vs Rate Limit)

### Consequences

**긍정**
- 인증 후/전 모두 보호
- Tenant Plan 차등 적용 가능
- 표준 HTTP 패턴 (429 + Retry-After)

**부정**
- 매 요청 Tenant DB 조회 → 5-D에서 캐시
- IP 기반은 NAT 공유 IP 한계
- → 추가 보안 (CAPTCHA 등)은 Sprint 6+

### Interview Point

> "Rate Limit Filter는 JWT 인증 뒤에 배치합니다. JWT 파싱이 먼저 SecurityContext에 Tenant 정보를 저장하고, RateLimitFilter가 그 정보로 Plan 한도를 적용합니다. 인증 전 endpoint(signup/login/refresh)는 IP 기반 + Fixed Window로 Brute Force와 회원가입 남용을 방지합니다. X-Forwarded-For 헤더로 실제 IP를 추출하고, NAT 공유 IP 대비 한도를 너무 엄격하게 잡지 않습니다."

### Related

- §60 (Token Bucket), §61 (분리), §62 (Plan)
- `doc/sprint-5/RATE_LIMITER.md`

---

## 64. Sprint 5 — Redis Lua Script Bean 등록 패턴

**Status**: Accepted
**Date**: 2026-06
**Context**: Lua Script가 늘어날 예정 (Token Bucket, Fixed Window, Enqueue, Admit, Ranking 등). Bean 등록 전략 결정.

### Decision

**현재 단계 (Script 1-5개)**: Bean 개별 등록 + Helper 메서드.

```java
// Helper 메서드 (Bean 아님)
private <T> RedisScript<T> loadScript(String path, Class<T> resultType) {
    DefaultRedisScript<T> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource(path));
    script.setResultType(resultType);
    return script;
}

@Bean
public RedisScript<Long> tokenBucketScript() {
    return loadScript("lua/token-bucket.lua", Long.class);
}

@Bean
public RedisScript<Long> fixedWindowScript() {
    return loadScript("lua/fixed-window.lua", Long.class);
}
```

### Rationale

**왜 일괄 로드(Map 패턴) 아닌가**:
- Script 5개 미만이라 boilerplate 부담 적음
- 타입 안전성 (Class<T> 파라미터)
- Bean 명시적 → 가독성 ↑
- Map 키 문자열 의존 → 런타임 확인 (컴파일 X)

**왜 Helper 메서드인가**:
- 3줄 boilerplate를 1줄로 줄임
- @Bean 메서드는 여전히 명시적
- 추가 쉬움 (한 줄)

**확장 시점**:
- Script 5-10개: 도메인별 Configuration 분리 (RedisRateLimitConfig, RedisQueueConfig)
- Script 10+: 일괄 로드 (Map<String, RedisScript>) 검토

### Consequences

**긍정**
- 단순 + 명시적
- 학습 단계 적합
- 면접 답변 깔끔

**부정**
- Script 늘어나면 RedisConfig 비대해짐
- → 시점에 도메인별 분리

### Interview Point

> "Lua Script Bean 관리는 단계별 전략입니다. 현재 1-5개 단계는 개별 @Bean 등록 + Helper 메서드로 boilerplate를 줄였습니다. Script가 5-10개로 늘면 도메인별 Configuration 분리(RedisRateLimitConfig, RedisQueueConfig)를 검토하고, 10개 이상이면 일괄 로드 패턴을 고려합니다. 단순함과 가독성을 우선하되, 확장 시점의 패턴 변경 가능성을 열어둡니다."

### Related

- §60 (Token Bucket), §61 (분리)

---

## 65. Sprint 5 — 인증 전 Rate Limit 알고리즘 의도 (Burst 불허)

**Status**: Accepted
**Date**: 2026-06
**Context**: 인증 전 endpoint(signup/login/refresh)의 한도 알고리즘 선택. Token Bucket vs Fixed Window 비교.

### Decision

인증 전 endpoint는 **Fixed Window** 사용. Token Bucket 안 씀.

| 측면 | 인증 후 (Token Bucket) | 인증 전 (Fixed Window) |
|------|-------------------|------------------|
| 목적 | SLA + 비즈니스 | 보안 + 보호 |
| Burst | 허용 (콘서트 티켓팅) | 불허 (정상 분당 1-5회) |
| 한도 도달 | 영업 시그널 | 비정상 시그널 |
| 정상 사용자 도달 | 가능 | 거의 X |

### Rationale

**왜 인증 전엔 burst 불필요한가**:
- 정상 사용자: 회원가입 하루 1-2회, 로그인 5-10회, refresh 분당 1회
- Token Bucket의 burst 5-10 → 정상 사용자에게 의미 없음
- 오히려 공격자에게 도움 (초기 burst 5회 즉시 통과)

**왜 Fixed Window 의도 명확한가**:
- "1분에 N회" 명시적 한도
- 도달 = 비정상 신호 (정상은 절대 도달 X)
- 시간 경계 burst 영향 미미 (한도 작아서)

**한도 결정 근거**:
- SIGNUP 5/분: 정상 사용자는 하루 1-2회 → 5회는 충분
- LOGIN 10/분: 비밀번호 오타 3-5회 + 여유 → 10회 적절
- REFRESH 30/분: SDK 정상 15분마다 1회 → 30회는 매우 여유

### Consequences

**긍정**
- 보안 신호 명확 (한도 도달 = 공격/봇 의심)
- 정상 사용자엔 영향 X
- 알고리즘 의도 명확

**부정**
- NAT 공유 IP 차단 가능 (회사 100명 동시 가입 시)
- → CAPTCHA, 이메일 인증 등 보조 수단 (Sprint 6+)

### Interview Point

> "인증 전 endpoint의 Rate Limit은 의도가 다릅니다. Tenant SLA는 burst 처리가 핵심이지만, 인증 전 보안 한도는 burst가 오히려 공격자에게 유리합니다. 정상 사용자는 분당 1-5회 정도라 burst 불필요하고, 한도 도달 = 비정상 신호로 운영자가 알람 받을 수 있습니다. 한도 자체는 NAT 공유 IP 고려해 너무 엄격하지 않게 설정했습니다. SIGNUP 5/분, LOGIN 10/분, REFRESH 30/분으로 정상 사용엔 절대 도달 안 합니다."

### Related

- §60 (Token Bucket), §61 (알고리즘 분리)
- `doc/sprint-5/RATE_LIMITER.md`

---

## 66. Sprint 8+ — Redis Cluster 도입 결정 (Sentinel → Cluster 확장)

**Date**: 2026-07-08
**Context**: Sprint 5-D 완료 후 대규모 확장 대비 검토. 현재 Sentinel은 데이터 총량 한계 (Master 1대 메모리), Master Single Thread 병목이 명확. Multi-tenant SaaS로 성장 시 Tenant 30+ 시점에 인프라 전환 필요.

### Decision

**Sprint 10에서 Sentinel → Redis Cluster 전환**

단계적 도입:
- **Sprint 5-E~7**: Sentinel 유지 (Master 8-16GB, WAS 확장)
- **Sprint 8**: Cluster 로컬 학습 실습 (완료 - 2 Cluster × 4 Master × 1GB WSL)
- **Sprint 10**: 프로덕션 Cluster 도입 (3 Master + 3 Replica × 8-16GB)
- **Sprint 12**: Cluster 확장 (5-7 Master + Hash Tag)
- **Sprint 15+**: 4 Cluster × 4 Master × 4GB (극대 분산 최종)

### Rationale

**Sentinel의 한계**:
- Master 1대에 모든 데이터 저장 (총 저장 = Master 메모리)
- Single Thread 특성 → 초당 40,000 ops 한계
- Scale-Up만 가능 (메모리 증가), Scale-Out 불가

**Cluster의 이점**:
- 자동 샤딩 (16,384 slot)
- 여러 Master 병렬 처리 → 처리량 선형 증가
- Failover 손실 최소화 (Master 수 많을수록)
- Multi-tenant 격리 (Master별 부하 분산)

**단계적 접근 근거**:
- 학습 우선 (Sprint 8 로컬 실습)
- 안전한 전환 (Sentinel + Cluster 병행 실행)
- Master 크기 결정 후 확장 (초기 8-16GB → 최종 4GB)

**로컬 실습 완료 사항** (2026-07-08):
- Sentinel (6379-6381, 26379-26381) 유지
- Cluster A (7001-7008): 4 Master + 4 Replica × 1GB
- Cluster B (8001-8008): 4 Master + 4 Replica × 1GB
- 총 22 Redis 프로세스
- Failover 검증 완료
- Cluster 간 완전 독립성 확인

### Consequences

**긍정**:
- 확장성 확보 (Tenant 100+ 대응)
- Failover 손실 최소화 (16 Master 시 6.25%)
- CPU 코어 완전 활용 (Master 수 = 활용 코어 수)
- 실무 관행 준수 (Netflix, Twitter, Uber)

**부정**:
- 관리 복잡성 증가 (노드 수 6 → 32)
- Cluster 통신 오버헤드
- Multi-key 명령 제약 (Hash Tag 필요)
- 자동화 필수 (Prometheus, Grafana)

**중립**:
- ~~Lua Script 무변경 (단일 key 사용 - Queue Platform 특성)~~
  → **개정 (§70, 2026-07-15)**: 5-E에서 `INCR seq`가 추가되어 `enqueue_bulk.lua`는 **2-key**가 됨.
    Hash Tag를 선제 적용(`queue/QueueKeys.java`)하여 **결과적으로 무변경 작동은 유지**
- Spring 코드 무변경 (application.yml 변경만)
- Lettuce 자동 라우팅

### Interview Point

> "Queue Platform은 Sprint 5까지 Sentinel 기반이지만, Sprint 10에서 Cluster로 전환합니다. Sentinel은 데이터 총량이 Master 하나 메모리로 제한되고 Single Thread 병목이 있어 대규모 트래픽 처리에 한계가 있습니다. Cluster는 자동 샤딩으로 여러 Master가 병렬 처리하여 처리량이 선형 증가하고, Master별 부하 격리로 Multi-tenant 서비스에 적합합니다. Sprint 8에서 로컬 WSL에 Sentinel과 병행 실행하여 학습했습니다. 프로덕션 Cluster는 실제 운영 관행에 따라 Master 크기를 8-16GB로 시작해 부하 편차 관찰 후 확장할 계획입니다. Lua Script는 대기열 ZSet과 순번 카운터 두 키를 쓰기 때문에 Hash Tag로 같은 slot에 묶어 CROSSSLOT을 회피했고, 로컬 Cluster에서 실제로 검증했습니다."

> ⚠️ 개정 (§70, 2026-07-15): 위 면접 답변의 마지막 문장은 원래 "Lua Script는 단일 key만 사용하므로 CROSSSLOT 이슈 없이 그대로 사용 가능합니다"였다. 5-E에서 `INCR seq` 도입으로 2-key가 되어 사실이 아니게 되었고, Hash Tag 적용 후 위 문장으로 개정했다.

### Related

- `queue-domain/docs/ARCHITECTURE_ROADMAP.md` (Phase 2, 부록 C-E)
- `doc/INFRA_SETUP.md` §6.5 (로컬 Cluster 실습 가이드)
- §67 (이중 라우팅), §68 (Master 크기), §69 (극대 분산)

---

## 67. Sprint 12+ — 이중 라우팅 아키텍처 (Cluster + Hash Tag)

**Date**: 2026-07-08
**Context**: Cluster만으로는 자동 slot 분배에 의존해 부하 편차 제어 불가. 대형 Queue 하나가 특정 Master로 몰릴 때 다른 Master로 회피 불가. 애플리케이션의 세밀한 제어 필요.

### Decision

**Cluster Routing (Layer 1) + Hash Tag Master Routing (Layer 2) 동시 도입**

**Layer 1 - Cluster 선택 (Application Router)**:
- 여러 개의 독립 Cluster 운영
- Tenant Tier 또는 예상 규모 기반 선택
- Least Load 알고리즘

**Layer 2 - Master 선택 (Hash Tag)**:
- 선택한 Cluster 내 4-16 Master 중 결정
- `{shard_X}` 문법으로 Master 지정
- 부하 기반 Shard 결정

**최종 Key 예시**: `queue:{shard_A2}:q_bts_002:waiting`
- Cluster: A (Application이 결정)
- Master: 2 (shard_A2로 slot 계산)
- Queue: q_bts_002

### Rationale

**Cluster만으로 부족한 이유**:
- CRC16 자동 분배는 무작위 (부하 편차 발생 가능)
- 특정 Master 부하 시 다른 Master로 회피 불가
- 대형 Tenant 격리 어려움

**Hash Tag만으로 부족한 이유**:
- 하나의 Cluster 내 제어만 가능
- 다중 Cluster 간 라우팅 불가
- 대형 Tenant 완전 격리 어려움

**이중 라우팅의 이점**:
- Layer 1: Cluster 간 격리 (SLA 차등, 지역 분산)
- Layer 2: Master 간 부하 균등 (세밀한 제어)
- 완전한 부하 관리

**실무 관행**:
- Netflix EVCache: Service Clusters + Sharding
- Twitter: Region Clusters + Hash Sharding
- Uber: Service Clusters + Geographic Sharding

### Consequences

**긍정**:
- 완전한 부하 제어
- Tenant Tier 격리 (SLA 차등)
- 대형 Queue 완전 격리
- 실무 표준 아키텍처

**부정**:
- 관리 복잡성 큼 (다중 Cluster + 라우팅 로직)
- Queue → Cluster/Shard 매핑 DB 필요
- 자동화 리소스 요구
- 초기 도입 부담

**중립**:
- Queue 도메인에 `clusterName`, `shard` 필드 추가
- 애플리케이션 부하 측정 로직 필요
- Sprint 12 (Layer 2) → Sprint 15+ (Layer 1+2) 단계적

### Interview Point

> "Cluster 도입 후에도 자동 slot 분배만으로는 부하 편차와 대형 Tenant 격리에 한계가 있습니다. 이중 라우팅은 Layer 1에서 Application이 Cluster를 선택하고, Layer 2에서 Hash Tag로 Cluster 내 Master를 선택합니다. 최종 key는 `queue:{shard_A2}:q_bts_002:waiting` 형태로, `{shard_A2}` 부분만으로 slot 계산해 원하는 Master에 배치됩니다. Netflix EVCache, Twitter가 이 방식으로 대규모 트래픽 처리합니다. Queue Platform은 Sprint 12에 Hash Tag를 먼저 도입하고, Sprint 15+에 다중 Cluster를 완성해 대형 Tenant SLA 차등을 지원할 계획입니다."

### Related

- `queue-domain/docs/ARCHITECTURE_ROADMAP.md` (부록 F)
- §66 (Cluster 도입), §69 (극대 분산)
- `doc/INFRA_SETUP.md` §6.5-12 (이중 라우팅 실습)

---

## 68. Sprint 10+ — Master 크기 최적화 (Single Thread 병목 해결)

**Date**: 2026-07-08
**Context**: Redis Single Thread 특성으로 Master 크기가 커도 CPU 코어 1개만 사용. 실무에서 큰 Master 소수보다 작은 Master 다수가 효율적임을 확인.

### Decision

**Master 크기 4-16 GB로 유지, Master 수를 늘리는 방향으로 확장**

단계별 결정:
- **Sprint 10 초기**: 3 Master × 8-16 GB (관리 부담 최소)
- **Sprint 12 확장**: 5-7 Master × 8-16 GB (부하 편차 발생 시)
- **Sprint 15+ 최종**: 16 Master × 4 GB (극대 분산, 4 Cluster × 4 Master)

### Rationale

**Redis Single Thread 병목**:
- 각 Redis 프로세스 = CPU 코어 1개만 사용
- 초당 30,000-50,000 ops 한계 (Master 하나)
- CPU 크기 무관 (16코어 CPU여도 1코어만)
- Master 메모리 크기와 성능 무관

**해결 원리 - Master 늘리기**:
- Master 수 = CPU 코어 활용 수
- 처리량 선형 증가
- 5 Master: 200,000 ops/초
- 10 Master: 400,000 ops/초
- 16 Master: 640,000 ops/초

**물리 서버 배치**:
- 각 Master 별도 서버 = CPU 낭비 (Redis 1코어만 사용, 서버 16코어 존재)
- 여러 Master 같은 서버 = CPU 완전 활용 (실무 권장)
- r6g.2xlarge (8 vCPU, 64 GB)에 8 Redis 프로세스

**실무 관행**:
- Netflix EVCache: Master 10-50개, 각 8-16 GB
- Twitter: Master 100+, 물리 서버당 여러 Master
- LinkedIn: Master 100+, CPU 활용 극대화

### Consequences

**긍정**:
- 처리량 대폭 증가 (선형)
- CPU 활용도 극대화
- Failover 시 손실 최소화 (16 Master 시 6.25%)
- 비용 효율 (물리 서버 공유)

**부정**:
- Cluster 통신 오버헤드 증가
- Failover 감지 시간 증가
- Rebalancing 오래 걸림
- 자동화 도구 필수

**중립**:
- Sweet Spot: Master 5-16개
- 32 GB+ Master는 CPU 낭비 (사용 안 함)
- Queue Platform은 Sprint 12에 5-7 Master, Sprint 15+ 16 Master

### Interview Point

> "Redis는 Single Thread 특성으로 Master 하나가 CPU 코어 1개만 사용합니다. 8코어 서버여도 Redis 하나는 1코어만 사용해서 CPU가 낭비됩니다. 따라서 Master 크기를 크게 하는 것보다 Master 수를 늘리는 것이 처리량 극대화에 유리합니다. 처리량은 선형 증가하며, 물리 서버 하나에 여러 Master를 배치하면 CPU를 완전 활용할 수 있습니다. Netflix EVCache나 Twitter는 실제로 Master를 100개 이상 운영합니다. Queue Platform은 초기 3 Master로 시작하고, Sprint 12에 부하 편차 관찰 후 5-7개로, Sprint 15+에 최종 16 Master (4 Cluster × 4)로 확장할 계획입니다. 각 Master는 8-16 GB, 극대 분산 시 4 GB로 최적화합니다."

### Related

- `queue-domain/docs/ARCHITECTURE_ROADMAP.md` (부록 H)
- §56 (Redis Single Thread 성능 한계)
- §66 (Cluster 도입), §69 (극대 분산)

---

## 69. Sprint 15+ — 극대 분산 아키텍처 (4x4x4GB 최종 구성)

**Date**: 2026-07-08
**Context**: 1억 대기 처리 대비 최종 인프라 구성 확정. SLA 균등 조건, 성능 우선, 실무 관행 반영.

### Decision

**4 Cluster × 4 Master × 4 Replica × 4GB (총 32 노드)**

- Cluster 수: 4개
- 각 Cluster: 4 Master + 4 Replica
- 각 Node: 4 GB
- 총 노드: 32개
- 총 저장 (Master): 64 GB
- 총 저장 (Master + Replica): 128 GB
- 총 처리량 (이론): 640,000 ops/초 (16 × 40,000)
- Failover 손실: 6.25% (16 Master 중 1)

### Rationale

**1억 대기 데이터 계산**:
- Redis ZSet 항목 128 bytes/member (UUID 기준)
- 1억 항목 = 12.8 GB
- Redis 오버헤드 20% + 안전 마진 30% = **약 22 GB 필요**

**4 Cluster 분산 배치**:
- Cluster별: 22 / 4 = 5.5 GB
- 각 Cluster 저장 용량: 16 GB (4 Master × 4 GB)
- 사용률: 34% (여유 66%)

**Master 4 GB 최적 근거**:
- 각 Master 4 GB × 4 = 16 GB per Cluster
- CPU 1개 완전 활용 (Single Thread 한계)
- 관리 부담 감당 가능
- 확장 여유 3배

**물리 서버 배치**:
- r6g.2xlarge (8 vCPU, 64 GB) × 4대
- 각 서버 8 Redis 프로세스 (4 Master + 4 Replica)
- CPU 완전 활용
- 메모리 32 GB 사용 (64 GB 여유)

**비용 (EC2 자체 관리)**:
- 컴퓨팅: $1,161/월
- 네트워크: $500-1,000/월
- 관리: $200-300/월
- **총: $1,961-2,561/월**
- 연간: $23,532-30,732
- 사용자당: $0.00025 (0.25원)

### Consequences

**긍정**:
- 대규모 트래픽 처리 (640,000 ops/초)
- Failover 손실 최소 (6.25%)
- 완전한 이중 라우팅 지원
- 비용 효율 (EC2 관리로 ElastiCache 대비 66% 절감)
- Multi-AZ 배치 (4 AZ 분산)
- 확장 여유 3배 (Master 크기 증가로 5억 대기까지)

**부정**:
- 관리 복잡성 큼 (32 노드)
- 자동화 필수 (Prometheus, Grafana, 자동 Failover)
- 초기 설계 시간 큼
- 팀 리소스 요구

**중립**:
- Sprint 5-E~10: 단일 Cluster 유지
- Sprint 12: 이중 라우팅 도입 (Layer 2)
- Sprint 15+: 4 Cluster 완성 (Layer 1+2)
- 단계적 진화

### Interview Point

> "1억 대기 처리를 위한 최종 인프라는 4 Cluster × 4 Master × 4 Replica × 4 GB, 총 32 노드입니다. 데이터 크기는 Redis ZSet 항목 128 bytes 기준 1억 항목 = 12.8 GB, 오버헤드와 안전 마진 반영해 22 GB 필요합니다. 4 Cluster에 분산하면 각 Cluster 16 GB 저장 용량으로 34% 사용률에 여유 66%입니다. Master 크기를 4 GB로 유지하는 이유는 Redis Single Thread 특성 때문에 크기 늘려도 CPU 낭비이고, Master 수를 늘려야 처리량이 선형 증가하기 때문입니다. 물리 서버는 r6g.2xlarge 4대로 각 서버에 8 프로세스 배치해 CPU를 완전 활용합니다. AWS ElastiCache 대신 EC2 자체 관리로 월 $2,000 수준으로 66% 비용 절감이 가능합니다. Failover 시 16 Master 중 1대만 손실되어 영향이 6.25%로 최소화됩니다. Netflix EVCache, Twitter의 관행을 따른 설계입니다."

### Related

- `queue-domain/docs/ARCHITECTURE_ROADMAP.md` (부록 I)
- §66 (Cluster 도입), §67 (이중 라우팅), §68 (Master 크기)
- `doc/INFRA_SETUP.md` §6.5-10 (로컬-프로덕션 매핑)

---

## 70. Sprint 5-E — Bulk 단독 + seq 키 + Hash Tag (D7/D8 개정, CROSSSLOT 선제 대응)

**Date**: 2026-07-15
**Context**: 5-E 구현 과정에서 두 가지가 §66-69 수립 시점의 전제를 깼다.
1. **하이브리드 단건 경로 제거** — D7(enqueue.lua + enqueue_bulk.lua 2개), D8(임계값 1000 req/s)이 코드와 불일치
2. **score 발급을 `INCR seq`로 변경** — Lua가 **2-key**가 되면서 "단일 key만 사용 → CROSSSLOT 이슈 없음"(§67 전제, CONCURRENCY §6.5, FLOW)이 **거짓이 됨**

### Decision

**D7 개정**: `enqueue.lua` 폐기. **`enqueue_bulk.lua` 단독**으로 모든 요청 처리.

**D8 개정**: 임계값·단건 분기 삭제. 배치 상수만 유지 (`MAX_DRAIN=5000`, `CHUNK_SIZE=500`, `fixedRate=1000ms`, 타임아웃 30s).
> ⚠️ 원안(간격 10ms, 타임아웃 1s) 대비 100배/30배 이탈 상태. **재조정은 별도 과제**로 분리 (아래 Consequences 참조).

**D9 신설 — score는 `INCR queue:{queueId}:seq`**: 큐별 전역 순번 카운터를 2번째 키로 도입.

**D10 신설 — Hash Tag 필수**: 모든 Queue Engine 키는 `{queueId}`로 감싼다.
```java
// queue/QueueKeys.java (cache/RedisKeyFactory 아님 — ratelimit/RateLimitKeys 선례)
public static String waiting(String queueId) { return "queue:{" + queueId + "}:waiting"; }
public static String seq(String queueId)     { return "queue:{" + queueId + "}:seq"; }
```

### Rationale

**왜 enqueue.lua를 폐기했나**:
- 경로 2개 = 코드 2벌 + 임계값 튜닝 + **두 경로가 같은 순번 체계를 공유함을 증명할 부담**
- 배치 하나면 모든 요청이 동일 코드를 지나므로 순번 유일성 증명이 한 번으로 끝남

**왜 seq 키가 필요한가** (단일 key로 되돌릴 수 없는 이유):
- `ZCARD + 1`: admit으로 중간이 빠지면 score 충돌
- `System.currentTimeMillis()`: 같은 ms 요청끼리 동점 → 순서 뒤집힘
- **`INCR`만이 단조증가·유일을 보장** → 2-key 구조는 불가피

**왜 Hash Tag인가** (로컬 Cluster A 실측):
```
queue:q_bts:waiting     → slot 7911   → 포트 7002   ┐ 다른 마스터
queue:q_bts:seq         → slot 11273  → 포트 7003   ┘ → CROSSSLOT 에러

queue:{q_bts}:waiting   → slot 10592  → 포트 7003   ┐ 같은 마스터
queue:{q_bts}:seq       → slot 10592  → 포트 7003   ┘ → 정상 실행
```
- Cluster는 `CRC16(key) % 16384`로 슬롯 결정. Lua는 **노드 한 대에서만** 실행되므로 키가 다른 슬롯이면 시작조차 거부
- 해시태그는 "같은 슬롯에 넣어달라"는 요청이 아니라 **"슬롯 계산 시 중괄호 안쪽만 본다"는 규칙**
- 검증: `keyslot("q_bts")` = `keyslot("queue:{q_bts}:waiting")` = **10592** (동일 입력 → 동일 슬롯이 수학적으로 보장)

**왜 Sprint 10이 아니라 지금인가**:
| 시점 | 비용 |
|---|---|
| 지금 (Sentinel) | 문자열 2줄. 슬롯 개념이 없어 **무해**. 운영 데이터 0 |
| Cluster 전환 후 | 키 이름 변경 = **슬롯 이동 = 데이터 이관**. 대기 중 유저의 순번을 유지한 무중단 마이그레이션 필요 |

### Consequences

**긍정**:
- Cluster 전환 시 **무변경 작동** (§66 Sprint 8+ 목표 유지)
- Sprint 10까지 미루지 않고 **로컬 Cluster A에서 실제 `enqueue_bulk.lua` 실행 검증 완료** (alice OK / bob OK / alice EXISTS, seq=3)
- 키 조립이 `QueueKeys` 한 곳으로 집중 (기존엔 `RedisQueueEngine` + 테스트 3곳에 하드코딩)

**부정**:
- **단건 경로 제거의 대가**: 저부하 요청도 배치 주기를 전액 부담. `fixedRate=1000ms`면 **평균 500ms, 최악 1s**. 하이브리드 시절엔 단건 경로로 빠져나가 이 비용이 없었음
  - → **후속 과제**: `fixedRate`를 D8 원안(10ms)~50ms로 재조정 후 실측
- 인스턴스당 처리량 상한 = `MAX_DRAIN / fixedRate` = **5,000 req/s**. 유입이 이를 넘으면 globalQueue 적체 → 30s 타임아웃 → 실패

**중립**:
- **큐 1개 = 슬롯 1개 = 마스터 1대 고정.** D2(ZSet 하나)의 필연적 귀결이며 해시태그가 만든 문제가 아님. 1,000만 대기 시 ZSet 실측 **114.5 bytes/멤버 → 약 1.07 GB**가 단일 마스터에 집중 (§69의 128 bytes 추정과 근사)
- §67 이중 라우팅 도입 시 태그가 shard로 이동: `queue:{shard_A2}:q_bts_002:waiting`

### Interview Point

> "Redis Cluster는 CRC16(key) % 16384로 슬롯을 정하고 마스터마다 슬롯 범위를 나눠 갖습니다. 저희 enqueue_bulk.lua는 대기열 ZSet과 순번 카운터 두 키를 함께 다루는데, 해시태그가 없으면 각각 slot 7911, 11273으로 흩어져 서로 다른 마스터에 저장됩니다. Lua Script는 노드 한 대에서만 원자적으로 실행되므로 CROSSSLOT 에러가 납니다. Redis가 옆 노드에서 알아서 가져오지 않는 이유는 그 순간 원자성이 깨지고, 이를 막으려면 분산 트랜잭션이 필요한데 Redis는 속도를 위해 그 복잡도를 거부했기 때문입니다. 해결책은 해시태그로, 키에 중괄호를 씌우면 슬롯 계산에 중괄호 안쪽만 쓰입니다. 두 키의 queueId가 같으니 같은 슬롯이 수학적으로 보장됩니다. 중요한 건 타이밍인데, 지금은 Sentinel이라 해시태그가 무해하지만 Cluster 전환 후에 고치면 키 이름 변경이 곧 슬롯 이동이라 대기 중인 사용자의 순번을 유지한 채 데이터를 이관해야 합니다. 2줄 수정과 무중단 마이그레이션 프로젝트의 차이라 선제 적용했고, 로컬 Cluster에서 실제 스크립트를 돌려 검증했습니다."

### Related

- §66 (Cluster 도입), §67 (이중 라우팅 — 태그가 shard로 이동), §69 (ZSet 128 bytes 추정)
- `doc/CONCURRENCY.md` §6.5 (Multi-key Lua + Hash Tag)
- `doc/FLOW.md` (Enqueue 결정 근거 D1-D10)
- `queue-infrastructure/.../queue/QueueKeys.java`, `ratelimit/RateLimitKeys.java` (선례)

---

## 71. Sprint 5-E / 9+ — Enqueue 저장 순서(Redis → DB) 확정 + DB → Redis 복구 설계

**Date**: 2026-07-27
**Context**: enqueue 영속화가 **Redis(순서 부여) → Kafka → DB(비동기 적재)** 순서로 구현돼 있으나, (1) 왜 이 순서인가, (2) Redis 전손 시 DB로 어떻게 복구하는가가 문서화되지 않았다. §70 D9(`INCR seq`)로 seq를 Redis가 부여하고 DB `tokens.seq`에 저장하는데, **이 seq 저장이 복구의 열쇠**이므로 순서와 복구를 함께 확정한다.

### Decision

**D11 — Enqueue 저장 순서: Redis 먼저, DB 나중 (비동기)** (구현 완료, Sprint 5-E)
- **Redis = 순서 진실원천**(seq·ZSet). enqueue 도착 즉시 `INCR`/`ZADD NX` 원자 부여 → 202 응답.
- **DB = 내구 원장**. Kafka(현재) 또는 Redis Stream outbox(후속) 경유 **비동기** 적재.
- seq는 **Redis가 부여, DB `tokens.seq`에 저장**(복구용).
- ⚠️ 대비 — **종료 경로(만료/complete)는 반대: DB 먼저 → Redis 정리**. 그땐 최종 과금 상태의 권위가 DB이므로. → "권위가 어디냐"가 순서를 결정.

**D12 — 복구 3계층 (DB 재구성은 최후수단)** (설계, 구현 후속)
1. **Sentinel failover → replica 승격**(데이터 보유) → 복구 불필요 [최빈]
2. **Redis AOF/RDB → 재시작 시 디스크 재적재** [Redis 자체 durability]
3. **DB → Redis 재구성** → 1·2 모두 실패한 **전손 시** [disaster recovery]

**D13 — DB → Redis 재구성 절차** (큐 단위, **분산락으로 큐 잠금 후** 실행)
```
① waiting ZSet:  SELECT user_id, seq FROM tokens WHERE queue_id=? AND status=0 (WAITING)
                 → ZADD queue:{q}:waiting {seq} {user_id}        (청크 페이징, 배치 ZADD)
② seq 카운터:    SELECT MAX(seq) FROM tokens WHERE queue_id=?    (모든 status! NULL이면 0)
                 → SET queue:{q}:seq {maxSeq}                    (사용된 번호 재발급 방지)
③ tokens 해시:   WAITING rows → HSET queue:{q}:tokens {user_id} {token_id}
④ admit 키:      status=1(ADMIT_ISSUED) AND admit_token IS NOT NULL AND issued_at > now-60s
                 → SET admit-token-by-token:{token_id} ... EX {남은 60s}   (양방향)
                 (60s 초과분은 복원 안 함 → 배치가 returnToWaiting 처리)
⑤ last-active:   재구성 안 함(비움) → 다음 폴링(ka=1)이 재populate. inactive_ttl 리셋뿐 무해
```

### Rationale

**왜 Redis 먼저 (DB auto-increment로 seq 못 매기는 이유)**:
- **타이밍** — 위치는 enqueue 순간(동기) 필요, DB insert는 async(수 초 후) → auto-inc는 너무 늦어 202/폴링에 순위 못 줌
- **순서** — DB auto-inc = insert 순서(Kafka 파티션 병렬·Consumer 배치) ≠ **도착 순서** → FIFO 뒤집힘. `INCR`은 도착 순간 단일스레드 원자 → 정확
- **단위** — auto-inc는 tokens 테이블 전역, 필요한 건 **큐별** 순번(`INCR queue:{q}:seq`는 자연히 큐별)
- → **seq는 Redis가 부여, DB는 저장만**. 부여는 Redis, 기억은 DB.

**왜 복구를 waiting ZSet 스캔이 아니라 DB로 하나 / 왜 seq 순으로**:
- waiting ZSet은 **mutable**(admit/cancel/만료 시 ZREM) → "현재 대기자"이지 "모든 enqueue 로그"가 아님 → 소스로 부적합
- DB `tokens.seq`로 `ZADD score=seq` → **원래 순서 그대로 복원**(rank 보존). seq를 DB에 저장한 값어치가 여기서 실현

**왜 last-active는 복구 안 하나**:
- DB에 "마지막 폴링 시각"이 없음. 비워도 무해 — 다음 폴링이 채우고 inactive_ttl만 리셋

### Consequences

**긍정**:
- **DB가 안전망** → Redis 전손도 순서까지 정확 복구 가능 (seq 저장 = 복구 가능성의 근거)
- 복구 계층화로 대부분(failover/AOF)은 자동 처리, DB 재구성은 드문 최후수단

**부정**:
- **복구 완전성 = DB 신선도만큼만**. async 적재 지연 중 Redis엔 있었지만 DB 미반영된 enqueue는 복구 불가 = 현재 **fire-and-forget Kafka의 유실 gap**. → **Outbox(Redis Stream) + 대사(reconciliation)**로 보강 (후속 과제)
- 대용량 큐(수백만 대기) 재구성 = 청크 페이징 + 배치 ZADD 필요(메모리·시간)
- 재구성 중 신규 enqueue와 경쟁 → **큐 분산락** 필요(재구성 원자성)

**중립**:
- 재구성 **트리거**: (a) lazy — enqueue/poll 시 `queue:{q}:seq` 없는데 DB에 WAITING 존재하면 rebuild, 또는 (b) admin/startup 잡. ZADD는 멱등이라 부분 재실행 안전
- ADMIT_ISSUED-이나-60s-초과 토큰은 논리적으로 waiting 복귀 대상 → 배치가 재구성 후 정리

### Interview Point
> "enqueue의 순번은 Redis가 `INCR`로 부여하고 DB `tokens.seq` 컬럼에 저장합니다. DB auto-increment를 안 쓴 이유는 세 가지인데, 첫째 위치는 사용자가 enqueue하는 순간 필요한데 DB 적재는 Kafka를 거쳐 수 초 뒤 비동기라 너무 늦고, 둘째 auto-increment는 insert 순서를 반영하는데 그건 Kafka 파티션 병렬·Consumer 배치 때문에 도착 순서와 달라 FIFO가 뒤집히며, 셋째 auto-increment는 테이블 전역이라 큐별 순번을 못 줍니다. 그래서 Redis가 도착 순간 원자적으로 부여하고 DB는 저장만 합니다. 이 저장이 복구의 핵심인데, Redis가 전손되면 복구는 3계층입니다. 대부분은 Sentinel failover로 replica가 승격해 복구가 필요 없고, 그다음이 AOF/RDB 재적재, 최후수단이 DB 재구성입니다. DB 재구성은 큐를 분산락으로 잠근 뒤 WAITING 토큰을 seq 순서로 ZADD하면 대기 순서까지 정확히 복원됩니다. 여기서 주의할 점은 복구 완전성이 DB 신선도만큼이라, 비동기 적재 지연 중 Redis엔 있었지만 DB에 아직 안 들어간 enqueue는 유실될 수 있다는 겁니다. 현재 발행이 fire-and-forget이라 이 gap이 존재하고, Redis Stream Outbox와 대사(reconciliation)로 보강하는 게 후속 과제입니다. 그리고 복구 소스로 live waiting ZSet을 쓰면 안 되는데, admit/cancel로 멤버가 빠져나가는 mutable 구조라 짧게 살다 간 토큰을 놓치기 때문입니다. append-only인 DB(또는 outbox)가 소스여야 합니다."

### Related
- §70 (`INCR seq`·Hash Tag — 복구의 근거), §66 (Cluster/failover)
- `doc/STATE.md` (Token 상태 0=WAITING/1=ADMIT_ISSUED), `doc/schema.sql` (tokens.seq/admit_token 컬럼)
- memory: `sprint5-token-kafka-progress`(Kafka 적재·fire-and-forget gap), `token-ttl-design`(returnToWaiting)

---

## 72. Sprint 5-E 개정 — Enqueue DB 영속화: Kafka 제거 → Redis List outbox + @Scheduled + ShedLock

**Date**: 2026-07-27
**Context**: 5-E는 enqueue→DB 적재를 Kafka(`enqueue-events` 토픽 + `@KafkaListener` Consumer)로 구현했다(§70, `sprint5-token-kafka-progress`). Kafka 클러스터 운영 부담을 제거하고 **API 서버 내 처리**로 변경한다. 단 "API 서버 내 스케줄러"의 두 함정(**크래시 유실·스파이크 OOM**)을 피하려면 스케줄러가 읽을 소스가 **durable append-only**여야 한다. 이 결정은 §71 D11의 "적재 수단"을 Kafka에서 구체화하는 것으로, §71의 순서(Redis→DB)·복구(DB→Redis)는 그대로 유효하다.

### Decision

**Kafka 제거. Enqueue DB 영속화 = Redis List outbox + `@Scheduled` 소비 + ShedLock(리더 선출).**

- **소스 = Redis List** (durable, append-only). ~~waiting ZSet~~(mutable → cancel/admit로 빠진 토큰 유실), ~~in-memory~~(유실/OOM) 배제.
- **List(not Stream)인 이유**: **DB가 영구 원장**이라 처리 후 outbox 데이터를 보존할 필요가 없음(replay·다중 소비 need 없음) → **ack=삭제(LREM)로 자동 청소**가 딱 맞음. Stream의 로그 보존·XTRIM은 오버스펙.
- **reliable-queue 패턴**: `RPUSH pending` → `LMOVE pending→processing`(claim, 안 지움) → 배치 INSERT(멱등) → `LREM processing`(ack=삭제). **reaper**가 processing에 오래 방치된 것(죽은 워커)을 pending으로 재-queue.
- **중복방지(N대)**: **ShedLock(리더 1대, 직렬)**. List엔 consumer group 분배가 없으므로. DB 드레인 ~1.5만/s를 1대가 감당 → 충분.
- **격리**: 소비를 **전용 스레드 + 별도 HikariCP 풀**(bulkhead)로 → 영속화가 요청용 커넥션·스레드 고갈 안 시킴.

### Rationale

- **왜 durable 소스 필수**: 스케줄러는 "소비자"일 뿐, 유실 방지엔 append-only durable **소스**가 별도로 필요. Redis-first라 그 소스가 Redis에 산다. 스케줄러가 소스를 없애주지 않는다.
- **왜 List(not Stream)**: 소비자가 "DB 저장" 하나뿐 + DB=원장이라 완료분 보존 불필요 → `ack=LREM=삭제` 자동청소. Stream의 log/consumer-group/replay는 이 용도엔 짐(+ XTRIM 청소 부담).
- **at-least-once + 멱등**: 순서는 반드시 **insert 먼저 → ack 나중**(ack 먼저면 크래시 시 유실=at-most-once). ack 실패 시 재처리로 **중복** 발생 → **멱등 insert**(`@SQLInsert ON DUPLICATE KEY UPDATE`)로 무해화. "안 놓치되 중복은 멱등으로".
- **왜 ShedLock(not consumer group)**: List는 그룹 분배가 없어 N대가 같은 표를 집으면 중복 → 리더 1대만 드레인. 규모상 직렬로 충분(병렬 필요 시 Stream 승급).

### Consequences

**긍정**:
- Kafka 클러스터 운영·의존성 제거. 개념 단순(List 명령). 완료분 자동 청소(XTRIM 불필요).
- 포트 추상화 덕에 **어댑터·소비만 교체**, 도메인 로직 무변경.

**부정**:
- 영속화가 **API JVM에서** 실행 → 스파이크 때 heap/GC/CPU를 요청 처리와 **공유**(머신 격리 안 됨; Kafka는 별 머신이었음). → 전용 스레드+별도 DB 풀로 **스케줄링만 격리**, 리소스는 공유(트레이드오프 인지).
- **리더 1대 직렬** → 영속화 처리량 = 1대 능력치. 병렬 필요 시 Stream+consumer group 승급.
- **reliable-queue(processing+reaper)를 직접 구현**해야 함(Stream이면 XPENDING/XAUTOCLAIM 내장).
- **poison message**(계속 insert 실패) → 무한 재시도로 outbox 정체 가능 → 재시도 한도+**dead-letter** 별도 필요(후속 과제).

**중립**:
- 제거 대상: `KafkaEnqueueEventPublisher`, `TokenEnqueueConsumer`(@KafkaListener), Kafka 토픽/리스너 설정·의존성, 관련 Kafka 테스트.
- 유지: `EnqueueEventPublisher`(포트), `EnqueueEvent`, `TokenEntity`(멱등 insert)·`TokenRepository`·`TokenEnqueueService`, **seq는 Redis `INCR`**(§70 D9).
- 교체: 발행 어댑터 Kafka send → **List `RPUSH`** / 소비 @KafkaListener → **@Scheduled 리커버리-큐 드레인**.

### Interview Point
> "enqueue의 DB 적재를 Kafka에서 Redis List outbox + 스케줄러로 바꿨습니다. 핵심은 스케줄러가 소비자일 뿐 유실 방지엔 durable append-only 소스가 따로 필요하다는 점인데, Redis-first 구조라 그 소스가 Redis에 삽니다. 대기열 ZSet은 admit·cancel로 멤버가 빠지는 mutable 구조라 짧게 산 토큰을 놓쳐 소스로 못 쓰고, in-memory는 크래시 유실·스파이크 OOM이라 안 됩니다. Stream이 아니라 List를 고른 이유는 DB가 영구 원장이라 처리 끝난 outbox 데이터를 보존할 필요가 없어서, ack을 곧 삭제(LREM)로 두는 자동 청소가 딱 맞기 때문입니다. Stream의 로그 보존·replay·XTRIM은 이 용도엔 오히려 짐입니다. 신뢰성은 pending에서 processing으로 LMOVE해 옮겨두고 DB insert 성공 후에만 LREM하는 reliable-queue 패턴으로 at-least-once를 보장하고, ack 실패 시 재처리로 생기는 중복은 tokenId 유니크 + ON DUPLICATE KEY의 멱등 insert로 무해화합니다. 서버가 여러 대라 같은 표를 중복 처리하지 않도록 ShedLock으로 리더 한 대만 드레인하는데, DB 드레인 속도를 한 대가 감당하는 규모라 직렬로 충분하고 병렬이 필요해지면 Stream의 consumer group으로 승급하면 됩니다. 트레이드오프는 영속화가 API JVM에서 돌아 스파이크 때 리소스를 요청과 공유한다는 점이라, 전용 스레드와 별도 커넥션 풀로 스케줄링을 격리했습니다."

### Related
- §71 (Redis→DB 순서·DB→Redis 복구 — 이 결정이 D11의 적재 수단을 구체화), §70 (`INCR seq`)
- memory: `sprint5-token-kafka-progress` (제거 대상 Kafka 구현체 목록)
- `doc/CONCURRENCY.md` (@DistributedLock/ShedLock — 리더 선출)

---

## 73. Sprint 5-E 재개정 — Enqueue 영속화: List → Stream → **Kafka 복귀** + 소비 전담 모듈 분리

**Date**: 2026-08-10
**Context**: §72로 Kafka를 걷어내고 Redis List outbox를 도입했으나, 이후 **Redis Stream(Consumer Group)으로 한 번 더 옮겼고 그 전환은 문서화되지 않았다**. 여기에 Sprint 6-7의 상태 전이(admit/complete/cancel/expire)를 설계하면서 **Stream으로는 풀 수 없는 요구**가 드러나 Kafka로 되돌린다. 세 세대(List → Stream → Kafka)를 한 절에 정리해, "왜 왔다 갔는가"가 기록에서 끊기지 않게 한다. §71의 순서(Redis→DB)·복구(DB→Redis)는 그대로 유효하며, 이 결정은 §72와 마찬가지로 **적재 수단만** 바꾼다.

### Decision

**D14 — List → Stream (중간 세대, 문서화만 하고 폐기)**
- §72가 손으로 만들기로 했던 것 — `processing:{worker}` 목록, heartbeat, 워커 명단, reaper, ShedLock 리더 선출 — 이 **전부 Consumer Group + PEL의 기본 기능**이었다. 직접 구현분이 통째로 삭제됐다.
- `LREM`은 **payload 바이트가 일치해야** 지워진다 → 재직렬화가 끼어들면 ack이 조용히 무력화. `XACK`은 **엔트리 ID 기준**이라 그 취약점이 구조적으로 없다.
- 큐별 스트림 `queue:{queueId}:outbox` + 해시태그 → `enqueue_bulk.lua`가 waiting·seq·outbox를 **한 원자 단위**로 쓸 수 있는 길이 열렸다(§70 D10과 동일 슬롯).
- 대가: `XACK`은 PEL에서만 빼고 **원본을 남긴다** → `XTRIM`(MINID+MAXLEN) 청소를 직접 해야 한다. List의 `ack=삭제` 자동 청소는 잃었다.

**D15 — Stream → Kafka 복귀 (본 결정)**
- 발행: `KafkaEnqueueEventPublisher`(infra 어댑터), 소비: **신설 `queue-consumer` 모듈**.
- **`EnqueueEventPublisher` 포트는 그대로.** 도메인·서비스 코드 무변경 — 어댑터만 교체.

**D16 — 파티션 키 = `tokenId`** (⚠️ 이 결정이 D15의 핵심)
- 같은 토큰의 모든 이벤트가 같은 파티션 → **상태 전이 순서 보장**.
- ~~`queueId`~~ 배제: 큐 카디널리티가 낮고 "한 큐에 30만 명"이 정상 시나리오라 **트래픽 99%가 한 파티션**에 몰린다(파티션을 늘려도 해결 안 됨). `tokenId`는 토큰마다 유일해 분산과 묶음을 동시에 얻는다.

**D17 — 파티션 18 / `replication.factor=3` / `min.insync.replicas=2` / `acks=all` / `enable.idempotence=true`**
- 18은 컨슈머 대수 후보(2·3·6·9)로 나눠떨어지는 값. **파티션은 줄일 수 없고, 늘리면 `hash % N`이 바뀌어 살아 있는 토큰의 순서 관계가 끊긴다**(토큰 수명 최대 `waitingTtl` 2시간) → 처음에 넉넉히 잡는 값.
- `min.insync.replicas=1`이면 `acks=all`이 사실상 `acks=1`이 되어 리더 장애 시 유실 → 협상 대상 아님.

**D18 — 토픽 = `token-lifecycle` (생명주기 통합 단일 토픽)**
- Kafka의 순서 보장은 **같은 토픽의 같은 파티션** 안에서만 성립. `enqueue-events` / `token-status-changed`로 나누면 키가 같아도 `WAITING → ADMIT_ISSUED` 순서가 보장되지 않는다.
- **컨슈머 그룹은 나누지 않는다.** 그룹 분리는 *팬아웃*(쓰기 대상이 다를 때)이지 *작업 분담*이 아니다. 같은 `tokens` 행을 두 그룹이 쓰면 각자 독립 offset으로 달려 순서가 다시 깨진다. 병렬화는 파티션이 담당한다. "전담 컨슈머"는 **한 리스너 안에서 핸들러 분기**로 표현한다.

**D19 — 발행 시한: `max.block.ms=4000` + `request.timeout.ms=3000` + `delivery.timeout.ms=8000` + `linger.ms=5`, 어댑터 대기 `12000ms`**
- 기본값(`max.block` 60s + `delivery` 120s)은 **동기 요청 경로에 맞지 않는다**. 발행이 오래 매달리면 호출자가 먼저 포기해 503을 전달할 곳이 없어진다.
- 사슬: `max.block(4s) + delivery(8s) = 12s ≤ 어댑터 대기(12s) < 클라이언트 타임아웃`. 어댑터 대기를 프로듀서 최악 시한보다 **크게** 두는 이유는, 더 짧으면 브로커가 판정 중인데 우리가 먼저 포기해 **"모름"을 "실패"로 단정**하기 때문이다.

**D20 — 소비를 `queue-consumer` 모듈로 분리 (≠ queue-batch)**
- **확장 방향이 반대**다. 소비는 유입량에 비례해 파티션 수만큼 늘려야 하고, 스케줄 작업(TTL 만료 감지·파티션 정리)은 늘릴수록 중복 실행 방지 장치가 필요해진다. 한 프로세스에 두면 어느 쪽도 제대로 늘릴 수 없다.
- `queue-batch`는 껍데기로 남긴다(Sprint 7·9에서 채운다). consumer에는 **`@EnableScheduling`을 붙이지 않는다** — 붙이면 infra의 `@Scheduled` 빈까지 함께 돌아 이중 적재가 된다.

### Rationale

- **왜 Stream을 버리는가 ① 순서**: Stream의 Consumer Group은 **건별 배분**이라 같은 토큰의 두 이벤트가 다른 소비자에게 갈 수 있다. 순서를 얻으려면 소비자를 1대로 묶거나(=§72 ShedLock 회귀, 병렬 포기) 스트림을 `outbox:{hash(tokenId)%N}`으로 샤딩해야 하는데 **후자는 파티션을 손으로 재구현하는 것**이다. Kafka의 파티션은 "쪼갬 + 순서 경계 + **그룹 내 독점**"을 한 번에 준다. 셋째가 핵심이고 Stream에 없는 것도 그것뿐이다.
- **왜 Stream을 버리는가 ② 복구 완전성**: outbox가 대기열 ZSet과 **같은 Redis 인스턴스**에 살면 상관 실패(correlated failure)다. 전손 시 잃어버린 데이터와 **"무엇을 잃었는지 알려줄 증거"가 함께 사라진다**. §71 D12/D13이 남긴 "복구 완전성 = DB 신선도만큼" 갭을 메울 수단이 Stream엔 없다. Kafka는 다른 머신·디스크·3중 복제라 Redis 전손과 **독립 사건**이 되어, `MAX(seq)` 이후를 replay해 갭을 메울 수 있다.
- **갭 크기 비교(판단이 뒤집힌 지점)**: Lua 원자화가 막는 갭은 "Lua 성공 ~ 발행 사이 프로세스 크래시" = **수 ms**. Redis 전손 갭은 "마지막 적재 이후 유입분" = **수 초~수 분**. 폭이 100배 이상 다르다 — Lua 원자성을 결정적 근거로 삼은 것은 영향 폭 기준으로 과대평가였다.
- **포기하는 것**: `XADD`를 `enqueue_bulk.lua`로 옮겨 at-least-once를 완성하는 길. Redis와 Kafka 사이엔 분산 트랜잭션이 없어 **이 갭은 Kafka에서 영구적**이다 → **reconciliation(정합성 대사)이 필수 후속 과제**가 된다.
- **왜 순서 보장이 정확성의 유일한 근거가 되면 안 되는가**: 파티션 순서는 평시 대부분을 커버하지만 **리밸런스 재처리·파티션 증설·DLT 격리·프로듀서 재시도**에서 깨진다. 순서는 "평소에 단순하게", 별도 방어는 "그래도 틀리지 않게"를 담당한다(상태 전이 설계는 §후속).
- **왜 실패를 2분류하는가**: 제약 위반은 재시도해도 결과가 같아 뒤의 정상 항목까지 막고(독약), 일시 장애를 격리하면 멀쩡한 수백 건을 버린다. Spring Kafka 기본값은 예외를 가리지 않고 재시도 후 격리 → `addNotRetryableExceptions(DataIntegrityViolationException)`으로 명시해야 한다. 배치 리스너는 인덱스 없이 던지면 **배치 전체가 DLT로** 가므로, 이분 탐색으로 범인 인덱스를 찾아 `BatchListenerFailedException`으로 **한 건만** 격리한다(§72가 List에서 만든 이분 탐색 로직의 이식).

### 실측 (2026-08-10, 로컬 100만건 enqueue)

| 지점 | 결과 |
|---|---|
| 부하 | 1,000,000 성공 / 실패 0 / 429 0 / 625s (1,600 RPS, 평균 593ms) |
| Redis `ZCARD` = `seq` = DB `tokens` | 1,000,001 전부 일치 |
| `DISTINCT token_id` / `DISTINCT seq` | 중복 0, seq 결번 0 |
| consumer lag / DLT | 0 / 0 |
| 파티션 분포(18개) | 편차 **1.4%** — D16이 의도대로 동작 |

**1차 시도는 1,410건 실패**했는데 원인은 애플리케이션이 아니라 **WSL2 VM suspend 16.8분**이었다. 진단 근거: 모노토닉 625.9s vs 벽시계 1,634s(차이 1,008s), `/proc/uptime`이 벽시계보다 67분 적음, Kafka 배치 만료 age가 정확히 1,004.8s, DB에 16분 공백. **`HttpTimeoutException`·Kafka `TimeoutException`은 "패킷이 안 갔다"가 아니라 "시간이 지났다"** — 정지 중 벽시계만 흐르다 재개 순간 타이머가 일괄 발화한 것이다(네트워크는 끊긴 적 없음: Redis·MySQL 에러 0, ISR 정상). 이때 **835건이 "Redis엔 있고 DB엔 없는 유령 토큰"**으로 남아, D15가 예고한 발행 갭이 실측으로 확인됐다.

### Consequences

**긍정**:
- 토큰 단위 **순서 보장 + 소비 병렬화**를 동시에 확보(Stream은 양자택일이었다).
- outbox가 Redis 밖으로 나가 **전손과 독립** → §71 D13 복구에 "Kafka replay로 DB 미적재분 보충" 계층이 추가 가능.
- Redis 메모리 압박 해소. outbox가 부풀어 `waiting` ZSet을 evict할 위험이 사라졌다.
- 소비 코드 대폭 감소: 회수(`XCLAIM`)·트림·그룹 생성·큐 목록 캐시·회전 커서·예산 3상수가 통째로 삭제. 배치 크기는 `max.poll.records` 하나로 결정된다(Stream의 `COUNT`는 스트림별이라 큐 수에 비례해 폭주했다).
- 자원 격리: 적재가 API JVM 밖(별도 프로세스·머신)으로 나가 §72가 인지한 트레이드오프가 해소됐다.

**부정**:
- **Kafka 클러스터 운영 부담이 돌아온다**(§72가 없애려던 것). KRaft 3브로커.
- **발행 원자성의 길이 닫힌다.** `XADD`를 Lua로 옮기는 경로가 사라져 발행 갭이 영구화 → **reconciliation 스위퍼 필수**.
- **파티션 증설이 위험 작업**이 된다(D17). 순서에 의존하는 대가.
- **head-of-line blocking이 큐 경계를 넘는다.** Stream은 큐마다 독립 스트림이라 한 큐가 막혀도 다른 큐는 멀쩡했지만, 이제 여러 토큰이 파티션을 공유한다.
- 역직렬화 자체가 실패한 항목은 값이 null이고 원본이 헤더에 남는다 → 원문 보존하려면 `DelegatingByTypeSerializer` 필요(후속).

**중립**:
- 제거: `RedisStreamOutboxPublisher/Consumer`, `OutboxKeys`, `OutboxDrainScheduler`, `TokenEnqueueService`, `EnqueueOutbox`·`OutboxEntry`·`DeadLetterReason`, 관련 테스트. `QueueRepository.findDrainableQueueIds`도 호출자가 사라져 함께 제거(Kafka는 브로커가 배분하므로 "훑을 큐 목록" 개념이 없다).
- 유지: `EnqueueEventPublisher`(포트), `EnqueueEvent`, `TokenEntity`(멱등 insert)·`TokenRepository`, **seq는 Redis `INCR`**(§70 D9), 해시태그(§70 D10 — Lua 통합 목적은 사라졌지만 큐 상태 키의 슬롯 일관성은 유효).
- `ShedLock` 리더 선출은 D14(Stream) 시점에 이미 불필요해졌고 Kafka에서도 필요 없다.

### Interview Point
> "enqueue 적재를 Redis List → Stream → Kafka 순으로 두 번 바꿨습니다. List에서 Stream으로 간 건 reliable-queue를 손으로 만들던 것 — 워커별 처리 목록, heartbeat, reaper, 리더 선출 — 이 전부 Consumer Group과 PEL의 기본 기능이었기 때문입니다. 그런데 Stream을 다시 버린 이유가 둘인데, 첫째는 순서입니다. Sprint 7에 admit·complete 같은 상태 전이가 들어오면 같은 토큰의 이벤트 순서가 필요한데, Stream의 consumer group은 건별로 배분해서 같은 토큰의 두 이벤트가 다른 소비자에게 갈 수 있습니다. 순서를 얻으려면 소비자를 한 대로 묶어 병렬을 포기하거나 스트림을 해시로 샤딩해야 하는데, 후자는 결국 파티션을 손으로 재구현하는 겁니다. Kafka 파티션의 본질은 쪼개는 능력이 아니라 쪼갠 것을 그룹 안에서 한 소비자가 독점한다는 규칙이고, Stream에 없는 게 정확히 그것뿐입니다. 둘째는 복구인데, outbox가 대기열 ZSet과 같은 Redis에 살면 전손 시 잃은 데이터와 무엇을 잃었는지 알려줄 증거가 같이 사라집니다. Kafka는 다른 머신이라 독립 사건이 되고, DB 최대 seq 이후를 replay해 갭을 메울 수 있습니다. 대신 포기한 게 있는데, Redis Stream이었다면 XADD를 Lua 안에 넣어 순번 부여와 이벤트 기록을 한 원자 단위로 묶을 수 있었지만 Redis와 Kafka 사이엔 분산 트랜잭션이 없어 그 길이 닫힙니다. 다만 그 갭은 프로세스 크래시 순간의 수 밀리초인 반면 Redis 전손 갭은 수 초에서 수 분이라, 영향 폭으로 보면 후자를 막는 게 맞다고 판단했습니다. 남는 갭은 정합성 대사로 메웁니다. 파티션 키는 tokenId인데, queueId로 잡으면 티켓팅처럼 한 큐에 30만 명이 몰리는 게 정상인 서비스라 트래픽이 통째로 한 파티션에 가서 파티션을 늘려도 소용이 없습니다. 실제로 100만건을 넣어보니 18개 파티션 편차가 1.4%였고, Redis와 DB 카운트가 정확히 일치하며 순번 중복도 결번도 0이었습니다."

### Related
- §72 (이 결정이 뒤집는 대상 — Kafka 제거·List 채택), §71 (Redis→DB 순서·복구 — 여전히 유효), §70 (`INCR seq`·Hash Tag)
- `scripts/kafka/create-topics.sh` (D17 — 파티션 수 불일치 감지 포함)
- `queue-consumer/` (D20), `queue-infrastructure/.../queue/KafkaEnqueueEventPublisher.java` (D16·D19)
- 후속 과제: reconciliation 스위퍼(유령 토큰 복구), `DelegatingByTypeSerializer`(DLT 원문 보존), 상태 전이 순서 설계(Sprint 6-7)
