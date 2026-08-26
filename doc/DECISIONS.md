# Queue Platform — 설계 결정 문서

> FRS v1.14 기준 | Entity 설계 / 보안 / 복구 전략 / 아키텍처

---

## 목차 — 기능별 색인

> **§번호는 작성 순서다.** 코드 주석·커밋 메시지·다른 문서가 §번호로 참조하므로 **재배치하지 않는다.**
> 이 표가 "무엇을 찾을 때 어디를 보는가"와 **"이게 아직 살아 있는 결정인가"**를 대신한다.
>
> | 상태 | 뜻 |
> |---|---|
> | ✅ | 유효 — 현행 결정 |
> | ⛔ | 대체됨 — 다른 §가 이 결정을 뒤집었다. 본문은 역사 기록 |
> | ✏️ | 개정됨 — 일부가 뒤집혔다. **어느 부분인지 각 절 머리 배너 참조** |
> | 📉 | 축소 — 범위가 좁혀졌다 |
> | 🚧 | 설계 확정 · 구현 미착수 |
>
> `(→§N)` = 교차 참조. 주 카테고리는 한 곳뿐이다.

### Ⅰ. 토큰 생명주기

**1. Enqueue**

| § | 제목 | 상태 |
|---|---|---|
| §17 | 대용량 처리 — Redis (Bulk Worker 항상 활성) (→8) | ✏️ |
| §31 | 대용량 Enqueue 시나리오 분석 (rps별) | ✅ |
| §70 | Bulk 단독 + seq 키 + Hash Tag (D7·D8 개정) | ✅ |
| §71 | 저장 순서(Redis → DB) + DB → Redis 복구 (→10) | ✅ |
| §72 | DB 영속화: Kafka 제거 → Redis List outbox (→10) | ⛔ |
| §73 | 영속화 재개정: List → Stream → **Kafka 복귀** + `queue-consumer` (→10) | ✅ |

**2. Polling**

| § | 제목 | 상태 |
|---|---|---|
| §38 | FLOW 개선 (`nextPollAfterSec` 적응형) | ✏️ |
| §74 | 폴링 소유권 검증 — `poll_verify.lua` 원자 1회 (→8) | ✅ |
| §79 | 폴링 응답 계약 — `admitWatermark` + `pacing`, 엔드포인트 2분할 | ✅ |

**3. Admit · Verify · Complete**

| § | 제목 | 상태 |
|---|---|---|
| §9 | Admit = Dequeue + 통계 갱신 | ✏️ |
| §10 | rank=1 중복 불가 보장 | ✅ |
| §11 | 재입장 재시도 로직 (Platform 관여 없음) | ✅ |
| §12 | Admit 방식 전면 변경 (admitToken 도입) | ✅ |
| §13 | 입장 토큰(admitToken) 설계 — TTL 60초 | ✏️ |
| §14 | admit 요청 순서 보장 — Kafka (→10) | ✏️ |
| §15 | Token 상태 추가 — ADMIT_ISSUED | ✅ |
| §22 | verify / complete 분리 | ✏️ |
| §33 | verify API 제거 검토 → 유지로 번복 | ✏️ |
| §34 | admitToken TTL 값(60초). ~~WAITING 복귀~~는 §36이 폐기 | ✏️ |
| §36 | admitToken TTL 만료 → **EXPIRED** (WAITING 복귀 **폐기**, 재접속 → 맨 뒤) | ✅ |
| §80 | **Sprint 7 Admit — 전 구간 원자 Lua + 동기 응답** (`verified-token`·`admit_requests` 폐기) | ✅ |
| §81 | `avgWaitingTime`·ETA·`queue-stats` 폐기 (→8) | ✅ |

**4. 이탈 · 만료 (TTL / EXPIRED)**

| § | 제목 | 상태 |
|---|---|---|
| §21 | 이탈 정책 — ~~CANCELLED~~ → **TTL 만료로 일원화** (§82가 대체) | ✅ |
| §82 | **Cancel API를 만들지 않는다 + 과금은 상태를 보지 않는다** (→9) | ✅ |

> TTL 만료는 별도 §가 없다. `ADMIT_TOKEN_TTL` → §34·§36, `waitingTtl`·`inactiveTtl` 판정 → `doc/STATE.md`,
> 배치 흐름 → `doc/FLOW.md`. **`inactiveTtl` 배치는 미구현**이며, §82 이후 이탈 회수의 **유일한** 경로다.

### Ⅱ. 플랫폼 기능

**5. Tenant · Queue · API Key 관리**

| § | 제목 | 상태 |
|---|---|---|
| §4 | API Key 설계 (SHA-256, Revoke) | ✅ |
| §43 | Queue 삭제 흐름 (DRAINING → DELETED) | ✏️ |
| §50 | Tenant status 확장 | ✅ |
| §51 | Queue update 전략 (name만 변경 허용) | ✏️ |
| §52 | Queue delete는 PAUSED에서만 | ✅ |
| §55 | API Key prefix `sk_live_` | ✅ |

**6. 인증 · 인가 · 남용 방지**

| § | 제목 | 상태 |
|---|---|---|
| §42 | JWT 설계 (Access/Refresh, Rotation) | ✅ |
| §53 | PasswordHasher Port/Adapter 분리 | ✅ |
| §54 | JWT를 api 계층에 배치 (→12) | ✅ |
| §60 | Rate Limiter 알고리즘 선택 (Token Bucket) | ✅ |
| §61 | 알고리즘 분리 (Token Bucket + Fixed Window) | ✅ |
| §62 | Tenant Plan 도입 (SaaS 등급) (→5) | ✅ |
| §63 | RateLimitFilter HTTP 통합 | ✅ |
| §65 | 인증 전 Rate Limit — Burst 불허 | ✅ |

**7. 클라이언트 계약 · SDK**

| § | 제목 | 상태 |
|---|---|---|
| §28 | SDK 제공 계획 (초기 검토) | 📉 |
| §35 | SDK 설계 — **JS SDK만**, Tenant 서버용은 안 만든다 | ✏️ |
| §78 | 클라이언트 경계 — enqueue는 Tenant, polling은 Platform 직접 | ✅ |

### Ⅲ. 기반 기술 (횡단)

**8. Redis (키 · Lua · 배포 · 복구)**

| § | 제목 | 상태 |
|---|---|---|
| §5 | Redis 장애 복구 전략 | ✏️ |
| §20 | 메모리 압박 해결 | ✅ |
| §23 | Redis Key 설계 이유 (키표) | ✏️ |
| §30 | Master/Replica (Sentinel) 설계 | 📉 |
| §39 | RedisSyncJob 상세 흐름 | ✏️ |
| §64 | Lua Script Bean 등록 패턴 | ✅ |
| §66 | Redis Cluster 도입 결정 | ✏️ |
| §67 | 이중 라우팅 (Cluster + Hash Tag) | ✏️ |
| §68 | Master 크기 최적화 (Single Thread 병목) | 🚧 |
| §69 | 극대 분산 (4×4×4GB) | 🚧 |
| §75 | 배포 방식 확정 — 독립 2 Cluster + 큐 단위 이중 라우팅 | 🚧 |

**9. MySQL (스키마 · 인덱스 · 파티션 · 복제)**

| § | 제목 | 상태 |
|---|---|---|
| §2 | ID 전략 — 이중 ID 분리 | ✅ |
| §3 | DATETIME(3) 전체 적용 | ✅ |
| §6 | tenantId 비정규화 (tokens) | ✅ |
| §7 | 인덱스 설계 근거 | ✅ |
| §16 | 대용량 처리 — DB | ✅ |
| §26 | DB 파티셔닝 전략 (월별 Range) | ✅ |
| §29 | MySQL Read/Write 분리 | ✅ |
| §37 | schema/entity 개선사항 | ✅ |
| §41 | HikariCP 커넥션 풀 계산 | ✅ |
| §44 | 파티션 유예 전략 (월말 걸친 토큰) | ✅ |
| §46 | LazyConnectionDataSourceProxy 필수 | ✅ |
| §48 | schema.sql 수동 관리 | ✅ |
| §83 | **파티션 키를 바꾸지 않는다** — 범위 조건 프루닝 실패의 실측과 4개 안 비교 | ✅ |
| §84 | **BillingSnapshotJob** — 매일 2개월 재집계 · `PARTITION` 절 · READ COMMITTED (실측이 뒤집은 것 5개) | ✅ |
| §85 | **큐 Redis 키 purge 배치를 만들지 않는다** — 전제 3개가 전부 뒤집힌 기록 | ✅ |

**10. Kafka · 배치 · 비동기**

| § | 제목 | 상태 |
|---|---|---|
| §32 | Kafka 도입 설계 (버퍼 · 상태 이벤트) | ✏️ |
| §40 | Kafka Consumer 설정 상세 | ✏️ |

> 적재 경로의 현행 결정은 **§73**(→1)이다. 순서·복구는 §71, 폐기된 중간 세대는 §72.

**11. 동시성 · 확장성**

| § | 제목 | 상태 |
|---|---|---|
| §18 | 대용량 처리 — 로직 | ✅ |
| §19 | 대용량 처리 — 병렬 처리 (ZREM 분산 논거 폐기) | ✏️ |
| §24 | 실서비스 대용량 처리 문제 및 해결 | ✅ |
| §27 | 수평 확장 설계 (Stateless) | ✅ |
| §57 | 동시성 제어 우선순위 정책 | ✅ |
| §58 | Queue 생성 동시성 처리 (→5) | ✅ |
| §59 | `@DistributedLock` 도입 및 모듈 배치 | ✅ |

**12. 아키텍처 · 모듈 · 기술 스택**

| § | 제목 | 상태 |
|---|---|---|
| §1 | 기술 스택 — Spring MVC + Virtual Thread + JPA | ✅ |
| §8 | Rich Domain Model + Hexagonal | ✅ |
| §25 | WebFlux → MVC + Virtual Thread 전환 | ✅ |
| §45 | Gradle 멀티모듈 + Virtual Thread 전략 | ✅ |
| §47 | JpaConfig를 infrastructure에 배치 | ✅ |
| §49 | Adapter 네이밍 `xxxJpaAdapter` | ✅ |
| §56 | GlobalExceptionHandler를 api에 배치 | ✅ |

**13. 시각 · 시간대**

| § | 제목 | 상태 |
|---|---|---|
| §76 | `tokens`는 UTC, 나머지는 KST (통일 안 함) | ⛔ |
| §77 | 시각을 전부 UTC로 통일 (§76을 대체) | ✅ |

---

## §1 기술 스택 결정 — Spring MVC + Virtual Thread + JPA

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

업데이트 VT (admit/complete):
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

## §2 ID 전략 — 이중 ID 분리

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

## §3 DATETIME(3) 전체 적용

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

## §4 API Key 설계

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

## §5 Redis 장애 복구 전략

> ✏️ **복구 절차가 §71 D12·D13에서 재설계됐다.** 아래 표의 `global-seq`(→ `queue:{queueId}:seq`)와
> `queue-user 역인덱스`(→ `queue:{queueId}:tokens` Hash)는 폐기된 키다. 순서 복구도 `issued_at` 밀리초를
> score로 쓰는 근사 복구가 아니라 **DB `tokens.seq`를 그대로 score로 복원**한다(§70 D9로 seq가
> 단조증가·유일해졌기 때문). "DB가 원본" 원칙과 복구 불가 항목(비활동 TTL) 판정은 유효하다.
> ⛔ 아래 표의 **`avgWaitingTime` 행은 §81이 폐기**했다 — 값 자체를 만들지 않으므로 복구 대상이 아니다.

### 복구 가능 항목

| 항목 | 복구 여부 | 방법 |
|------|----------|------|
| WAITING 토큰 목록 | ✅ 완전 복구 | DB tokens WHERE status=WAITING |
| 큐 설정 | ✅ 완전 복구 | DB queues 테이블 |
| 대기열 순서 | ✅ 사실상 완전 | issued_at.toEpochMilli() → score |
| userId 역인덱스 | ✅ 재구성 가능 | WAITING 토큰에서 재구성 |
| global-seq | ⚠️ 근사 복구 | 최대 score를 seq로 설정 |
| 비활동 TTL | ❌ 복구 불가 | 전원 inactiveTtl 리셋 |
| ~~avgWaitingTime~~ | ⛔ **폐기 (§81)** | 값을 만들지 않는다 — 복구 대상 아님 |

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
> 비활동 TTL은 복구 불가하며 inactiveTtl 리셋으로 처리합니다."
> (⛔ 초판의 "avgWaitingTime은 ETA null로" 부분은 **§81이 폐기**했다 — 두 값 다 만들지 않는다)

---

## §6 tenantId 비정규화 (tokens 테이블)

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

## §7 인덱스 설계 근거

| 인덱스 | 대상 쿼리 |
|--------|----------|
| `token_id + status` | Polling 인증 — 가장 빈번 (2,000 rps) |
| `queue_id + status + issued_at` | Batch TTL 만료 탐색 (10초 주기) |
| `queue_id + user_id + status` | userId 중복 체크 보조 |
| `key_hash` (unique) | API Key 인증 DB fallback |
| `tenant_id + name` (unique) | Tenant 내 큐 이름 중복 방지 |
| `tenant_id + status` (queues) | 활성 큐 목록 조회 |

---

## §8 Rich Domain Model + Hexagonal Architecture

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
| ~~`Token.complete()` `expire()` `returnToWaiting()` `waitingSeconds()`~~ | — | 🔴 **초판 구상. 존재하지 않는 메서드다.** §80이 전이 강제를 도메인에서 **Kafka 소비 측 UPSERT 가드**로 옮겼다. `Token`의 실제 public 메서드는 `issue`·`transition`·`reconstruct` 셋뿐이다 |
| `Queue` | `isEnqueueable()` | ACTIVE 상태 판단 |
| `Queue` | `isCapacityExceeded(count)` | maxCapacity 초과 판단 |
| ~~`Queue`~~ | ~~`assignSlice(seq)`~~ | **폐기 (§66 D2 — ZSet 하나).** 존재하지 않는 메서드다 |
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

## §9 Admit = Dequeue + 통계 갱신

> ✏️ **§12가 admit 방식을 전면 변경했다.** 아래는 `Admit → DB COMPLETED` 즉, **admit이 곧 입장 완료**이던
> 시절의 그림이다. 지금은 `admit → ADMIT_ISSUED + admitToken(TTL 60s)` → Tenant `verify` → `complete`에서야
> COMPLETED가 된다(§12·§13·§22). **"DB 먼저 → ZREM 나중"이라는 순서 원칙은 그대로 유효**하며,
> 그 근거(잔류가 유실보다 안전)가 이 절의 살아 있는 부분이다.

### 결정
별도 Dequeue API 없음. Admit 한 번에 세 가지 처리.

```
Admit ──┬──▶ DB COMPLETED  (상태 확정 — 먼저)
        ├──▶ Redis ZREM    (Dequeue — 나중)
        └──▶ ~~avgWaitingTime 갱신 (통계 — 마지막)~~  ⛔ **폐기 (§81)**
```

> ⛔ **세 번째 단계는 §81이 폐기했다.** 남는 순서는 **DB 먼저 → Redis 정리 나중** 둘이다.
> Redis 정리 안에서의 순서(§80 구현 결과 ①의 `HDEL` 마지막)는 그와 별개로 지켜야 한다.

### 순서가 중요한 이유

```
ZREM 먼저 → DB 실패:
  대기열에서 제거됐는데 WAITING 상태 → 유저 영원히 대기 ❌

DB 먼저 → ZREM 실패:
  COMPLETED 기록됨 → Batch 10초 내 ZREM 재실행 (멱등) ✅
  Tenant 입장: 유저 이미 입장 허용 → 서비스 이용 중 → 피해 없음
  Platform 입장: Sorted Set에 잔류 → 10초 내 정리

~~avgWaitingTime 마지막인 이유:~~   ⛔ 폐기 (§81 — 값도 ETA도 만들지 않는다)
  Admit 확정 후에야 정확한 대기시간(issuedAt ~ completedAt) 계산 가능
  다음 유저 ETA 계산에 사용 → 실제 Admit 데이터만 반영해야 정확
```

---

## §10 rank=1 중복 불가 보장

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
→ 나머지 → 404 INVALID_ADMIT_TOKEN (TK002)
```

> ✏️ **초판의 `409 QE_006_INVALID_STATUS`는 틀렸다(2026-08-20).** complete는 실패 원인을
> 구분하지 않고 전부 **404 `TK002`**로 답한다 — 상태 불가·admitToken 불일치·유효 창 초과 중
> 어느 쪽이든 **Tenant가 할 일이 같기 때문**이다(§80, `FRS §6.6`). `QE_006`은 `ErrorCode`에
> 없고, 남은 후보였던 이탈(`DELETE /tokens/:tokenId`)은 **§82가 폐기**해 이제 **쓰이는 곳이 없다**.

### 면접 포인트
> "정상 흐름에서 rank=1은 항상 1명입니다.
> ZREM 실패 시 일시적 잔류가 가능하지만
> DB UPDATE WHERE status=1로 complete 동시성을 제어하고
> Batch 10초 내 자동 정리되므로 실제 피해는 없습니다."

---

## §11 재입장 재시도 로직

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

## §12 Admit 방식 전면 변경

### 변경 방식
```
Tenant → Platform POST /queues/:queueId/admit { count: N }
Platform → 앞 N명 입장 토큰(admitToken) 발급 (TTL 60초)
유저 → Polling으로 admitToken 수신
유저 → Tenant에 admitToken 전달
Tenant → Platform POST /queues/:queueId/admit-tokens/:admitToken/verify
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

## §13 입장 토큰(admitToken) 설계

> ✏️ **아래 "만료 시 우선순위 유지(WAITING 복귀)"는 폐기됐다 — §36(2026-08-21).**
> 만료는 그대로 끝이고 재접속 → 재-enqueue → 맨 뒤다. **TTL 60초와 그 시간 예산
> (폴링 수령 + 네트워크 + 유저 행동 ≈ 10초 미만)은 그대로 유효**하며, §36이 폐기 근거의
> 분모로 그 예산을 쓴다.

> ✏️ **P1-③의 `verified-token` 플래그는 §80이 폐기했다**(아래 해당 절 배너 참조).
> TTL 60초·발급 구조·admitToken이 곧 입장 자격이라는 성격은 그대로 유효하다.

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

## §14 admit 요청 순서 보장 — Kafka

> ⛔ **§80(2026-08-17)이 닫았다 — 명령 토픽을 만들지 않는다.** admit은 **동기 처리**이므로
> 전달할 명령 자체가 없다. `enqueue-admit` 토픽도, `AdmitConsumer`도, `admit_requests` 테이블도
> 만들지 않는다(§80이 테이블까지 폐기했다).
> **순서 문제는 여전히 실재하지만 해법이 다르다** — Kafka 파티션이 아니라 **DB의 조건부 UPSERT**
> (`IF(status = 0, 1, status)` 계열)로 막는다. 프로듀서가 여러 WAS라 브로커 도착 순서 자체를
> 신뢰할 수 없기 때문이다(§80 Rationale ③). 아래 본문은 그 이전의 설계 기록이다.

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

## §15 Token 상태 추가 — ADMIT_ISSUED

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

## §16 대용량 처리 — DB

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

## §17 대용량 처리 — Redis

> ✏️ **"Bulk Worker 항상 활성"(분기 없음)은 유효하며 §70이 재확인했다.** 다만 아래 두 가지는 폐기됐다:
> **① `슬라이스별 ZADD multi-member`** → 대기열은 ZSet 하나다(§66 D2). **② `INCRBY N → seq 블록 채번`**
> → 항목마다 `INCR queue:{queueId}:seq`로 발급한다(§70 D9).
> 상수도 실제와 다르다 — 코드는 `MAX_DRAIN=5000` / `CHUNK_SIZE=500` / `fixedRate=1000ms`
> (`BatchProcessor.java`). 아래 `500건 / 10ms`는 원안이며 **재조정은 후속 과제**다(§70).

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

## §18 대용량 처리 — 로직

### 멱등성 — Redis idempotency key
```
채택: Redis idempotency key
  SET queue:{queueId}:admit-idem:{requestId} {result} EX 300 NX
  → 이미 처리된 requestId → 저장된 결과 반환
  → 멀티 서버 보장
  ⚠️ requestId는 Tenant가 정하는 값이다. 큐 스코프 없이 전역 네임스페이스에 두면
     Tenant B의 "req_1"이 Tenant A의 저장된 결과(admitToken 목록)를 받는다.
```

### 비동기 INSERT 유실
```
Kafka At-Least-Once 보장:
  Consumer 장애 → Offset 미커밋 → 재시작 시 재처리
  DB UNIQUE KEY → 중복 INSERT 자동 방어
```

### ~~ADMIT_ISSUED → WAITING 복귀 (seq 복원)~~ — 🔴 폐기 (§36, 2026-08-21)

> 만료자는 `waiting`으로 돌아오지 않는다. claim 잡이 `HDEL tokens`로 게이트를 풀고 `EXPIRED`를
> 발행하며, 유저는 재접속해 새 seq로 맨 뒤에 선다. 아래는 폐기된 초판 서술이다.
```
문제:
  admitToken TTL 60초 초과 → WAITING 복귀
  Redis ZADD 시 원래 seq(score) 필요

해결:
  tokens 테이블에 seq 컬럼 저장 ✅
  Enqueue 시 INCRBY로 받은 seq → DB 저장

복구 흐름:
  Batch: EXISTS queue:{queueId}:admit-by-token:{tokenId} = 0 감지
  DB SELECT WHERE status=ADMIT_ISSUED AND tokenId=?
  → seq 조회
  → Redis ZADD queue:{queueId}:waiting {seq} {identifier}
  → DB UPDATE status=WAITING
```

---

## §19 대용량 처리 — 병렬 처리

> ✏️ **①의 해결책("슬라이스별 분할 처리 → 슬라이스 3개 → Lua 1회당 ~333건")은 성립하지 않는다.**
> 대기열은 ZSet 하나다(§66 D2). ZREM 대량 처리의 블로킹 문제는 **여전히 열려 있고**, 분산이 아니라
> 청크 크기로 다뤄야 한다 — admit 착수(Sprint 7)의 입력값이다.
> **②(DB UPDATE 100건 청크 + 10ms 양보)는 유효하다.**

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
  SET queue:{queueId}:admit-by-token:{tokenId} × 1000
  SET queue:{queueId}:admit-by-admit:{admitToken} × 1000
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
        // 키 조립은 QueueKeys를 거친다 — 해시태그가 빠지면 Cluster에서만 깨진다 (§75 D26)
        byte[] tokenKey = QueueKeys.admitByToken(queueId, r.tokenId()).getBytes();
        byte[] admitKey = QueueKeys.admitByAdmit(queueId, r.admitToken()).getBytes();

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

## §20 메모리 압박 해결

```
inactiveTtl 기본값: 300s (5분 무응답 = 사실상 이탈)
Batch 주기: 10초 (EXPIRED 토큰 메모리 점유 최소화)
Redis maxmemory: 4GB / maxmemory-policy: noeviction
```

---

## §21 이탈 정책

> ✏️ **이 절은 §82가 대체한다.** 아래 초판 설계는 `DELETE /tokens/:tokenId`(Cancel API)를 전제로
> 했으나, **그 API를 만들지 않기로 확정**했다(§82). 이탈은 전부 `inactiveTtl` 판정 배치가 잡고
> 결과 상태는 **EXPIRED(4)** 다. `CANCELLED(3)`에 도달하는 경로는 **하나도 남지 않는다.**
> 기록으로 남기는 이유는 "왜 취소를 별도로 두지 않는가"의 출발점이 여기이기 때문이다.

```
❌ 폐기된 초판 설계 (§82 이전)
이탈 허용 상태:
  WAITING      → CANCELLED ✅
  ADMIT_ISSUED → 409 QE_006_INVALID_STATUS ❌

ADMIT_ISSUED에서 이탈하려면:
  admitToken TTL 60초 대기
  → WAITING 자동 복귀
  → DELETE /api/v1/queues/:queueId/tokens/:tokenId → CANCELLED
```

```
✅ 확정 (§82)
이탈 방법은 하나다 — 폴링을 멈춘다.
  WAITING → (last-active가 inactiveTtl 초과) → 배치가 ZREM + HDEL → EXPIRED ✅
  ADMIT_ISSUED → admitToken TTL 60초 만료 → EXPIRED(§36). 복귀하지 않는다 —
                 재접속 → 재-enqueue → 맨 뒤

명시적 이탈 요청 엔드포인트는 없다. 유저가 취소 버튼을 누르든 탭을 닫든,
브라우저가 폴링을 멈추는 것으로 신호는 동일하다.
```

**`inactiveTtl`은 청소 주기가 아니라 유예 창이다.** 그 안에 돌아와 같은 identifier로 재-enqueue하면
`enqueue_bulk.lua`의 `HSETNX` 게이트가 `EXISTS`를 돌려주어 **기존 `tokenId`·`seq`·`rank`가 그대로
복원**된다. 배치가 `HDEL`로 그 필드를 지운 뒤에 돌아오면 신규로 판정되어 맨 뒤에 선다.
그래서 이 값은 **"몇 초까지 자리를 지켜줄 것인가"** 이고, Tenant가 큐마다 정한다
(`QueueCreateRequest.inactiveTtl`, 기본 300초).

🔴 **이 창은 두 전제 위에 선다** — ⓐ Tenant의 `identifier` 매핑이 브라우저 종료를 견디고,
ⓑ 브라우저가 `tokenId`·`seq`를 복원해 폴링을 이어갈 수 있어야 한다. 둘 중 하나라도 깨지면
돌아온 사람을 알아보지 못해 **창이 있어도 자리를 못 찾아준다.** 둘 다 Tenant·SDK 책임이고
**`FRS_final.md` §6.2 "세션 경계 3종"** 에 적혀 있다.

⚠️ **재-enqueue는 생존 신호가 아니다.** `enqueue_bulk.lua`는 `last-active`를 건드리지 않는다(KEYS는 `waiting`·`seq`·`tokens` 3종). 순번이 복원돼도 다음 `ka=1` 폴링이 오기 전에 배치가 돌면 그대로 회수된다. 창을 되살리는 유일한 신호는 **`ka=1` 폴링 재개**다.

---

## §22 verify / complete 분리

> ✏️ **분리는 유지된다(§80 확정).** 다만 아래 서술 중 `verified-token` 관련은 폐기됐다.
> §80이 verify의 Redis 쓰기를 **0으로** 만들었으므로 "verify는 상태 변경 없음"이 이제
> **문자 그대로 참**이다(이전에는 `SET verified-token`이라는 쓰기가 있었다).
> complete의 가드도 바뀐다 — `admit_token` + `status IN (0, 1)` + `admitted_at` 유효 창.

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

## §23 Redis Key 설계 이유

> ✏️ **아래 키표의 큐 상태 키는 폐기됐다.** `queue:{t}:{q}:{slice}`·`global-seq:{t}:{q}`(→ §66 D2·§70 D9),
> `queue-user:{t}:{q}:{userId}`(→ `queue:{queueId}:tokens` Hash), `token-last-active:{tokenId}`(→
> `queue:{queueId}:last-active` ZSet, §74). **현행 키 목록은 `FRS_final.md` §8**(v1.12)이고 조립은
> `QueueKeys.java`가 한다. 살아 있는 것은 **설계 원칙**(테넌트 격리·TTL 기준·원자성)과 각 키의 "선택 이유"다.
> 해시태그 `{queueId}` 요구는 §70 D10·§75 D26에서 추가됐다 — 이 절에는 그 개념이 없다.

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
| ~~`queue-stats:{t}:{q}`~~ | Hash | 없음 | ⛔ **폐기 (§81)** — 용도가 avgWaitingTime 하나뿐이었고 그 값을 만들지 않기로 했다 |
| `queue-user:{t}:{q}:{userId}` | String | waitingTtl | O(1) 중복 체크. TTL=waitingTtl로 대기 중 자동 보호. CANCELLED 시 즉시 DEL |
| `token-last-active:{tokenId}` | String | inactiveTtl | Key 존재 여부로 활동 감지. Polling마다 TTL 갱신. EXISTS=0이면 EXPIRED |
| `token-info:{tokenId}` | String | nextPollAfterSec+2s | Polling DB SELECT 대체. 상태 변경 시 즉시 갱신. 갱신 실패 시 DEL로 폴백 |
| `queue:{queueId}:admit-by-token:{tokenId}` | String | 60s | Polling 응답에 admitToken 포함용. tokenId→admitToken 조회 |
| `queue:{queueId}:admit-by-admit:{admitToken}` | String | 60s | verify/complete 시 admitToken→tokenId 조회 |
| `queue:{queueId}:admit-idem:{requestId}` | String | 300s | admit 중복 요청 멱등성. NX로 최초 1회만 처리. `requestId`가 **Tenant가 정하는 값**이라 큐 스코프 필수 |
| `verified-token:{tokenId}` | String | 60s | 중복 입장 방지. verify 후 admit 대상 제외. complete 시 DEL |
| `apikey:{keyHash}` | String | 60s | API Key 인증 DB 조회 대체. SHA-256 hash를 Key로 → rawKey 노출 방지 |
| `batch-lock:{t}:{q}` | String | 15s | Batch 서버 분산 시 큐별 처리 서버 지정. SET NX로 중복 처리 방지 |

> **제거된 Key**
> `queue-count:{t}:{q}` → ZCARD Pipeline으로 대체. 카운터 불일치 위험 제거
> `billing-count:{t}:{yyyyMM}` → tokens 원본 직접 집계로 대체. Redis 의존 제거

---

## §24 실서비스 대용량 처리 문제 및 해결

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
해결: verified-token 플래그          ← ⛔ 폐기 (§80, 2026-08-17)
  verify 시: SET verified-token:{tokenId} EX 60
  admit 시: verified 토큰 제외 + ZREM 정리     ← 이 줄이 존재 이유였다
  complete 시: DEL verified-token
```

> ⛔ **`verified-token`은 §80이 폐기했다.** 위 해결책의 심장은 **"admit이 verified 토큰을
> 제외한다"**인데, §80이 **admit에서 Redis 밖 조회를 전부 걷어냈다**(중간 DB 확인이 정상 대기자를
> 지우기 때문 — §71 D11). 읽는 곳이 사라진 플래그는 플래그가 아니다.
> **대체**: complete를 관대하게 만든다 — `WHERE token_id = ? AND admit_token = ? AND status IN (0, 1)` +
> `admitted_at` 유효 창. 중복 입장은 **`admit_token` 자체의 유일성**이 막는다.

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
    WHERE issued_at BETWEEN M월 AND M+1월    -- 상태 술어 없음 (§82)
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

#### ⑧ ~~avgWaitingTime ETA 왜곡~~ ⛔ **폐기 (§81)**

> 문제 자체가 사라졌다 — avgWaitingTime도 ETA도 만들지 않는다. 아래는 역사 기록이다.

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

## §25 Spring MVC + Virtual Thread 전환 (WebFlux → MVC)

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

## §26 DB 파티셔닝 전략

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

~~Partition Pruning: issued_at 조건으로 조회 → 해당 월 파티션만 스캔~~
  ⛔ 사실이 아니다 (2026-08-21 실측, §83)
```

> 🔴 **범위 조건으로는 프루닝이 걸리지 않는다.** 파티션식이 `YEAR(c)*100 + MONTH(c)`인데
> MySQL 옵티마이저가 이 식을 `issued_at`에 대해 단조라고 인식하지 못한다.
> `WHERE issued_at >= ... AND < ...`는 **13개 파티션을 전부 훑는다**(실측).
> 등치(`=`)로 바꿔도, 파티션식을 그대로 써도 마찬가지다.
>
> **파티셔닝을 유지하는 이유는 `DROP PARTITION`이지 프루닝이 아니다.** 프루닝이 필요한
> 월말 집계는 `FROM tokens PARTITION (p2026_04)`으로 파티션을 직접 지목한다
> (배치는 대상 월을 안다). 근거·대안 비교는 **§83**.

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
> 오래된 파티션을 DROP으로 빠르게 정리합니다.
> (⛔ 초판의 *"TokenExpiryJob이 Partition Pruning으로 해당 월만 스캔합니다"*는
> **사실이 아니다** — §83. 면접에서 말하지 마라. 파티셔닝의 값은 `DROP PARTITION`이고,
> 집계의 프루닝은 `PARTITION (pYYYY_MM)` 절로 따로 얻는다.)
> Polling SELECT는 Read Replica로 분산하고
> 인덱스는 최소화해 write 성능을 보호합니다."

---

## §27 수평 확장 설계

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

## §28 SDK 제공 계획

> ⚠️ **범위가 §35에서 좁혀졌다 (2026-08-12).** 아래는 "SDK가 있으면 좋은 이유"를 나열한
> 초기 검토이고, **Tenant 서버용 SDK는 만들지 않기로 확정**했다(언어를 하나 고르면 나머지
> 테넌트를 버리는 결정이 되므로). 브라우저용 JS SDK만 만든다. **현재 결정은 §35를 보라.**

### SDK가 필요한 이유 (초기 검토)
```
Tenant가 직접 구현해야 하는 것들:
  HTTP 클라이언트 설정
  X-API-Key SHA-256 해싱
  재시도 로직 (verify 순서 강제, complete 재시도)      ← 서버 쪽. §35에서 REST 명세 + 서버 방어로 이관
  nextPollAfterSec 타이밍 관리, 탭 비활성화 처리        ← 브라우저 쪽. JS SDK가 담당

→ Tenant마다 직접 구현 → 실수 가능성 높음
→ Platform 정책 변경 시 모든 Tenant가 수정
→ SDK가 정책을 코드 레벨에서 강제
```

**§35의 판단**: 위 네 줄 중 **아래 두 줄만 SDK로 강제할 실익이 있다.**
위 두 줄(서버 쪽)은 순서만 지키면 되는 단순 호출이라 명세로 충분하고,
대신 **서버가 위반을 방어**한다 ~~(verify 없이 complete가 오면 거절)~~.
> ✏️ **괄호 예시는 §80이 철회했다** — `complete`가 이미 `admitToken`을 검증하므로 거절할 근거가 없다.
> 원칙은 유지되고, 그 명세가 `TENANT_INTEGRATION.md`의 계약 5건이다.

---

## §29 MySQL Read/Write 분리 설계

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

## §30 Redis Master/Replica (Sentinel) 설계

> 📉 **Sentinel은 §75 D28에서 학습·로컬 자산으로 격하됐다(폐기는 아니다).** 목표 구성은
> **독립 2 Cluster + 큐 단위 이중 라우팅**이다(§75, 전환 시점 미정). 아래 쿼럼·`min-replicas-to-write`·
> Failover 실증은 **현재 로컬 구현 기준으로 여전히 사실**이다.

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

## §31 대용량 Enqueue 시나리오 분석

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

## §32 Kafka 도입 설계

> ✏️ **토픽 구성이 §73 D16·D18에서 바뀌었다.** `enqueue-events`/`enqueue-admit`/`token-status-changed`
> 3토픽 · 파티션 키 `queueId` → **단일 `token-lifecycle` · 키 `tokenId` · 18파티션**.
> `queueId` 키는 명시적으로 **기각**됐다 — 한 큐에 30만 명이 정상 시나리오라 트래픽 99%가 한 파티션에 몰린다.
> 아래 "202 즉시 응답"도 실제로는 **200**이다(순번을 확정한 뒤 응답한다, `FRS_final.md` §6.2).
> **Kafka를 쓰는 목적(버퍼 · At-Least-Once · DB 적재 비동기화)은 유효하다.**
>
> 🔴 **아래 `BillingConsumer` 예시의 집계 기준(`COMPLETED`만 카운트)은 폐기됐다.** 과금은 상태를
> 보지 않는다 — **정본은 §82**다. 이 절만 보고 구현하면 EXPIRED·WAITING이 통째로 무료가 된다.

### 도입 용도

```
① Enqueue 버퍼
   Redis Lua 즉시 처리 → 202 즉시 응답
   DB INSERT는 Kafka Consumer가 비동기 처리
   → Enqueue p99 50ms 이하 달성

② Token 상태 변경 이벤트
   COMPLETED / EXPIRED 시 발행
   → BillingConsumer: tokens 원본 집계 → billing_snapshots UPSERT

~~avgWaitingTime은 Kafka 없이 complete API에서 직접 갱신:~~   ⛔ **폐기 (§81)**
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
> (⛔ 초판의 마지막 문단 *"avgWaitingTime은 complete API에서 직접 HINCRBYFLOAT / ETA는 보조 정보"*는
> **§81이 폐기**했다. 면접 답변에서도 빼라 — 만들지 않은 것을 만들었다고 말하게 된다.)"

---

## §33 verify API 제거 검토 (v1.8) → v1.9에서 유지로 번복

> ✏️ **verify 유지 결론은 §80에서도 그대로다.** 단 아래 근거 중 `verified-token`에 기대는
> 부분은 폐기됐다(§80). verify가 남는 이유는 **"Tenant가 입장시키기 전에 토큰이 유효한지
> 물어볼 곳이 필요하다"** 하나로 좁혀졌다.

### v1.9 결정: verify 유지

```
verify API 유지 이유:
  verify: 유저가 admitToken 들고 왔을 때 (Tenant가 호출)
  complete: 실제 입장 처리 완료 후 (Tenant가 호출)

  둘을 합치면:
  → verify 없이 바로 complete → 입장 실패 시 복구 불가

verify DB Fallback 추가 (v1.9):
  Redis queue:{queueId}:admit-by-admit 미스 시
  DB admit_token 컬럼으로 안전하게 조회
  → Redis 장애 상황에서도 verify 정상 동작
```

---

## §34 admitToken TTL 만료 처리 (v1.8 EXPIRED → v1.9 WAITING 복귀 → **v1.15 EXPIRED 복귀**)

> 🔴 **2026-08-21 — v1.9의 "WAITING 복귀"를 폐기하고 v1.8의 EXPIRED로 돌아왔다.** 판정과 근거는
> **§36이 정본**이다. 이 절에는 TTL 값(60초)과 그 근거만 남는다.

### TTL 60초 (유지)

```
30초: 여유 부족 → 만료가 빈번
60초: 폴링 수령 지연(최악 20초, §79) + 유저 행동(10초 미만, §13)을 덮고도 30초 여유
```

### ~~v1.9 결정: WAITING 복귀 + seq 유지~~ — 폐기 (§36)

```
❌ 폐기된 근거
  seq DB 저장 → Redis ZADD score 복원 → 우선순위 보존
  다음 admit 호출 시 앞순서이면 재발급
```

폐기 이유는 **근거가 틀려서가 아니라 적용 대상이 틀려서다.** "우선순위를 보존한다"는 판단은
Platform이 **만료 원인을 구분할 수 있을 때만** 정당한데 구분할 수단이 없다 — 자세한 것은 §36.

`tokens.seq` 컬럼은 **그대로 유지한다.** 용도가 바뀔 뿐이다: 복귀 시 score 복원 → **Redis 전손 시
DB 재구성**(§71). 그쪽이 원래 주 용도였다.

---

## §35 SDK 설계 — JS SDK만 만든다. Tenant 서버용 SDK는 안 만든다

**갱신**: 2026-08-12 (Java SDK 폐기 결정의 근거를 명시). `FRS_final.md` v1.10의
"Java SDK 제거 (REST API 명세로 대체)"가 이 결정이다.

> 여기는 **"무엇을 만드나"**다. **"누가 어디로 요청하나"(enqueue 호출 주체, 브라우저 직접 enqueue
> 기각, 게임·네이티브 클라이언트)는 §78**을 보라. JS SDK의 범위가 §78에서 **폴링 + 대기 UI 전용**으로
> 확정됐다 — enqueue는 SDK에 넣지 않는다.

> ✏️ **§80이 이 절의 예시 하나를 철회했다.** 아래 Consequences의 *"verify 없이 온 complete는
> 서버가 거절한다"*(괄호 안 예시)는 **더 이상 참이 아니다.** 그 거절은 `verified-token` 플래그에
> 기대고 있었는데 §80이 그 키를 폐기했고, 애초에 **독립적인 실효가 없었다** — complete 자체가
> `admit_token`을 검증하므로 verify를 건너뛴 호출도 정당한 토큰을 가진 정당한 호출이다.
> **"Tenant 책임을 서버가 방어한다"는 이 절의 큰 원칙은 유지된다** — 방어 수단이 하나 줄었을 뿐이다.

### Decision

| 대상 | 제공 방식 |
|---|---|
| **브라우저 (대기자)** | **JS SDK** — 순수 바닐라 JavaScript |
| **Tenant 서버 (admit/verify/complete 호출자)** | **SDK 없음. REST API 명세만 제공** |

### 왜 Tenant 서버용 SDK를 안 만드나

**언어를 하나 고르는 순간 나머지 테넌트를 버리는 것이 된다.**

Java SDK를 제공하면 Java 테넌트만 편해지고 **Python·Go·PHP 테넌트는 아무 도움을 못 받는다.**
그렇다고 언어별로 만들면 SDK 수만큼 유지보수가 늘고, 정책이 바뀔 때마다 N개를 동시에 고쳐야 한다.
그 사이 버전이 어긋나면 **"어느 SDK를 쓰느냐에 따라 동작이 다른"** 최악의 상태가 된다.

REST API는 **언어를 가리지 않는다.** HTTP 클라이언트가 없는 언어는 없다.
명세를 정확히 쓰는 비용이, 언어별 SDK N개를 만들고 버리지 못하는 비용보다 싸다.

### 왜 브라우저는 SDK를 만드나

**브라우저는 언어가 하나다.** 바닐라 JavaScript 하나로 **모든 브라우저가 대응된다** —
프레임워크(React/Vue/Angular)에 묶이지 않으므로 테넌트의 프론트 스택과 무관하게 붙는다.
Tenant 서버 쪽의 "언어를 고르면 나머지를 버린다"는 문제가 여기엔 없다.

그리고 브라우저 쪽에는 **SDK가 아니면 반복될 실수가 실재한다**:

- `nextPollAfterSec` 준수 (안 지키면 Rate Limit 429 → 대기 실패)
- 탭 비활성화 시 폴링 중단 / 복귀 시 재개 (배터리·서버 부하)
- 네트워크 offline/online 처리
- `tokenId` 보관 (소유가 곧 자격이라 유실되면 순번을 잃는다)

이건 각 테넌트가 직접 구현하면 **거의 확실히 틀리는** 종류다. 서버 쪽 admit/verify/complete는
순서만 지키면 되는 단순한 호출이라 명세로 충분하다.

### Alternatives

**A. 언어별 SDK를 여러 개 제공 (기각)**
가장 친절하지만 유지보수가 곱해진다. 정책 변경 시 N개 동기화가 필요하고, 하나라도 뒤처지면
그 SDK 사용자만 다르게 동작한다. 1인 프로젝트가 감당할 범위를 넘는다.

**B. Java SDK만 제공 (기각 — 원래 계획이었다)**
개발자가 Java에 익숙하다는 이유였는데, **그건 플랫폼 사용자의 사정이 아니다.**
Python 테넌트가 붙으려는 순간 "SDK가 없으니 알아서 REST로 붙으세요"가 되는데,
그럴 거면 처음부터 REST 명세를 정본으로 두는 편이 정직하다.

**C. OpenAPI 스펙으로 클라이언트 자동 생성 (후속 검토)**
언어 중립이면서 코드를 얻는 절충안이다. 생성물 품질과 스펙 유지 비용을 따져봐야 하므로
지금은 채택하지 않되, REST 명세를 쓸 때 **OpenAPI로 옮기기 쉬운 형태**로 유지한다.

### Consequences

- **Tenant 서버 쪽 정책 강제 수단이 없다.** verify 순서, `complete` 재시도, `admitToken` TTL 60초
  준수를 SDK가 강제하지 못하므로 **명세에 그 제약이 명시돼야 하고**, 서버가 위반을 방어해야 한다
  ~~(예: verify 없이 complete가 오면 거절)~~. ✏️ **이 예시는 §80이 철회했다.**
  남은 실효는 **명세**뿐이며, 그 명세가 `TENANT_INTEGRATION.md`의 계약 5건이다
- `FRS_final.md`의 API 명세가 **사실상의 SDK**다. 응답 필드·에러 코드·재시도 규칙이 부정확하면
  그대로 테넌트 장애가 된다
- JS SDK는 별도 레포(`queue-platform-sdk-js`, Sprint 10)

### JS SDK 핵심 기능

```
QueueSDK.init() + startPolling():
  nextPollAfterSec 타이밍 자동 적용 (setTimeout 관리)
  탭 비활성화 → Polling 중단 (배터리/서버 부하 절약)
  탭 복귀 → 즉시 재개
  네트워크 offline/online 자동 처리
```

### 면접 포인트

> "SDK를 왜 JavaScript만 만들었나요?"

**Tenant 서버용 SDK는 언어를 하나 고르는 순간 나머지 테넌트를 버리는 결정**이 됩니다.
Java SDK를 주면 Python 테넌트는 대응이 안 되고, 언어별로 만들면 정책이 바뀔 때마다
N개를 동시에 고쳐야 해서 버전이 어긋납니다. REST API는 언어를 가리지 않으니
명세를 정확히 쓰는 쪽을 택했습니다.

반대로 **브라우저는 언어가 하나**입니다. 바닐라 JS면 프레임워크와 무관하게 모든 브라우저가
대응되고, 여기엔 SDK가 아니면 반복될 실수가 실재합니다 — `nextPollAfterSec` 미준수로 인한 429,
탭 비활성화 시 불필요한 폴링, `tokenId` 유실. 그래서 **강제할 실익이 있는 쪽에만** SDK를 뒀습니다.

대신 서버 쪽 정책은 **API 계약**이 대신합니다. ~~verify 없이 complete가 오면 거절하는 식으로요~~ —
✏️ 그 예시는 §80이 철회했습니다(`complete`가 이미 `admitToken`을 검증하니 거절할 근거가 없습니다).
남은 수단은 명세이고, 그래서 Tenant가 읽을 계약을 `TENANT_INTEGRATION.md`에 5건으로 못박았습니다.

---

## §36 admitToken TTL 만료 → **EXPIRED** (WAITING 복귀 폐기)

> 🔴 **2026-08-21 판정 — 복귀하지 않는다.** 만료되면 그대로 끝이고, 다시 줄을 서려면
> **재접속 → 재-enqueue → 맨 뒤**다. v1.9의 "seq 보존 WAITING 복귀"를 폐기한다.
> 폐기 검토는 에이전트 3인(설계·구현·반대심문) 교차 검토를 거쳤고, **반대심문이 낸 최강 논거
> 두 개가 실측으로 무너졌다**(재진입 불가 / 코드 순감).

### Decision

```
admit.lua        ZPOPMIN → waiting에서 빠진다
                 admitted ZSet에 "seq|identifier" (score = 만료 epoch ms)
                 tokens Hash 필드는 남는다 (중복 게이트)
   ↓ admitToken TTL 60초 경과
admit_expire.lua ZRANGEBYSCORE admitted 0 now   ← 이 EVAL 자체가 claim (§80 ⑧)
                 ZREM admitted
                 HGET tokens {identifier}       ← issuedAt 원본 확보. HDEL보다 먼저
                 HDEL tokens {identifier}       ← 🔴 여기가 바뀐 곳. 옛 판은 ZADD waiting이었다
TokenReclaimJob   EXPIRED 발행 (key = tokenId)

유저: SDK가 404를 받고 종료 → 재접속 → Tenant가 enqueue 호출 → 새 seq → 맨 뒤
```

### Rationale — Platform은 만료 원인을 구분할 수 없다

Platform이 아는 사실은 **"60초 안에 verify가 안 왔다"** 하나뿐이다. 원인은 넷이다.

| 원인 | 귀책 | Platform이 물어줄 이유 |
|---|---|---|
| 탭을 닫았다 | 유저 | 없다 |
| 네트워크가 끊겼다 | 불가항력 | 없다 |
| Tenant 서버가 느려 verify를 못 불렀다 | **Tenant** | 없다 — Backpressure Pull은 *"받을 수 있는 만큼만 뽑는다"* 가 Tenant 책임이다 |
| 폴링 간격 때문에 admitToken을 늦게 받았다 | **Platform** | ✅ 있었다 |

**v1.9는 네 번째 때문에 넷을 전부 봐줬다.** 그런데 네 번째는 실측하면 **이미 예산 안**이다.

| Tenant의 admit 방식 | 수령 지연 | 60초 중 남음 | 소요(§13 예산) |
|---|---|---|---|
| `count=100` 1회 (일반) | 2~5초 | 55초 | 10초 미만 |
| 루프로 5,000명 | 10초 | 50초 | 〃 |
| 루프로 10,000명+ (극단) | **20초** | **40초** | 〃 |

**최악에서도 4배 여유다.** Platform 귀책분이 사라지면 남는 셋은 전부 유저·Tenant 귀책이고,
**Platform이 대신 물어줄 근거가 없다.** 구분할 수 없어서 봐준다는 논리는, 봐줄 이유가 있는 항목이
사라진 뒤에는 성립하지 않는다.

⚠️ 위 표는 **pacing 사다리가 기본값일 때만** 참이다. `queue:{q}:pacing` 오버라이드로 "전원 간격
2배"를 걸면 최장 40초가 되어 여유가 12초로 준다. **`admitTtl > 최대 pacing 간격 + 지터`** 라는
부등식이 §79에 있다.

### 폐기가 닫는 것

1. **좀비 head-of-line 점유가 소멸한다.** v1.9에서는 이미 떠난 사람이 원래 seq(작은 값)로 복귀해
   큐 맨 앞에 돌아왔고, 약 70초(TTL 60 + 배치 10) 주기로 **무한 재순환**했다. 좀비 Z명, Tenant의
   사이클당 처리율 R명일 때 매 사이클 `min(Z, R)`의 admit 슬롯이 좀비에게 갔고 **Z ≥ R이면 정상
   입장자가 0에 수렴**했다. 이제 좀비는 한 번 뽑히고 끝이다 — **admit 자체가 좀비 청소기가 된다.**
2. **§79의 미해결 후속이 소멸한다.** "복귀 대기 중"이라는 상태 B가 없어지므로 그것을 판정할
   신규 `ErrorCode`가 불필요해진다. §79는 이를 *"ErrorCode만 추가해서는 아무도 던질 수 없다 —
   판정 수단부터 없다"* 며 미해결로 남겨뒀었다.
3. **`frontSeq` 비단조의 유일한 원인이 사라진다**(§79 Alternatives A). 결론(비용 때문에 `frontSeq`를
   안 쓴다)은 그대로지만 근거 한 줄이 정리된다.
4. **업계와 정렬된다.** Queue-it *"you need to start over"* · Cloudflare Waiting Room *"back of the
   queue"* · CrowdHandler(순번 미보장) 셋 다 "차례를 놓치면 맨 뒤"다.

### 검토 과정에서 무너진 반대 논거 2개 (기록)

**① "브라우저가 enqueue를 못 부르니 재진입 경로가 없다"** — `ApiKeyAuthenticationFilter:122`가
enqueue를 X-API-Key 필수로 잡는 것은 사실이다. 그러나 **재진입은 첫 진입과 같은 경로다** —
유저가 Tenant 페이지를 다시 열면 Tenant 서버가 enqueue를 부른다. Tenant는 이미 그 로직을 갖고 있다.
새로 필요한 것은 **SDK가 404를 받았을 때 재접속을 안내하는 것** 하나이고, SDK는 아직 한 줄도
없으므로(Sprint 10) **지금 계약에 넣으면 공짜다.**

**② "복귀를 없애면 코드가 228줄 준다"** — 이것도 틀렸다(폐기 **찬성** 측 논거였다). 아래 참조.

### 🔴 복귀를 없애도 인프라는 남는다 — `HDEL`의 주체가 필요하다

중복 게이트가 `waiting` ZSet이 아니라 **`tokens` Hash의 `HSETNX`** 이고,
**`HDEL tokens`의 유일한 경로가 `cleanupCompleted`**(`RedisQueueEngine:474`) 하나다.

노쇼는 complete를 안 하므로 필드가 영구 잔류한다. `admitted` ZSet을 지우면 그 사람의
`(seq, identifier)` 쌍을 아는 자료구조가 **0**이 된다 — `inactiveTtl` sweep도 못 잡는다
(`last-active`로 seq는 찾아도 identifier를 `waiting`에서 역산해야 하는데 그 사람은 `waiting`에 없다).

```
HDEL을 안 하면:
  재-enqueue → HSETNX 0 → EXISTS, rank -1, seq -1, 옛 tokenId (ZADD 미실행)
            → 그 tokenId로 폴링 → 404 → 영구 락아웃
```
identifier가 *"같은 사용자·같은 큐에는 항상 같은 UUIDv7"* 규약이라(§79) 그 사용자는 **그 큐에서
영구 퇴출**된다. 규약을 어기고 새 identifier를 쓰면 게이트를 우회해 **과금 2건**이다.

→ `admitted` ZSet · `admit_expire.lua` · `TokenReclaimJob` · `ExpiredAdmit` ·
`QueueEngine.claimExpiredAdmits` 포트는 **전부 남는다.** 실제 변경은 아래 셋뿐이다.

```
admit_expire.lua     ZADD waiting {seq} {id}  →  HDEL tokens {id}    (HGET이 먼저)
                     KEYS[2] waiting이 미사용이 되므로 KEYS 2개로 축소 + 호출부 1줄
TokenReclaimJob  RETURNED  →  EXPIRED  (발행 타입 1개)
TokenEventType.RETURNED + TokenJpaAdapter SQL 맵 엔트리     (삭제)
TokenEventType.CANCELLED + 맵 엔트리 · TokenStatus.CANCELED(3)  (삭제 — §82 ① 정정)
```

**🔴 `RETURNED`는 지운다.** 한때 "배포 순서 제약 때문에 남긴다"고 적었으나 번복한다.
**개념이 사라진 상수를 남기면 다음 사람이 "복귀가 있나?"를 다시 묻는다** — `RETURNED`는 문자
그대로 *"대기열로 다시 들어옴"* 이고, 그 일이 더는 일어나지 않는다.

배포 순서 제약은 실재하지만(컨슈머가 모르는 타입을 `from()`의 `null`로 판정해
`BatchListenerFailedException`으로 한 건씩 DLT로 보낸다) **이 프로젝트는 아직 프로덕션이 아니라
in-flight `RETURNED`가 존재하지 않는다.** 이론상 제약은 **운영 주의사항**으로 적으면 될 것이지
dead code로 남길 이유가 아니다.

> ⚠️ **운영 주의**: 이후 프로덕션에서 이벤트 타입을 없앨 때는 **발행 측(batch)을 먼저** 올린다.
> 소비 측을 먼저 올리면 아직 발행 중인 구 타입이 전부 DLT로 간다. 그리고
> `addNotRetryableExceptions(BatchListenerFailedException.class)`가 **아직 미결**이라, 안 붙은
> 상태면 DLT까지 가는 데 백오프를 다 태운다.

**같은 이유로 `TokenEventType.CANCELLED`와 `TokenStatus.CANCELED(3)`도 지웠다.**
🔴 **§82 ①이 남긴 근거("TINYINT 재번호 비용")는 틀렸다** — `TokenStatus`는 명시적 `statusCode`를
갖는 enum이라 `CANCELED(3)`을 지워도 `EXPIRED(4)`는 그대로 4다. 재번호가 아예 일어나지 않는다.
`status = 3`인 행은 한 번도 존재한 적이 없으므로(Cancel 미구현) `fromCode(3)`이 불릴 일도 없다.
**결번 3은 `schema.sql`의 `status` 컬럼 주석으로 남긴다** — 재사용 금지 정보가 필요한 곳은
enum이 아니라 DB 쪽이다.

⚠️ **`HDEL`을 admit 시점에 하는 우회는 금지다.** 그러면 admit된 사람이 새로고침 한 번으로 새
tokenId·새 seq를 받아 맨 뒤에 다시 선다(과금 2건 + `status = 1` 고아). 게이트를 `waiting`이 아니라
Hash로 둔 이유가 정확히 이것이다. **반드시 claim 잡에서 한다.**

### 🔴 구현 시 반드시 지킬 것

- **`EXPIRED` 가드를 넓히지 마라.** `TokenJpaAdapter:81`은 `IF(tokens.status = 0, 4, tokens.status)`라
  admit된 사람(`status = 1`)에게는 **no-op**이다. 즉 만료자의 DB status는 **`1`로 남는다.**
  `IN (0, 1)`로 넓히면 status가 4가 되고, 그러면 `complete`의 술어
  `status IN (0, 1)`(`TokenJpaRepository:64`)에 걸리지 않아 **`INVALID_ADMIT_TOKEN`** 이 된다.
  `COMPLETE_VALID_WINDOW_SECONDS = 300`이라 **60~300초 구간의 늦은 입장이 정상 경로로 실재**하고,
  그 사람은 Tenant가 이미 사이트에 들여보낸 사람이다. **status를 1로 두는 것이 그 경로를 살린다.**
- **`HGET`이 `HDEL`보다 먼저다.** `issuedAt` 원본을 못 실으면 `UNIQUE (token_id, issued_at)`에 충돌이
  안 나 **같은 토큰의 두 번째 행**이 생기고, 과금이 상태를 안 보므로(§82) 한 건 더 청구된다.
  `admit.lua`가 같은 이유로 `HGET`을 먼저 한다.
- **`last-active`는 여전히 건드리지 않는다.** 복귀가 없어져 리셋 논점 자체가 사라졌다.

### 남는 대가

**Tenant 서버 과부하 시 대량 탈락.** 60초 안에 입장 처리를 못 하면 뽑힌 전원이 만료된다.
v1.9에서는 복귀가 흡수했다. 이제는 흡수하지 않는다 — **그것이 Backpressure Pull의 정직한 귀결이다.**
Tenant는 `count`를 줄이거나 admit 호출 간격을 늘려 스스로 조절한다.

📌 **Platform이 admit 레이트를 별도로 제한하지는 않는다.** ⓐ 원칙 1(*Tenant가 슬롯·입장 제어*)
위반이고 ⓑ `rl:tenant:{id}` 단일 버킷이라 **admit을 몰아치면 자기 verify/complete가 굶는
self-limiting이 이미 작동**하며(별도 버킷을 주면 오히려 이 방어가 사라진다) ⓒ 위 실측대로
손실이 예산 안이다.

### Redis Key 구성

```
queue:{queueId}:admitted                    → ZSet. score = 만료 epoch ms, member = "seq|identifier"
                                               🔴 만료 감지 + HDEL 대상 특정의 유일한 자료구조
queue:{queueId}:admit-by-token:{tokenId}    → admitToken (Polling 응답용). PX = admitToken TTL
queue:{queueId}:admit-by-admit:{admitToken} → tokenId (verify/complete용). PX 동일

DB:
  tokens.admit_token   → verify의 DB Fallback 조회 기준
  tokens.seq           → Redis 전손 시 DB 재구성 (§71). 복귀용이 아니다
```
🔴 **`verified-token:{tokenId}`는 폐기됐다**(§80). 이전 판의 "유지" 목록에 남아 있던 것을 삭제했다 —
같은 절의 배너가 폐기라고 적어놓고 목록에는 남아 있어 서로 모순이었다.

### Related

- **§34** — TTL 값(60초)의 근거. v1.9 "WAITING 복귀"는 이 절이 폐기한다
- **§80** (Sprint 7 Admit) — `admitted` ZSet + claim-Lua 트리거. `EVAL` 자체가 claim이라 leader
  election을 쓰지 않는다(⑧). **claim 잡은 그대로 살아 있고 마지막 동작만 바뀐다**
- **§79** (폴링 계약) — 상태 B와 그 미해결 `ErrorCode`가 **이 폐기로 소멸한다.**
  pacing 사다리와 TTL의 결합(수령 지연) 실측도 그쪽에 있다
- **§82** (Cancel 폐기) — 이탈 회수를 `inactiveTtl` sweep 하나로 모은 결정. **좀비의 상한을 주던
  역할이 이 절의 폐기로 상당 부분 불필요해졌다** — 좀비는 admit이 지나가면 정리된다
- **§13** (유저 행동 시간 예산) — 위 "4배 여유" 계산의 분모
- **§71** (Redis 전손 복구) — `tokens.seq`의 주 용도

---

## §37 schema/entity 개선사항 (v1.9)

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
     Redis queue:{queueId}:admit-by-token 미스 시 DB Fallback
  2. verify DB Fallback 시 조회 기준
     (issued_at 60초 이내 + admit_token 일치 확인)

complete 후에도 컬럼 값 유지:
  불필요한 UPDATE 제거 → write 부하 감소
```

---

## §38 FLOW 개선사항 (v1.9)

> ✏️ **`nextPollAfterSec`·`globalRank`는 §79(2026-08-14)가 폐기했다.** 서버가 개인별 간격을 계산해
> 내려주던 방식 → `/status`가 `pacing` 구간표를 내려주고 **SDK가 `rank = mySeq − lastAdmittedSeq`로
> 계산**한다. 아래 `token-info` TTL(`nextPollAfterSec + 2s`)도 그 필드에 매여 있어 §79 이후 재정의 대상이다.
> **아래 `ZCARD slice:N` 합산은 §66 D2가 폐기했다** — 대기열은 ZSet 하나(`queue:{queueId}:waiting`)다.

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
  queue-count 카운터 불일치 위험 (EXPIRED 시 DECR 누락 가능)
  ZCARD는 Sorted Set의 실제 크기 → 항상 정확

Pipeline:
  ZCARD slice:0, ZCARD slice:1, ZCARD slice:2
  → 한번의 네트워크 왕복으로 합산
```

### verify DB Fallback

```
Redis queue:{queueId}:admit-by-admit 미스 시:
  DB SELECT WHERE status=ADMIT_ISSUED
               AND admit_token=?
               AND issued_at > UTC_TIMESTAMP(3) - INTERVAL 60 SECOND
               -- UTC_TIMESTAMP()를 쓴다: 시각 컬럼은 전부 UTC다(§77). 앱 세션은 +00:00이라
               -- NOW()도 UTC지만, 서버 default-time-zone이 아직 +09:00이라 mysql CLI로
               -- 같은 쿼리를 돌리면 NOW()가 KST다. UTC_TIMESTAMP()는 어느 경로에서도 같다.

이유:
  Redis 장애 또는 TTL 경계에서 캐시 미스 가능
  DB admit_token 컬럼으로 안전하게 fallback
```

---

## §39 RedisSyncJob 상세 흐름

> ✏️ **아래 슬라이스 계산·키·멤버는 폐기됐다** (§66 D2 · §70 D9 · §74). 본문의 흐름(①~⑤)과
> "ZADD NX로 멱등" 원칙은 유효하되, **재삽입 대상 키·멤버는 아래 정정된 표기를 따라라.**
> `queue-user` 역인덱스 재구성(③)도 `queue:{queueId}:tokens` Hash 재구성으로 읽어야 한다.

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
   ZADD queue:{queueId}:waiting {seq} {identifier} NX
   NX: 이미 있으면 무시 (멱등)
   ⚠️ 정정(2026-08-17): 원문은 `ZADD queue:{t}:{q}:{slice} {seq} {tokenId}` 였다 —
      키와 멤버가 둘 다 틀렸다. 멤버가 tokenId면 poll_verify.lua의
      HGET tokens[member]가 nil이라 그 토큰은 폴링에서 항상 404다
      (enqueue_bulk.lua:65가 identifier를 멤버로 쓴다).

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
                // ⚠️ 정정(2026-08-17): 원문은 token.getSliceCount() 와
                //    RedisKeyFactory.queue(tenantId, queueId, slice) 를 호출했다 —
                //    둘 다 존재하지 않는 API다. 큐 키는 QueueKeys가 조립한다.
                String key = QueueKeys.waiting(token.getQueueId());
                redisTemplate.opsForZSet()
                    .addIfAbsent(key, token.getUserId(), token.getSeq());  // member = identifier

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

## §40 Kafka Consumer 설정 상세

> ✏️ **소비 주체와 토픽이 §73에서 바뀌었다.** `TokenEnqueueConsumer`(queue-batch 안) → **독립 모듈
> `queue-consumer`의 `TokenLifecycleConsumer`**(D20), 토픽 `enqueue-events` → `token-lifecycle`.
> 발행 측 시한(`max.block.ms` 등)은 §73 D19가, 파티션·복제는 D17이 정본이다.
> **수동 커밋을 쓰지 않는 이유**는 이 절이 아니라 `TokenLifecycleConsumer` javadoc에 있다.
> 배치 소비·멱등 insert·실패 2분류(제약 위반 vs 일시 장애)라는 골격은 유효하다.

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

## §41 HikariCP 커넥션 풀 계산 근거

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

## §42 JWT 설계

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

## §43 Queue 삭제 흐름 (DRAINING → DELETED)

> ✏️ **아래 "AdmitConsumer Queue 상태 체크"는 §80이 폐기했다** — AdmitConsumer도 `admit_requests`도
> 없다(동기 처리). 큐 상태 확인은 admit API가 ①단계에서 `queues` 행을 읽을 때 함께 한다.
>
> ✏️ **③의 DEL 대상 키 목록이 낡았다.** `queue:{t}:{q}:0 ~ {sliceCount-1}`·`global-seq:{t}:{q}` →
> 현행은 `queue:{queueId}:waiting|seq|tokens|last-active` 4종이다(`QueueKeys`, §66 D2·§70 D9·§74).
> `queue-stats:{t}:{q}`도 목록에서 빠진다 — **§81이 키 자체를 폐기**했다.
> **상태 전이(DRAINING → DELETED)와 "DB 먼저 → Redis 나중" 순서는 유효하다.**

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

## §44 파티션 유예 전략 (월말 걸친 토큰 보호)

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
  completedAt 기준 집계 → 대상 파티션을 특정할 수 없음 → 전체 풀스캔
  issued_at 기준 집계 → 대상 월을 특정할 수 있지만 월말 걸침 문제 발생
  ※ "issued_at이면 Pruning 가능"은 사실이 아니다 (§83). 특정은 배치가 하고,
    스캔 범위 축소는 PARTITION 절이 한다
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
  → FROM tokens PARTITION (p2026_04) 로 M월 파티션만 스캔 (§83)
    범위 조건만으로는 프루닝이 안 걸린다
  ON DUPLICATE KEY UPDATE id = id (멱등)

Step 2: billing_snapshots 집계
  SELECT COUNT(*) FROM tokens
  WHERE issued_at BETWEEN M월 AND M+1월    -- 상태 술어 없음 (§82)
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
| 집계 방식 | `FROM tokens PARTITION (pYYYY_MM)`으로 대상 월 지목 (§83) |

### 면접 포인트
> "Queue가 월말에 걸쳐 운영되면
> issued_at 기준 파티션에서 complete가 다음 달에 발생할 수 있습니다.
> 당월 말 바로 DROP하면 아직 WAITING인 토큰이 소멸되어
> 과금 누락이 발생합니다.
> 1달 유예를 적용해 M월 파티션을 M+2월 초에 DROP함으로써
> 월말 걸친 토큰이 complete될 때까지 보존합니다.
> 스토리지는 약 2배지만 B2B 과금 정확도가 더 중요합니다."

---

## §45 Sprint 1 — Gradle 멀티모듈 + Virtual Thread 전략

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

## §46 Sprint 2 — LazyConnectionDataSourceProxy 필수 적용

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

## §47 Sprint 2 — JpaConfig를 infrastructure 모듈에 배치

### 결정
@EnableJpaRepositories + @EntityScan을 queue-infrastructure의 JpaConfig.java에 배치.

### 근거
- queue-api에 spring-boot-starter-data-jpa 의존성 추가 불필요 (헥사고날 원칙)
- JPA는 infrastructure의 관심사
- scanBasePackages="com.sonix.queue"가 infrastructure의 JpaConfig를 자동 스캔

---

## §48 Sprint 2 — schema.sql 수동 관리 (ddl-auto 미동작 대응)

### 문제
LazyConnectionDataSourceProxy 환경에서 Hibernate ddl-auto=update가 테이블을 생성하지 않음.
커넥션 획득이 지연되어 DDL 실행이 스킵되는 알려진 이슈.

### 결정: schema.sql 수동 실행
- ddl-auto=update 유지하되, 실제 테이블 생성은 schema.sql로 수동 관리
- Sprint 4 이후 스키마 안정화 시 Flyway 도입 예정

---

## §49 Sprint 3 — Adapter 네이밍 xxxRepositoryImpl → xxxJpaAdapter

### 결정
TenantRepositoryImpl → TenantJpaAdapter로 네이밍 변경.

### 근거
- "Impl"은 "단순 구현체"처럼 보여 헥사고날의 Adapter 역할이 안 느껴짐
- "JpaAdapter"는 "JPA를 사용하는 어댑터"라는 역할이 명확
- 인프라 교체 시 네이밍이 자연스러움: TenantJpaAdapter → TenantMyBatisAdapter → TenantMongoAdapter

---

## §50 Sprint 3 — Tenant status 확장 (FRS에 없는 필드)

### 결정
FRS의 tenants 테이블에 status 컬럼이 없지만, ACTIVE(0)/DEACTIVATED(1) 상태를 추가.

### 근거
- 실무에서 Tenant 비활성화 없는 SaaS는 거의 없음 (계정 정지/탈퇴 처리)
- schema.sql에 `status TINYINT NOT NULL DEFAULT 0` 추가
- 면접에서 "FRS에 없던 건데 왜 추가했나?" → "계정 관리에 필수라 확장했습니다"

---

## §51 Sprint 3 — Queue update 전략 (name만 변경 허용)

> ✏️ **근거 하나가 사라졌다.** "maxCapacity 변경 → sliceCount 변경 → 슬라이스 파티셔닝 정합성 붕괴"는
> §66 D2(ZSet 하나)로 **성립하지 않는다.** 남은 근거는 TTL 소급 적용 문제와 "운영 중 정원 변경은
> 대기자 기대를 깬다"는 쪽이다. **결정(name만 변경 허용) 자체는 유지**하되 근거는 이 점을 감안해 읽어라.

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

## §52 Sprint 3 — Queue delete는 PAUSED 상태에서만 허용

### 결정
기존 DRAINING/PAUSED 둘 다 허용 → PAUSED에서만 허용으로 변경.

### 근거
- ACTIVE에서 삭제하면 대기자가 즉시 소실
- 정지(PAUSED) → 대기자 처리 → 삭제 흐름을 강제
- DRAINING 상태 처리는 Sprint 5 이후 Redis 레벨에서 처리

---

## §53 Sprint 4 — PasswordHasher Port/Adapter 분리 (BCrypt)

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

## §54 Sprint 4 — JWT를 api 계층에 배치 (domain 아님)

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

## §55 Sprint 4 — API Key prefix "sk_live_" (Stripe 관례)

### 결정
API Key 원본 형식: "sk_live_" + SecureRandom 16byte hex (총 40자)

### 근거
- Stripe의 API Key 네이밍 컨벤션 참고 (업계 표준)
- sk = Secret Key
- rawKey는 발급 시 1회만 반환, DB에는 SHA-256 해시만 저장
- Platform도 원본을 모름 → 분실 시 Revoke 후 재발급

---

## §56 Sprint 4 — GlobalExceptionHandler를 api 모듈에 배치

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

## §57 동시성 제어 우선순위 정책

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

## §58 Queue 생성 동시성 처리 방식

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

## §59 `@DistributedLock` 도입 및 모듈 배치

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


## §60 Sprint 5 — Rate Limiter 알고리즘 선택 (Token Bucket)

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

## §61 Sprint 5 — Rate Limiter 알고리즘 분리 (Token Bucket + Fixed Window)

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

## §62 Sprint 5 — Tenant Plan 도입 (SaaS 등급)

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

## §63 Sprint 5 — RateLimitFilter HTTP 통합

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
- IP는 `request.getRemoteAddr()`(TCP peer)만 신뢰. 현재 프록시가 없으므로 `X-Forwarded-For`는 클라이언트가 임의로 쓰는 값이고, 이를 키로 쓰면 헤더만 바꿔 한도를 무한 우회할 수 있다 (실증됨)
- LB/Nginx 도입(Sprint 11) 시에는 앱 코드가 아니라 `server.forward-headers-strategy=native`(RemoteIpValve) + internal-proxies 설정으로 처리한다

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

> "Rate Limit Filter는 JWT 인증 뒤에 배치합니다. JWT 파싱이 먼저 SecurityContext에 Tenant 정보를 저장하고, RateLimitFilter가 그 정보로 Plan 한도를 적용합니다. 인증 전 endpoint(signup/login/refresh)는 IP 기반 + Fixed Window로 Brute Force와 회원가입 남용을 방지합니다. 이때 IP는 TCP peer(`getRemoteAddr()`)만 씁니다. 프록시가 없는 구성에서 X-Forwarded-For를 신뢰하면 공격자가 헤더만 바꿔 매 요청 다른 버킷을 만들어 한도를 무력화할 수 있기 때문입니다. LB를 두게 되면 앱 코드가 아니라 RemoteIpValve(신뢰 프록시 목록)로 헤더를 검증해 처리합니다. NAT 공유 IP 대비 한도는 너무 엄격하게 잡지 않습니다."

### Related

- §60 (Token Bucket), §61 (분리), §62 (Plan)
- `doc/sprint-5/RATE_LIMITER.md`

---

## §64 Sprint 5 — Redis Lua Script Bean 등록 패턴

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

## §65 Sprint 5 — 인증 전 Rate Limit 알고리즘 의도 (Burst 불허)

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

## §66 Sprint 8+ — Redis Cluster 도입 결정 (Sentinel → Cluster 확장)

> ⚠️ **부분 폐기 — §75(2026-08-11)로 대체됨.**
> Cluster로 간다는 방향은 **확정**되었으나, 이 절의 다음 두 가지는 더 이상 유효하지 않다.
> ① **"Sprint 10 전환" 시점** → 미정으로 재설정 (§75 D29)
> ② **단일 Cluster를 Master 추가로 확장한다는 전제** → **독립 2 Cluster + 큐 단위 이중 라우팅**으로 대체 (§75 D25·D26).
>    §70 D10의 해시태그 때문에 한 큐가 Master 한 대에 고정되므로, 단일 Cluster 확장은 단일 대형 큐(최악 30만)에 효과가 없다.
> 아래 Rationale·수치(3배/16배 등)는 **2026-07-08 시점의 계획 근거**로 보존한다. 지우지 않는다.

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

## §67 Sprint 12+ — 이중 라우팅 아키텍처 (Cluster + Hash Tag)

> ✏️ **Layer 2(`{shard_X}` 태그)는 철회됐다 (2026-08-26 표시, 근거는 `QueueKeys.java` javadoc).**
> Layer 1(Application이 클러스터를 고른다)은 **§75로 구현 완료**다 — 다만 **`queueId` 해시가 아니다.**
> `RedisClusterAssigner`가 **생성 시점의 cluster1 메모리 사용률**(`used_memory/maxmemory ≥ 0.5`)로 정하고 `queues.redis_cluster_no`에 기록한다(§75 D27-3). 🪤 **"해시라 결정론적"으로 읽으면 안 된다** —
> 같은 이름의 큐라도 만든 시점에 따라 다른 클러스터로 간다.
>
> **왜 Layer 2가 성립하지 않나:** 라우팅은 *소유자를 모를 때* `queueId`를 해싱해 클러스터를
> 정한다. 그런데 키 이름에 `shard`를 넣으면 **키를 만들려면 shard를 이미 알아야 하고, shard를
> 알려면 라우팅을 이미 돌렸어야 한다.** 순환이다.
>
> 아래 본문의 `queue:{shard_A2}:q_bts_002:waiting` 예시와 "부하 기반 Shard 결정"은
> **그 순환을 안 보고 쓴 것**이다. 현행 키 형식은 `queue:{queueId}:waiting`이며
> 태그 기준은 **`queueId`로 확정**됐다(§70 D10).

> ⚠️ **§75(2026-08-11)에서 확정·구체화됨.**
> 이 절이 "Sprint 12+ 계획"으로 적어둔 Layer 1(Cluster 선택)이 **채택 확정**되었고,
> §75가 두 가지를 못 박았다: **라우팅 단위 = 큐 1개**(D26), **큐 생성 시 배정 후 고정**(D27).
> 반면 이 절의 **Sprint 번호(12/15+)와 "Least Load 알고리즘"·Tenant Tier 기준은 확정이 아니다**
> — §75의 배정 기준은 "cluster1 master 50% 초과 시 cluster2"이며 임계 정의는 미정이다.

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

## §68 Sprint 10+ — Master 크기 최적화 (Single Thread 병목 해결)

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

## §69 Sprint 15+ — 극대 분산 아키텍처 (4x4x4GB 최종 구성)

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

## §70 Sprint 5-E — Bulk 단독 + seq 키 + Hash Tag (D7/D8 개정, CROSSSLOT 선제 대응)

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
- ~~§67 이중 라우팅 도입 시 태그가 shard로 이동: `queue:{shard_A2}:q_bts_002:waiting`~~
  → 🔴 **철회.** 태그 기준은 `queueId`로 확정됐다 — shard를 키에 넣으면 라우팅이 순환한다(§67 머리말)

### Interview Point

> "Redis Cluster는 CRC16(key) % 16384로 슬롯을 정하고 마스터마다 슬롯 범위를 나눠 갖습니다. 저희 enqueue_bulk.lua는 대기열 ZSet과 순번 카운터 두 키를 함께 다루는데, 해시태그가 없으면 각각 slot 7911, 11273으로 흩어져 서로 다른 마스터에 저장됩니다. Lua Script는 노드 한 대에서만 원자적으로 실행되므로 CROSSSLOT 에러가 납니다. Redis가 옆 노드에서 알아서 가져오지 않는 이유는 그 순간 원자성이 깨지고, 이를 막으려면 분산 트랜잭션이 필요한데 Redis는 속도를 위해 그 복잡도를 거부했기 때문입니다. 해결책은 해시태그로, 키에 중괄호를 씌우면 슬롯 계산에 중괄호 안쪽만 쓰입니다. 두 키의 queueId가 같으니 같은 슬롯이 수학적으로 보장됩니다. 중요한 건 타이밍인데, 지금은 Sentinel이라 해시태그가 무해하지만 Cluster 전환 후에 고치면 키 이름 변경이 곧 슬롯 이동이라 대기 중인 사용자의 순번을 유지한 채 데이터를 이관해야 합니다. 2줄 수정과 무중단 마이그레이션 프로젝트의 차이라 선제 적용했고, 로컬 Cluster에서 실제 스크립트를 돌려 검증했습니다."

### Related

- §66 (Cluster 도입), §67 (이중 라우팅 — ~~태그가 shard로 이동~~ **Layer 2 철회**), §69 (ZSet 128 bytes 추정)
- `doc/CONCURRENCY.md` §6.5 (Multi-key Lua + Hash Tag)
- `doc/FLOW.md` (Enqueue 결정 근거 D1-D10)
- `queue-infrastructure/.../queue/QueueKeys.java`, `ratelimit/RateLimitKeys.java` (선례)

---

## §71 Sprint 5-E / 9+ — Enqueue 저장 순서(Redis → DB) 확정 + DB → Redis 복구 설계

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
                 → SET queue:{queueId}:admit-by-token:{token_id} ... EX {남은 60s}   (양방향)
                 (60s 초과분은 복원 안 함 → 배치가 returnToWaiting 처리)
⑤ last-active:   재구성 안 함(비움) → 다음 폴링(ka=1)이 재populate. inactive_ttl 리셋뿐 무해
⑥ admit-watermark: SELECT MAX(seq) FROM tokens WHERE queue_id=? AND status IN (1,2)
                 → SET queue:{q}:admit-watermark {maxSeq}   (NULL이면 0. §79)
                 COMPLETED(2) 포함 필수 — ADMIT_ISSUED만 세면 정상 진행할수록 후퇴한다
                 빠뜨리면 복구 후 전광판이 0이 되어 전원 순번이 폭증한다
```

### Rationale

**왜 Redis 먼저 (DB auto-increment로 seq 못 매기는 이유)**:
- **타이밍** — 위치는 enqueue 순간(동기) 필요, DB insert는 async(수 초 후) → auto-inc는 너무 늦어 202/폴링에 순위 못 줌
- **순서** — DB auto-inc = insert 순서(Kafka 파티션 병렬·Consumer 배치) ≠ **도착 순서** → FIFO 뒤집힘. `INCR`은 도착 순간 단일스레드 원자 → 정확
- **단위** — auto-inc는 tokens 테이블 전역, 필요한 건 **큐별** 순번(`INCR queue:{q}:seq`는 자연히 큐별)
- → **seq는 Redis가 부여, DB는 저장만**. 부여는 Redis, 기억은 DB.

**왜 복구를 waiting ZSet 스캔이 아니라 DB로 하나 / 왜 seq 순으로**:
- waiting ZSet은 **mutable**(admit/만료 시 ZREM) → "현재 대기자"이지 "모든 enqueue 로그"가 아님 → 소스로 부적합
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
> "enqueue의 순번은 Redis가 `INCR`로 부여하고 DB `tokens.seq` 컬럼에 저장합니다. DB auto-increment를 안 쓴 이유는 세 가지인데, 첫째 위치는 사용자가 enqueue하는 순간 필요한데 DB 적재는 Kafka를 거쳐 수 초 뒤 비동기라 너무 늦고, 둘째 auto-increment는 insert 순서를 반영하는데 그건 Kafka 파티션 병렬·Consumer 배치 때문에 도착 순서와 달라 FIFO가 뒤집히며, 셋째 auto-increment는 테이블 전역이라 큐별 순번을 못 줍니다. 그래서 Redis가 도착 순간 원자적으로 부여하고 DB는 저장만 합니다. 이 저장이 복구의 핵심인데, Redis가 전손되면 복구는 3계층입니다. 대부분은 Sentinel failover로 replica가 승격해 복구가 필요 없고, 그다음이 AOF/RDB 재적재, 최후수단이 DB 재구성입니다. DB 재구성은 큐를 분산락으로 잠근 뒤 WAITING 토큰을 seq 순서로 ZADD하면 대기 순서까지 정확히 복원됩니다. 여기서 주의할 점은 복구 완전성이 DB 신선도만큼이라, 비동기 적재 지연 중 Redis엔 있었지만 DB에 아직 안 들어간 enqueue는 유실될 수 있다는 겁니다. 현재 발행이 fire-and-forget이라 이 gap이 존재하고, Redis Stream Outbox와 대사(reconciliation)로 보강하는 게 후속 과제입니다. 그리고 복구 소스로 live waiting ZSet을 쓰면 안 되는데, admit·만료로 멤버가 빠져나가는 mutable 구조라 짧게 살다 간 토큰을 놓치기 때문입니다. append-only인 DB(또는 outbox)가 소스여야 합니다."

### Related
- §70 (`INCR seq`·Hash Tag — 복구의 근거), §66 (Cluster/failover)
- `doc/STATE.md` (Token 상태 0=WAITING/1=ADMIT_ISSUED), `doc/schema.sql` (tokens.seq/admit_token 컬럼)
- memory: `sprint5-token-kafka-progress`(Kafka 적재·fire-and-forget gap), `token-ttl-design`(returnToWaiting)

---

## §72 Sprint 5-E 개정 — Enqueue DB 영속화: Kafka 제거 → Redis List outbox + @Scheduled + ShedLock

> ⛔ **이 결정은 §73(2026-08-10)이 뒤집었다 — Redis List outbox 폐기, Kafka 복귀.**
> 아래 본문은 "Kafka 제거"를 확정문으로 쓰고 있으니 **여기서 멈추지 마라.** 현행은
> 단일 토픽 `token-lifecycle` + 파티션 키 `tokenId` + `queue-consumer` 모듈이다.
> 이 절이 손으로 만들기로 했던 것(`processing` 목록·heartbeat·reaper·ShedLock 리더 선출)은
> **전부 필요 없어졌다.** 왜 왔다 갔는지는 §73이 세 세대를 한 절에 정리해 두었다.

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

## §73 Sprint 5-E 재개정 — Enqueue 영속화: List → Stream → **Kafka 복귀** + 소비 전담 모듈 분리

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

---

## §74 — 폴링 소유권 검증: seq 존재 판정 → tokenId 대조 (Lua 원자 1회)

**일자**: 2026-08-11 · **Sprint 5-E** · **관련**: §63(Rate Limit 신뢰 경계), §70(INCR seq·Hash Tag), §71(복구)

### Context

`GET /api/v1/queues/{queueId}/tokens/{tokenId}?seq&ka` 는 **permitAll**이다. 브라우저가 Platform을
직접 폴링하는 구조라 API Key도 JWT도 받지 않고, 설계 의도는 **"tokenId 소유가 곧 자격(capability)"** 이었다.

그런데 구현은 tokenId를 한 번도 보지 않았다. `isWaiting(queueId, seq)` 로 **seq 존재만** 확인했다.
`seq`는 `INCR` 발급이라 `1, 2, 3...`으로 추측이 자명하다. 즉 자격 증명이 URL에 실려 있는데 검사하지 않아,
**아무나 `?seq=1&ka=1`을 반복해 남의 대기 항목 inactive_ttl을 무한 연장**할 수 있었다.

피해는 "남의 자리를 뺏는 것"이 아니라 **"남의 자리를 대신 지켜주는 것"** 이다. 이탈했어야 할 유령 대기자가
계속 살아남아 앞자리를 점유하고, inactive 판정 배치가 통째로 무력화된다.

### Decision

**D21 — 검증과 keepalive를 `poll_verify.lua` 하나로 묶는다 (원자 1회)**

```
ZRANGEBYSCORE waiting seq seq  →  identifier      (없으면 0)
HGET tokens identifier         →  "tokenId|issuedAt"
'|' 앞부분과 제시된 tokenId를 문자열 비교           (불일치면 0)
keepalive='1'이면 ZADD lastActive nowMillis seq
return 1
```

**D22 — 도메인 포트에서 `isWaiting`·`touchLastActive`를 삭제하고 `verifyWaiting` 하나로 통합한다**

```java
boolean verifyWaiting(String queueId, long seq, String tokenId, boolean keepalive, long nowMillis);
```

**D23 — tokenId는 반드시 문자열 비교. Lua에서 `tonumber`를 태우지 않는다**
`tok_` + UUIDv7이므로 숫자 변환은 `nil`이 되어 모든 비교가 통과하거나 실패하는 사고가 된다.

**D24 — `ZADD` member는 `ARGV[1]` 원문을 그대로 쓴다**
`tostring(tonumber(x))`는 Lua 5.1의 `%.14g` 포맷을 거쳐 Java가 만든 문자열과 어긋날 수 있다.
이 member는 inactive_ttl 배치가 seq로 되읽을 값이다.

### Rationale

**왜 왕복을 나누면 안 되는가 (D21의 핵심)**

검증(read)과 touch(write)를 분리하면 이 순서가 성립한다.

```
1. 검증 통과            (이 시점엔 항목이 있다)
2. ← admit 또는 이탈로 waiting에서 제거됨
3. touch 실행           → 이미 사라진 seq의 last-active가 되살아난다 (좀비)
```

Redis 스크립트는 단일 실행이므로 세 명령 사이에 아무것도 끼어들 수 없다. 부수적으로 RTT도 2회 → 1회.

**왜 포트를 합쳤는가 (D22)**

두 메서드가 분리돼 있는 한 **"검증 없이 touch"** 를 다시 호출할 수 있다. 그게 정확히 이번 결함이다.
API 표면에 남겨두면 같은 실수가 재발한다. 호출처가 `poll()` 하나뿐이라 합치는 쪽이 diff도 작다.

`keepalive`·`nowMillis`가 도메인 포트에 노출되는 것은 구현 세부 누출로 보일 수 있으나 **정당하다.**
이 둘을 분리하는 순간 원자성이 깨지고, 그게 방금 고친 결함이기 때문이다. 포트가 표현해야 하는 것은
"검증한다"와 "갱신한다"가 아니라 **"검증에 성공한 경우에만 갱신한다"** 라는 하나의 불가분 연산이다.

**버린 대안**

| 대안 | 버린 이유 |
|---|---|
| Java에서 `isWaiting` → `HGET` 비교 → `ZADD` 3회 왕복 | 위 좀비 경쟁이 그대로 남는다. RTT 3배 |
| 검증만 Lua, touch는 별도 호출 | 같은 이유. 원자성이 절반만 생긴다 |
| 폴링에 인증(API Key/JWT) 부여 | 브라우저 직접 폴링 전제(β)가 깨진다. 범위 밖 |
| tokenId 대신 seq에 서명을 붙여 검증 | 발급 경로 전체 변경 필요. 현 구조에서 Hash 대조로 충분 |

**fail-closed 설계**
`tokens` Hash가 유실되면 `HGET`이 nil → `0` → 404다. "못 찾으면 통과"가 아니라 **"못 찾으면 거부"** 다.
Redis 유실 후 복구 전 폴링은 404를 받는데, 이는 §71 복구 과제와 함께 다룬다.

**상태코드를 단일화한 이유**
"존재하지만 남의 것"과 "아예 없음"을 모두 `TOKEN_NOT_FOUND`(404)로 반환한다.
구분하면 공격자가 **seq 열거로 대기열 점유 상태를 읽어낼 수 있다.**

### 검증 (2026-08-11)

악의적 사용자 전제로 실 Redis 20건 + 순수 Mockito 2건. 전체 **202 tests / 0 failures**.

| 공격 | 결과 |
|---|---|
| 교차 탈취 (남의 seq + 내 tokenId, 양방향) | false, `last-active` 미기록 |
| TTL 연장 연타 `ka=true` × 100 | zcard 끝까지 **0** |
| seq 열거 1~50 / 경계값 `0·-1·Long.MAX·MIN` | 예외 없이 전부 false |
| 구분자 주입 `tok_X\|999`, `\|tok_X` | 전부 false |
| 이상 tokenId 13종 (NUL·개행·`\r\nHGETALL`·Lua 조각·10KB·이모지) | 예외 없이 전부 false |
| 큐 교차 (A의 유효 쌍 → B) | A=true, B=false |
| `tokens` Hash 유실 / admit 이탈 후 keepalive | fail-closed, 좀비 없음 |

**`|` 주입이 통하지 않는 이유**: Lua는 **저장값**만 `|`로 자르고 **입력값**은 그대로 비교한다
(`storedTokenId ~= tokenId`). `tok_X|999`를 제시해도 저장된 `tok_X`와 문자열이 다르다.

### Consequences

**긍정**
- permitAll 엔드포인트에 실제 자격 검사가 생겼다. 설계 의도(capability)와 구현이 일치.
- 좀비 last-active가 원천 차단되어 inactive_ttl 배치(Sprint 7/9)의 판정 근거가 신뢰 가능해졌다.
- 포트가 하나로 줄어 "검증 없이 touch" 경로가 API 표면에서 사라졌다.

**부정**
- 폴링이 `ZCOUNT`(read 1회) → **EVAL 3키**로 바뀌었다. Lua는 write로 분류되어 **replica 라우팅 여지가 사라진다.**
  현재 `ReadFrom` 미설정(전부 master)이라 회귀는 아니지만, 30만 폴링 규모의 실측은 **없다(미검증)**.
- `last-active` ZSet은 여전히 `ZADD`만 있고 `ZREM`·`EXPIRE`가 **전 소스에 0건**이다.
  이벤트 100회면 3천만 멤버가 영구 적재된다 → **inactive_ttl 배치에서 "판정 후 ZREM"을 같은 스크립트에 넣을 것.**

**중립 / 남는 전제**
- **score 유일성을 암묵 전제**한다(`members[1]`만 채택). 현재는 `INCR`이 보장하나,
  §71 DB→Redis 복구가 중복 score를 주입하면 뒤로 밀린 사용자가 **영구 404**가 된다.
  → 복구 스크립트에 seq 유일성 단언이 필요하다.
- `last-active` score의 출처는 요청을 받은 **WAS의 벽시계**다. NTP 미동작 시 조기 EXPIRE 가능.
  배치 판정을 Redis `TIME` 기준으로 통일하면 오차원이 하나로 준다.
- tokenId가 URL 경로에 실리므로 로그·리퍼러로 유출되면 소유권 증명이 무력하다. 서명 토큰(HMAC)은 후속.

### Interview Point

> "폴링 엔드포인트가 인증이 없는 대신 tokenId 소유를 자격으로 삼는 설계였는데, 정작 코드가 tokenId를 안 보고 seq 존재만 확인하고 있었습니다. seq는 INCR로 발급해서 1부터 훑으면 되니까, 아무나 남의 순번에 keepalive를 걸어서 이탈했어야 할 대기자를 계속 살려둘 수 있었습니다. 흥미로운 건 이게 '남의 자리를 뺏는' 공격이 아니라 '남의 자리를 대신 지켜주는' 공격이라, 피해자는 아무 이상을 못 느끼고 뒷사람 전체가 손해를 본다는 점입니다. 고칠 때 검증만 추가하면 될 것 같지만 그러면 안 되는데, 검증하고 나서 갱신하는 사이에 그 사람이 admit되거나 이탈하면 이미 사라진 순번의 last-active가 되살아나기 때문입니다. 그래서 Lua 스크립트 하나에 조회, 대조, 갱신을 다 넣어서 원자적으로 처리했습니다. 도메인 포트도 isWaiting과 touchLastActive 두 개를 verifyWaiting 하나로 합쳤는데, 두 개로 남겨두면 '검증 없이 touch'를 다시 호출할 수 있어서 같은 실수가 재발하기 때문입니다. 포트가 표현해야 하는 건 '검증한다'와 '갱신한다'가 아니라 '검증에 성공한 경우에만 갱신한다'라는 불가분 연산이라고 봤습니다. 검증할 때는 정상 사용자가 아니라 공격자를 가정하고 시나리오를 짰습니다. 구분자 주입이나 개행 섞은 Redis 명령 주입, 10KB 문자열 같은 것까지 넣어봤고, 특히 테스트가 진짜 회귀를 잡는지 확인하려고 고친 코드를 일부러 되돌려서 테스트가 실패하는 것까지 봤습니다. 테스트가 있다는 것과 테스트가 작동한다는 건 다른 문제라서요."

### Related
- §63 (Rate Limit — 같은 뿌리의 결함: 신뢰할 수 없는 입력을 권한·한도의 근거로 삼음)
- §70 D9/D10 (`INCR seq`가 유일성을 보장한다는 전제, Hash Tag로 3키 동일 슬롯)
- §71 (DB→Redis 복구 — seq 유일성 제약을 여기서 지켜야 한다)
- **§79 (폴링 응답 계약 — 이 결정이 만든 폴링 경로의 응답·엔드포인트를 변경했다.**
  `poll_verify.lua`의 소유권 대조는 유지되지만, **"`waiting`에 없으면 실패" 분기는 바뀐다** —
  admit된 사용자가 404가 되기 때문이다. `?seq=&ka=`는 그대로 필요하다)
- 리뷰 기록: `doc/reviews/2026-08-11-poll-ownership-xff.md`
- 후속: `last-active` 정리 로직(Sprint 7/9), 폴링 Rate Limit 키 카디널리티, 폴링 부하 실측


---

## §75 — Redis 배포 방식 확정: Sentinel → 독립 2 Cluster + 큐 단위 이중 라우팅

**일자**: 2026-08-11 · **관련**: §66(Cluster 도입 계획 — 이 결정이 시점·목적을 재설정), §67(이중 라우팅 — 이 결정이 구체화), §70 D10(Hash Tag), §74(poll_verify 3키), §73(findDrainableQueueIds 제거)

### Context

**현재 구성 (2026-08-11 기준, 코드 확인)**
- 인프라: Master 6379 + Slave 6380/6381 + Sentinel 26379/26380/26381 (Sprint 5-A, `INFRA_SETUP.md` §6)
- 애플리케이션: `RedisConfig.redisConnectionFactory()`가 `RedisSentinelConfiguration`으로 `LettuceConnectionFactory` **단일 빈**을 만든다. 연결처가 하나라는 전제가 코드에 박혀 있다
- `spring.data.redis.sentinel.*`가 **api / batch / consumer 세 모듈의 yml 9개**에 흩어져 있음
- api / batch / consumer는 **모두 N대 전제**다 (같은 날 확정). Redis는 세 종류 프로세스 × N대가 공유하는 유일한 상태 저장소다

**이미 있는 것 (전부 이번 결정의 준비물)**
- 로컬 Cluster 학습 환경 **2벌**: Cluster A(7001-7008), Cluster B(8001-8008), 각 4 Master + 4 Replica × 1GB. Failover 실증 + **완전 독립성 확인** 완료 (`doc/INFRA_SETUP.md` §6.5)
- 큐 상태 키에 §70 D10의 `queue:{queueId}:...` 해시태그 **선제 적용**
- `QueueKeys.java` 클래스 주석에 이미 예고돼 있다:
  > "Sprint 12+ 이중 라우팅 도입 시 태그를 shard 단위로 옮긴다 (`queue:{shard_X}:{queueId}:waiting`)."

  즉 이번 결정은 새 방향이 아니라 **이미 코드 주석과 §67에 예고돼 있던 경로의 확정**이다. 로컬에 클러스터를 **둘** 띄우고 독립성까지 확인해 둔 것도 이 그림을 전제한 준비였다.

§66(2026-07-08)의 "Sprint 10에 Cluster 전환"은 **계획**이었고 확정이 아니었다.

### Decision

**D25 — Redis는 Cluster로 간다. 단, 단일 Cluster의 자동 샤딩이 목적이 아니다.**
**독립된 2개 Cluster + 애플리케이션 레벨 이중 라우팅**이 목표 형태다.
- cluster1의 master가 **50% 이상 차면** 이후 데이터는 cluster2에 쌓는다
- **cluster1의 (용량) 방어 역할을 cluster2가 맡는다**

**D26 — 라우팅 단위는 "쓰기 1건"이 아니라 "큐 1개"다. (확정)**
한 큐의 키는 **반드시 같은 클러스터**에 놓인다. 요청 단위·토큰 단위로 클러스터를 고르는 방식은
**채택하지 않는다.**

**대상 키 (2026-08-17 기준 10종)** — 넷은 원래 있던 것, 다섯은 §79, 하나는 §80에서 늘었다.

| # | 키 | 출처 |
|---|---|---|
| 1 | `queue:{q}:waiting` | §70 |
| 2 | `queue:{q}:seq` | §70 D9 |
| 3 | `queue:{q}:tokens` | §70 |
| 4 | `queue:{q}:last-active` | §74 |
| 5 | `queue:{q}:admit-watermark` | **§79** |
| 6 | `queue:{q}:pacing` | **§79** (오버라이드. 없을 수 있다) |
| 7 | `queue:{q}:admit-by-token:{tokenId}` | **§79** — 구 `admit-token-by-token:{tokenId}` |
| 8 | `queue:{q}:admit-by-admit:{admitToken}` | **§79** — 구 `admit-token-by-admit:{admitToken}` |
| 9 | `queue:{q}:admit-idem:{requestId}` | **§79** — 구 `admit-idem:{requestId}`. `requestId`가 Tenant 지정값이라 스코프가 필요했다 |
| 10 | `queue:{q}:admitted` | **§80** — ZSet. score=만료 epoch ms, member=`"seq\|identifier"`. TTL 만료 claim 대상(§36 — 복귀가 아니라 `HDEL`+`EXPIRED`) |

7·8은 원래 tokenId/admitToken으로 해시돼 **다른 슬롯**이었다. verify·complete URL에 `queueId`를
넣어(§79) 큐 소속으로 옮긴 것은 **전 구간 원자성의 필요조건**이었고, **§80이 충분조건까지 채웠다** —
중간 DB 확인을 삭제해 admit 전 구간이 Lua 하나에 들어갔고, `verified-token`(소속 미정이던 그 키)은
**폐기**됐다. 즉 이 목록에 클러스터 소속이 미정인 키는 더 이상 없다.

> ⚠️ **§80의 admit Lua는 키가 런타임에 정해진다**(7·8의 `{tokenId}`·`{admitToken}` 부분).
> 해시태그가 `{q}`라 같은 슬롯인 것은 수학적으로 보장되지만, **`KEYS[]`로 선언되지 않은 키를
> 스크립트가 만지는 형태**라 Cluster에서 실제로 도는지 **로컬 Cluster A에서 실증이 필요하다**(§80).

> 이것은 선호가 아니라 **Lua 원자성을 유지하려면 강제되는 제약**이다.
> 해시태그는 *한 클러스터 안의* 슬롯만 정렬한다. **클러스터 경계는 못 넘는다.**
> 한 큐가 두 클러스터에 걸치면 `enqueue_bulk.lua`(3키)와 `poll_verify.lua`(3키)는
> **같은 EVAL로 보낼 수조차 없다.** CROSSSLOT은 한 클러스터 *안*의 문제고, 클러스터가 다르면
> 그 이전 단계 — 커넥션이 다르다 — 에서 실행 자체가 불가능하다.

**D27 — 큐 → 클러스터 매핑은 큐 생성 시점에 정해져 큐 수명 동안 고정된다 (sticky).**
매핑이 고정이면 운영 중 데이터 이동이 없으므로 마이그레이션 문제 자체가 사라진다.
이 매핑은 WAS N대가 **같은 답**을 봐야 하는 **영속 상태**다.

**D27-1 — 매핑은 `queues` 테이블의 별도 컬럼에 저장한다. (확정, 2026-08-11)**
현재 `doc/schema.sql`의 `queues`에는 해당 컬럼이 없으므로 **스키마 변경이 선행 작업**이다.
컬럼명·타입은 dba가 정한다. `Queue` 도메인 필드 추가와 생성 시 배정 로직이 함께 필요하다.

**D27-2 — 이미 배치된 큐는 옮기지 않는다. 새 큐만 cluster2로 간다. (확정, 2026-08-11)**
따라서 rebalancing 절차·마이그레이션 도구가 **필요 없다.** 매핑이 불변이므로 라우팅 캐시에
무효화 로직도 없다 — 한 번 읽으면 영구히 유효하다.

> **폴링 경로는 매핑을 읽을 수단이 따로 필요하다 (2026-08-17, §79에서 이관 — 미정)**
> enqueue·verify·complete는 DB 행을 이미 읽으므로 D27-1의 컬럼이 **딸려온다.** 그러나
> `/status`와 개인 폴링은 **DB를 전혀 읽지 않는다**(핫패스에 MySQL을 넣지 않는 것이 §79의 전제다).
> 후보 둘: **① WAS-local 맵** / **② `queueId` 자체에 클러스터 번호를 인코딩** — D27-2가
> "안 옮긴다"를 확정했으므로 ②가 성립하고, 그러면 **조회·맵·갱신이 전부 0**이 된다.
> **§75 구현 시 결정한다.** §79는 초판에서 `/status` 한 경로만 보고 맵을 정했다가 철회했다(§79 D2).
> 함께 이관된 지적: WAS 간 맵 발산(같은 새 큐가 WAS A는 200, B는 404) · 다른 경로도 같은 맵을
> 쓰는지 미기재 · 맵 메커니즘이 미검증 목록에 없음.

**D27-3 — 50% 임계는 master 노드 *각각* 을 개별 판정한다. replica 포함, `maxmemory` 대비 비율. (확정, 2026-08-11)**
합계가 아니다. **master 4대 중 하나라도** 자기 `maxmemory`의 50%를 넘으면 그 클러스터는
신규 큐를 받지 않는다 — 가장 뜨거운 노드가 기준이다.
합계로 재면 한 노드가 90%여도 평균이 50% 미만이면 계속 배정되어, 정작 터지는 노드를 못 막는다.

> ⚠️ **전제 충돌**: 리뷰에서 `maxmemory 0`(무제한) 권고가 나온 바 있다. `maxmemory`가 0이면
> "대비 비율"이 성립하지 않는다. 이 판정을 쓰려면 **`maxmemory`를 반드시 설정**해야 한다.
> 현재 로컬은 1GB로 설정돼 있다(D2 결정 — admit 구현까지 이 값 유지).

> **측정 주기·캐시 위치·히스테리시스(임계 진동 시 큐가 번갈아 배정되는 문제)는 여전히 미정이다.**

**D27-4 — 큐에 종속되지 않는 키는 cluster1에 고정한다. (확정, 2026-08-11)**
`rl:*`(Rate Limit), `apikey:*`, `tenant:*`, `refresh-token:*` 등 특정 큐에 속하지 않는 키가 대상이다.
전부 **단일 키 연산**이라 해시태그·CROSSSLOT 제약이 없고, 어느 클러스터에 두든 동작은 같다.
양쪽에 나눠 두면 Rate Limit 카운터가 클러스터별로 갈라져 **한도가 2배로 늘어나는** 문제가 생기므로,
한쪽 고정이 맞다.

> ⚠️ **감수하는 대가 — cluster2가 방어하지 못하는 부류가 생긴다.**
> `rl:poll:token:{tokenId}`는 실측상 **요청 1건당 키 1개**다
> (랜덤 tokenId 50회 → 키 50개 신규 생성). 즉 이 키 부류는 **큐 개수가 아니라 요청량에 비례**한다.
>
> cluster1 고정이면 cluster1을 50%로 밀어올리는 **가장 빠른 요인이 정작 cluster2가 손댈 수 없는 것**이 된다.
> 신규 큐를 cluster2로 보내도 cluster1의 Rate Limit 키는 계속 쌓인다.
> → 이 구조에서 cluster1의 메모리 여유는 **폴링 Rate Limit 키의 카디널리티 문제**(§74 후속)와
> 직접 연결된다. 그쪽을 먼저 해결하지 않으면 D25의 50% 임계가 큐 배정과 무관한 이유로 먼저 도달한다.
>
> **[2026-08-12 갱신] TTL을 1시간 → 65초로 줄여 이 대가를 약 55배 축소했다.**
> `token-bucket.lua`의 `EXPIRE`가 고정 3600에서 `max(60, min(3600, ceil(capacity/refillRate)+60))`로
> 바뀌었다. 근거는 **버킷이 full refill되면 그 상태가 키 없음(= capacity로 시작)과 결과가 같다**는 것 —
> 그 시간만 버티면 되고 나머지는 순수한 메모리 낭비였다. 폴링(cap 5, refill 1.0/s)은 65초,
> Tenant Plan은 4종 모두 120초다(실측). 위 문단의 "TTL 약 1시간" 전제는 더 이상 유효하지 않다.
>
> 다만 **문제가 사라진 것은 아니다.** 키 수는 여전히 요청량에 비례하고, 동시 폴링 사용자 수가
> 곧 상주 키 수다(TTL이 짧아도 폴링이 계속되면 매 요청마다 갱신된다). 줄어든 것은
> **폴링을 멈춘 뒤에도 남아 있던 잔여 키**이며, 이것이 §74 후속의 근본 해결을 대체하지는 않는다.

**D28 — Sentinel은 폐기가 아니라 격하다.** 학습·로컬 실습 자산으로 남긴다.
`doc/sprint-5/REDIS_SENTINEL.md`, `INFRA_SETUP.md` §6, Sprint 5-A 이력 서술은 **그대로 보존**한다.
(→ `ARCHITECTURE_ROADMAP.md` §4.4의 "T+1일 Sentinel 폐기"는 이 결정과 충돌한다. 폐기 여부는 미정으로 재설정)

**D29 — 다음은 이 결정에 포함되지 않는다. 미정이다.**

| 미정 항목 | 상태 |
|---|---|
| 50% 판정의 측정 주기·캐시 위치 | 미정 (판정 기준 자체는 D27-3에서 확정) |
| 클러스터 개수 | 미정 — 2개 고정인가, N개 확장형으로 설계하는가 |
| 프로덕션 노드 구성 | 미정 — 로컬은 4 master + 4 replica × 1GB × 2, §69의 목표는 4×4×4GB로 적혀 있으나 **확정 여부 확인 필요** |

> **확정으로 옮겨간 항목** (2026-08-11 사용자 결정): 50% 임계 정의 → D27-3 /
> 매핑 저장 위치 → D27-1 / rebalancing → D27-2 / 큐 비종속 키의 소속 → D27-4
>
> **확정으로 옮겨간 항목** (2026-08-17 구현으로 해소, `feat/redis-cluster-step1`):
> - **임계 히스테리시스** → **단조증가 가드**로 해결. `RedisClusterAssigner.assign()`이
>   `findMaxRedisClusterNo() >= 2`이면 사용률을 보지 않고 cluster2를 반환한다. 한 번 넘어가면
>   되돌아오지 않으므로 50% 부근 진동이 배정에 도달하지 못한다. **새 상태·키·테이블 0개** —
>   이미 있는 `queues.redis_cluster_no`가 그 기록이다
> - **전환 시점** → **이번에 전환**(Sprint 5 연장선). §66의 "Sprint 10"도, `QueueKeys` 주석의
>   "Sprint 12+"도 아니었다. 그 주석은 기각된 (b)계열 태그 규칙까지 함께 지시하고 있어 정정했다
> - **로컬 Sentinel 환경 폐기 여부** → **앱 설정에서 제거**(프로파일 분기도 하지 않는다 —
>   해시태그 누락처럼 Cluster에서만 터지는 결함이 Sentinel 경로로 숨을 통로를 남기지 않기 위함).
>   D28의 "격하"는 **자산 보존**이지 앱이 계속 붙는다는 뜻이 아니므로 충돌이 아니다.
>   Sentinel 인프라·`REDIS_SENTINEL.md`·`INFRA_SETUP.md` §6은 **그대로 존치**한다

### Rationale

**왜 단일 Cluster의 자동 샤딩이 아닌가**

단일 Cluster를 키우는 방식이었다면 §70 D10의 `{queueId}` 해시태그가 곧 한계가 된다. 태그는 한 큐의 모든 키를 **한 슬롯 = 한 Master**에 못 박으므로, Master를 3대로 늘리든 16대로 늘리든 **그 큐의 부하는 흩어지지 않는다.** 이 서비스의 최악 케이스가 **단일 큐 30만 대기**라는 점을 감안하면, 단일 Cluster의 "Master 수만큼 선형 확장"은 **큐가 여러 개일 때만** 성립하는 이야기다.

이번 결정은 그 축을 바꾼다. 한 큐를 쪼개 여러 노드에 퍼뜨리는 대신, **큐 단위로 클러스터를 나눠 담는다.** 확장의 단위가 "슬롯"에서 "큐"로 올라간 것이다.

**왜 하나의 큰 Cluster가 아니라 독립된 둘인가**

cluster1이 차오를 때 노드를 추가하는 대신 cluster2로 신규 큐를 보낸다. 이때 두 클러스터가 **완전히 독립**이라는 점이 핵심이다 — 슬롯 재분배도, 리샤딩 중 마이그레이션도, 한쪽 장애의 전파도 없다. 로컬에서 A/B를 띄우고 "완전 독립성"을 먼저 확인해 둔 것이 이 성질을 겨냥한 것이다.

**이 결정이 코드 주석과 정합한다**

`QueueKeys.java`가 예고한 **"이중 라우팅"이라는 방향 자체는** 이 결정과 맞물린다. 새 방향이 아니라 예고된 경로의 확정이다.

> ⚠️ **다만 그 주석이 제시한 구체적 형태(`queue:{shard_X}:{queueId}:waiting`)는 이제 틀렸다.**
> 그것은 **한 클러스터 안에서 샤딩**할 때의 계획이다. 독립 2 클러스터 + 큐 단위 라우팅에서는
> **클러스터 선택이 곧 샤딩**이므로 태그를 바꿀 이유가 없다.
> 오히려 `{shard_X}`로 묶으면 **여러 큐가 한 슬롯에 몰려** 지금보다 나빠진다
> (현재 `{queueId}` 태그는 큐마다 슬롯이 흩어진다 — 이게 맞는 상태다).
>
> **결론: `QueueKeys`와 Lua 스크립트 2개는 전환 시 변경 없음.** 전환 비용을 크게 줄이는 사실이다.
> `QueueKeys.java`의 해당 주석은 정정 대상이다(코드 수정 필요 — 이번 문서 작업 범위 밖).

**버린 대안**

| 대안 | 버린 이유 |
|---|---|
| 단일 Cluster를 Master 추가로 키움 | 해시태그 때문에 단일 대형 큐의 부하가 안 흩어진다. 30만 단일 큐가 주요 시나리오인 서비스에 안 맞음 |
| 쓰기 1건 단위 클러스터 선택 | `enqueue_bulk.lua`·`poll_verify.lua`가 실행 불가. Lua 원자성을 포기해야 함 (D26 근거) |
| Sentinel 유지 + Master Scale-Up | 저장 총량이 Master 1대 메모리로 묶인다 (§66) |

### Consequences

아래는 **현재 코드·스키마에서 확인한 사실**과 그로부터 따라오는 제약이다.

**① 다중 키 Lua는 한 클러스터 안에서는 이미 준비돼 있다**

| 스크립트 | KEYS | 키 | 같은 슬롯? |
|---|---|---|---|
| `enqueue_bulk.lua` | 3 | `queue:{q}:waiting`, `:seq`, `:tokens` | O (태그 `{q}`) |
| `poll_verify.lua` (§74) | 3 | `queue:{q}:waiting`, `:tokens`, `:last-active` | O |
| `token-bucket.lua` | 1 | `rl:tenant:{id}` | 무관 |
| `fixed-window.lua` | 1 | `rl:{action}:ip{ip}` | 무관 |

큐 키 문자열은 `QueueKeys.java` 한 곳에서만 만들어진다 (main 소스의 `"queue:` 리터럴 4건이 전부 그 파일). 태그 누락 경로가 현재는 없다.

**② 새 키를 태그 없이 기존 Lua의 KEYS에 끼우면 그 순간 깨진다**
Sentinel에서는 아무 일도 없고 Cluster에서만 `CROSSSLOT Keys in request don't hash to the same slot`으로 실패한다. **로컬 Sentinel 테스트로는 절대 잡히지 않는 부류의 결함**이다. 새 큐 상태 키는 반드시 `QueueKeys`를 거치게 하고, 다중 키 Lua는 로컬 Cluster A에서 한 번 돌려봐야 한다.

**③ "어느 큐가 어느 클러스터에 있는가"는 잃으면 안 되는 영속 상태다**
이 매핑을 잃으면 **데이터가 어디 있는지 알 수 없다.** 인메모리 맵·설정 파일로는 부족하다 — api/batch/consumer가 각각 N대이므로 **모든 프로세스가 같은 답**을 봐야 하고, 재기동 후에도 같아야 한다.

> **스키마 영향 (확인함)**: `doc/schema.sql`의 `queues` 테이블에는
> `id / queue_id / tenant_id / name / max_capacity / waiting_ttl / inactive_ttl / status / created_at / deleted_at` 만 있다.
> **클러스터·shard를 담을 컬럼이 없다.** D27-1(=`queues` 컬럼 저장)에 따라 **스키마 변경이 선행 작업**이다:
> `queues` 컬럼 추가 + `Queue` 도메인 필드 추가 + 생성 시 배정 로직. 컬럼명·타입은 DBA 몫이므로 여기서 DDL을 쓰지 않는다.
>
> **부수 이점**: 큐 조회 경로에 이미 있는 테이블이라 **매핑을 얻는 데 추가 왕복이 생기지 않는다.**
> D27-2(불변)와 합치면 **라우팅 캐시에 무효화 로직이 필요 없다** — 값이 안 바뀌므로 TTL도 evict 이벤트도 없다.
> api/batch/consumer가 각각 N대인 환경에서 "어느 인스턴스가 아직 옛 매핑을 보고 있는가"라는 문제 자체가 성립하지 않는다.

**④ 50% 판정은 "가장 뜨거운 노드"가 기준이다 (D27-3 확정)**
master 노드 **각각을 개별 판정**하고, replica를 포함하며, `maxmemory` **대비 비율**로 잰다. 합계·평균이 아니다.

| 항목 | 확정 내용 |
|---|---|
| 판정 단위 | master **노드별 개별** — 하나라도 50% 초과면 그 클러스터는 신규 큐를 받지 않음 |
| replica | **포함** |
| 기준값 | `maxmemory` **대비 비율** (절대 바이트 아님) |

**여기서 따라 나오는 운영 전제 2건**
1. **`maxmemory`를 반드시 설정해야 한다.** 0(무제한)이면 "대비 비율"이 계산되지 않아 배정 로직이 성립하지 않는다. `doc/reviews`의 Redis 설정 점검에 `maxmemory 0` 권고가 있어 충돌로 기록돼 있었으나, **2026-08-18 실측으로 해소**됐다 — Cluster A/B 16노드 전부 `maxmemory=1gb` + `noeviction`, Sentinel 3노드는 2gb.
2. **노드별 `used_memory`/`maxmemory`를 관측할 수 있어야 한다.** 현재 `doc/monitoring/MONITORING_DESIGN.md` §2-2는 Sentinel 전제(Master/Slave/Sentinel)라 **클러스터별 × 노드별 메모리 지표가 없다.** 판정 근거를 실제로 볼 수 없으므로 모니터링 보강이 선행된다.

**아직 미정**: 측정 주기, 판정값 캐시 위치(매 큐 생성마다 노드 전체에 `INFO`를 칠 것인가), 히스테리시스(50% 부근 진동 시 큐가 두 클러스터에 번갈아 배정됨).

**⑤ 배치·컨슈머·스위퍼는 두 클러스터를 다 봐야 한다**
main 소스 전수 확인 결과 `KEYS`, `SCAN`/`ScanOptions`, `executePipelined`, `multi()`/`SessionCallback`, `multiGet` **사용 0건**이다. 즉 지금 당장 깨질 전수 순회 코드는 없다. 하지만 예정된 것이 전부 "여러 큐를 훑는" 성격이다.
- inactive_ttl 판정 배치 (Sprint 7/9) — `last-active` ZSet 순회
- reconciliation 스위퍼 (§73 후속) — Redis ↔ DB 대사

이들이 **클러스터 1개를 가정하면 절반을 조용히 놓친다.** 누락된 절반은 예외도 로그도 남기지 않는다 — 그냥 "처리할 것이 없다"로 보인다. 게다가 `SCAN`은 원래도 노드별인데 이제 **클러스터별 × 노드별**로 돌아야 한다.

> **권장 형태**: §73에서 `QueueRepository.findDrainableQueueIds`를 제거했으므로, **큐 목록은 Redis 순회가 아니라 MySQL `queues` 테이블에서 얻는다.** DB에서 (큐, 클러스터) 목록을 받아 큐별로 해당 클러스터에 키를 지정해 접근하면, 노드·클러스터 경계 문제가 애초에 발생하지 않는다.

**⑥ 이 구조는 용량 방어지 가용성 방어가 아니다 (혼동 주의)**
"cluster1의 방어를 cluster2가 맡는다"는 **용량**에 대한 이야기다.
- cluster1이 **50%를 넘기 전에 죽으면** cluster2는 아무 역할도 하지 않는다. cluster2에는 그 큐의 데이터가 애초에 없다
- 가용성은 **각 클러스터 내부의 master–replica**가 담당한다 (Cluster의 자동 failover)
- 즉 두 축은 독립이다: 용량 = 클러스터 분리, 가용성 = 클러스터 내부 복제
- cluster1 전손 시 그 클러스터에 배정된 큐 전체가 영향을 받는다. 복구는 §71(DB → Redis 복구)의 문제이지 cluster2가 메워주지 않는다

**⑦ Rate Limit·캐시 키는 키 형태 무변경, 소속은 cluster1 고정 (D27-4)**
- Rate Limit: `rl:tenant:{id}`, `rl:{action}:ip{ip}`, `rl:poll:token:{tokenId}` — Lua 둘 다 KEYS 1개, 태그 없음
- 캐시: `apikey:{hash}`, `tenant:{id}`, `refresh-token:{hash}` — 단일 키 GET/SET
- 전부 단일 키 연산이라 슬롯 제약이 없다. **키 문자열은 한 글자도 바뀌지 않는다.**
- 소속은 D27-4로 **cluster1 고정** — 양쪽에 나누면 Rate Limit 한도가 2배로 새기 때문이다
- **귀결**: 이 부류는 큐 개수가 아니라 **요청량**에 비례하므로, cluster1의 메모리 압박에 큐 배정과 무관한 성분이 상시로 얹힌다. D27-3의 50% 판정이 cluster1에서 더 빨리 걸린다는 뜻이다 (D27-4의 "감수하는 대가" 참조)

**⑧ 전환 변경량 — 코드 확인 결과 (범위가 좁고, 무거운 건 딱 하나)**

| 대상 | 개수 | 내용 |
|---|:-:|---|
| 연결 설정 | **1곳** | `RedisConfig` (`RedisSentinelConfiguration` + `LettuceConnectionFactory`) |
| `RedisTemplate` 주입 파일 | **6개** | `RedisQueueEngine`, `RedisTokenBucketRateLimiter`, `RedisFixedWindowRateLimiter`, `RedisApiKeyCache`, `RedisTenantCache`, `RedisConfig` — **전부 `queue-infrastructure`** |
| yml | 9개 | api/batch/consumer × (base·local·dev·prod) |
| `QueueKeys` + Lua 2개 | **0** | 해시태그 그대로 유지 (Rationale 참조) |
| 도메인·API·배치 코드 | 0 | 포트 시그니처 변경 없음 |

**가장 무거운 작업은 파일 수가 아니라 구조다**: 지금은 `StringRedisTemplate` **단일 빈**을 6곳이 주입받는다. 이중 라우팅은 "큐마다 연결처가 다르다"는 뜻이므로, 이 6곳이 **템플릿을 직접 받는 대신 "큐로 템플릿을 고르는 계층"을 거치게** 바뀐다.

- 큐 범위 키를 쓰는 곳은 `RedisQueueEngine` **하나뿐**이라 라우팅이 실제로 필요한 지점은 좁다
- 나머지 5곳(Rate Limit 2 + 캐시 2 + Config)은 **큐에 종속되지 않으므로 라우팅 대상이 아니다.** D27-4로 **cluster1 고정**이 확정됐으므로, 이 5곳은 **cluster1 커넥션을 그대로 주입받으면 된다** — 즉 실질 변경이 "빈 이름/한정자" 수준으로 줄어든다
- 정리하면 **라우팅 계층이 실제로 걸리는 지점은 `RedisQueueEngine` 한 곳**이다. 나머지는 고정 연결이다

Lettuce의 MOVED/ASK 리다이렉트 처리·`ClusterTopologyRefreshOptions`는 **현재 미설정**이며 전환 시 결정 항목이다.

**⑨ 테스트 전제**
`EnqueueE2EIntegrationTest`, `EnqueueBenchmarkTest`가 로컬 Sentinel(26379-26381) 기동을 전제로 한다. 전환 시 이 전제가 바뀐다. 반대로 로컬 Cluster A/B가 이미 있으므로 **이중 라우팅 통합 테스트는 로컬에서 실증 가능**하다.

### Interview Point

> "Redis를 Cluster로 가되, 단일 클러스터를 키우는 게 아니라 독립된 클러스터 두 개로 갑니다. cluster1의 master가 50% 이상 차면 그다음 큐부터는 cluster2에 쌓는 구조입니다. 왜 단일 클러스터 샤딩이 아니냐면, 저희는 한 큐의 키 네 개를 해시태그로 같은 슬롯에 묶어놨거든요. Lua로 원자 처리를 하려면 그래야 합니다. 그런데 그 대가로 한 큐가 통째로 master 한 대에 고정됩니다. 티켓팅처럼 큐 하나에 30만 명이 몰리는 게 정상인 서비스에서는 노드를 늘려도 그 큐의 부하가 안 흩어져요. 그래서 확장의 단위를 슬롯에서 큐로 올렸습니다. 라우팅 단위를 큐로 잡은 건 취향이 아니라 강제입니다. 해시태그는 한 클러스터 안의 슬롯만 정렬하지 클러스터 경계는 못 넘습니다. 한 큐가 두 클러스터에 걸치면 3키짜리 Lua를 같은 EVAL로 보낼 수조차 없어서, CROSSSLOT 에러 이전에 실행 자체가 안 됩니다. 그리고 배정은 큐 생성 시점에 고정합니다. 큐 수명 동안 안 바뀌면 운영 중 데이터 이동이 없어서 마이그레이션 문제가 통째로 사라집니다. 한 가지 분명히 해둘 건, 이건 용량 방어지 가용성 방어가 아니라는 겁니다. cluster1이 50% 차기 전에 죽으면 cluster2에는 그 큐 데이터가 아예 없어서 아무것도 못 합니다. 가용성은 각 클러스터 안의 master-replica가 따로 담당합니다. 이 둘을 섞어서 설명하면 장애 대응 설계가 통째로 틀어집니다."

### Related
- §66 (Sprint 8+ Cluster 도입 계획 — **이 결정이 시점과 목적을 재설정**. §66은 단일 Cluster 확장을 전제했다)
- §67 (이중 라우팅 Layer1/Layer2 — **이 결정이 Layer 1을 확정하고 라우팅 단위를 큐로 못 박음**)
- §70 D10 (Hash Tag — ①의 근거이자, 단일 Cluster를 버린 이유)
- §74 (`poll_verify.lua` 3키 — D26 근거의 두 번째 스크립트)
- §73 (`findDrainableQueueIds` 제거 — ⑤의 배경)
- §71 (DB → Redis 복구 — ⑥의 클러스터 전손 시나리오가 여기로 연결)
- `doc/INFRA_SETUP.md` §6.5 (로컬 Cluster A/B — 완전 독립성 확인이 이 결정의 준비물)
- `queue-infrastructure/.../queue/QueueKeys.java` (클래스 주석의 `{shard_X}` 태그 계획 — **이 결정과 어긋나므로 정정 대상**. 방향은 맞으나 형태가 틀렸다)
- `doc/schema.sql` `queues` 테이블 (③ — 클러스터 컬럼 없음, D27-1에 따라 추가 필요)
- `doc/monitoring/MONITORING_DESIGN.md` §2-2 (④ — Sentinel 전제라 **클러스터별 × 노드별 메모리 지표가 없다.** D27-3 판정 근거를 관측하려면 보강 필요)

### 채워야 할 질문 (사용자 확인 필요)

> ✅ **답변 완료 (2026-08-11)** — 아래 3건은 확정되어 Decision으로 이동했다.
> 50% 임계 정의 → **D27-3** (master 개별 판정, replica 포함, `maxmemory` 대비) /
> 매핑 저장 위치 → **D27-1** (`queues` 테이블 컬럼) /
> rebalancing → **D27-2** (새 큐만 이동, 마이그레이션 없음) /
> 큐 비종속 키의 소속 → **D27-4** (cluster1 고정)

**남은 질문**

1. ~~**50% 판정의 측정 주기·캐시 위치**~~ **(해결, `a4214bf`)** — 큐 생성 시점에만 잰다. 캐시는 두지 않았다 — cold path라 필요 없고, 단조 가드가 먼저 걸리면 Redis를 아예 안 본다
2. ~~**임계 히스테리시스**~~ **(해결, `a4214bf`)** — 별도 장치 없이 **단조 가드**로 닫았다. `MAX(redis_cluster_no) >= 2`면 되돌아가지 않는다. 새 키·테이블 0개
3. **클러스터 개수** — 2개 고정인가, N개로 늘어날 수 있는 구조로 설계하는가?
4. ~~**전환 시점**~~ **(해결, `a4214bf`에서 전환함)** — §66의 Sprint 10, `QueueKeys` 주석의 Sprint 12+ 서술이 모두 앞당겨졌다
5. **프로덕션 노드 구성** — §69의 4×4×4GB가 확정인가, 검토안인가?

> ⚠️ **D27-3의 전제**: `maxmemory 0`(무제한)이면 "대비 비율" 판정이 성립하지 않는다.
> 이 방식을 쓰려면 `maxmemory` 설정이 필수다. (현재 로컬 1GB)

---

## §76 — `tokens`는 UTC, 나머지 테이블은 KST (통일하지 않고 명시한다)

> ⛔ **이 결정은 §77로 대체되었다 (2026-08-12).** 통일하지 않기로 한 판단을 뒤집고
> **전부 UTC로 통일**했다. 아래 본문은 그때의 판단 근거로 남긴다 — 특히 `[불변식]`(JVM TZ 의존)은
> §77의 출발점이 된 발견이라 그대로 유효하다. **현재 규약은 §77을 보라.**

**일자**: 2026-08-12 · **관련**: §73(Kafka 적재 경로 — UTC 변환이 여기서 일어난다), §74(폴링 — 복구 절차가 `issued_at`을 판정에 쓴다)

### Context

같은 DB에 **시각 규약 두 개가 공존**한다. 코드로 확인한 현재 상태:

| 대상 | 쓰는 코드 | 실제 값 |
|---|---|---|
| `tokens.issued_at` | `BatchProcessor` `Instant.now()` → `TokenLifecycleConsumer.toToken()`의 `LocalDateTime.ofInstant(e.issuedAt(), ZoneOffset.UTC)` | **UTC** |
| `queues.created_at` / `deleted_at` | `Queue.java` `LocalDateTime.now()` | **KST** |
| `tenants.created_at` | `Tenant.java` `LocalDateTime.now()` | KST |
| `api_keys.created_at` / `revoked_at` | `ApiKey.java` `LocalDateTime.now()` | KST |
| `refresh_tokens.*` | `RefreshToken.java` `LocalDateTime.now()` + `TenantService:117,138` / `TokenRevocationService:31`의 벌크 UPDATE | KST |
| `admit_requests.created_at` / `completed_at` | DDL `DEFAULT CURRENT_TIMESTAMP(3)` (세션 TZ) | KST |
| `billing_snapshots.updated_at` | 〃 + `ON DUPLICATE KEY UPDATE updated_at = NOW(3)` | KST |
| `queue_daily_stats.created_at` | 〃 | KST |
| `queue_daily_stats.stat_date` | `DATE(issued_at)` | **UTC 날짜** |
| `billing_snapshots.year_month` | `issued_at` 월 범위 | **UTC 월** |

⚠️ `queue_daily_stats`는 **한 행 안에 UTC(`stat_date`)와 KST(`created_at`)가 공존**한다.
⚠️ `admit_requests.created_at`(KST)은 Sprint 7에 `tokens.issued_at`(UTC)과 조인될 가장 유력한 후보다.

MySQL 실측: `@@global.time_zone` = `@@session.time_zone` = `+09:00`,
`NOW()` = `2026-08-12 15:05`, `UTC_TIMESTAMP()` = `2026-08-12 06:05`.

**둘 다 `DATETIME(3)`이라 타입으로 구분되지 않는다.** 각자 자기 컬럼만 볼 때는 무해하고,
그래서 지금까지 문제가 드러나지 않았다.

**드러날 시점이 정해져 있다.** `tokens`의 `completed_at` / `expired_at`은
DDL에 이미 있으나 Sprint 7 미구현이라 전부 NULL이다. 구현할 때 이 코드베이스의 다수 관행
(`LocalDateTime.now()`)이나 `DEFAULT CURRENT_TIMESTAMP(3)`를 그대로 쓰면 **KST가 들어간다**.
그러면

```sql
AVG(TIMESTAMPDIFF(SECOND, issued_at, completed_at))
                          ↑ UTC        ↑ KST
```

이 **오류가 아니라 그럴듯한 숫자로** 일괄 `+32,400초`(9시간)를 낸다. 30분 대기가 9시간 30분으로 보인다.
데이터가 섞인 뒤에는 어느 행이 UTC인지 구분할 방법이 없다.

### Decision

**전체 통일을 하지 않는다. "tokens만 UTC"를 현 상태로 확정하고, 그 사실을 코드·DDL·런북에 못 박는다.**

명시한 지점 넷:
1. `doc/schema.sql` — tokens DDL 위에 [시각 규약] 블록. `DEFAULT CURRENT_TIMESTAMP(3)` 제거
2. `Token.java` 클래스 javadoc — "시각은 UTC, 상태 전이 메서드에서 `now()` 금지, 주입받는다"
3. `TokenLifecycleConsumer.toToken()` javadoc — 여기가 규약을 실제로 강제하는 유일한 지점임을 명시
4. 운영 문서 — `NOW()`/`CURDATE()` 대신 `UTC_TIMESTAMP()`/`UTC_DATE()`

### ⚠️ 불변식 — JVM 기본 TZ == JDBC `serverTimezone` == `Asia/Seoul`

**현재 UTC가 보존되는 이유는 두 설정이 서로 상쇄되기 때문이지, 코드가 명시해서가 아니다.**

근거(Hibernate 6.5.3 소스 + 드라이버 실측):
- `LocalDateTimeJavaType.unwrap(..., Timestamp.class)` → `Timestamp.valueOf(ldt)` — **JVM 기본 TZ로 해석**한다
- `TimestampJdbcType`은 `hibernate.jdbc.time_zone`이 없으면 `st.setTimestamp(index, timestamp)` 분기를 탄다
- `hibernate.jdbc.time_zone`은 **어느 yml에도 없다**(확인). JDBC URL은 `serverTimezone=Asia/Seoul`, JVM은 `Asia/Seoul`

mysql-connector-j 8.3.0 직접 바인딩 실측:

| JVM TZ | 바인딩한 값(UTC 벽시계) | 서버가 본 값 |
|---|---|---|
| `Asia/Seoul` (현재) | `2026-08-12T06:05` | `2026-08-12 06:05` ✅ |
| `UTC` (컨테이너 기본) | `2026-08-12T06:05` | `2026-08-12 15:05` ❌ **+9h** |

**이 등식이 깨지는 순간 `tokens.issued_at`이 통째로 9시간 밀린다. 그리고 조용히 일어난다.**
Docker 이미지 기본 `TZ=UTC`, AWS ECS/EKS 기본 UTC, `-Duser.timezone` 누락이 전부 해당한다 —
**Sprint 11(Docker + AWS 배포)이 정확히 그 시점이다.**

지킬 것:
- 컨테이너·인스턴스에 **`TZ=Asia/Seoul` 또는 `-Duser.timezone=Asia/Seoul`을 명시적으로 박는다**
- JDBC URL의 `serverTimezone`을 바꾸지 않는다
- **`hibernate.jdbc.time_zone: UTC`로 "고치지" 마라.** `Timestamp.valueOf`가 KST로 해석한 뒤
  UTC 캘린더로 렌더링해 `2026-08-11 21:05`이 되고, KST 규약 테이블 전체에도 같은 왜곡이 걸린다
- 근본 해소는 바인딩을 `setObject(LocalDateTime)` 경로로 옮기는 것인데(TZ 무관) Hibernate가
  그 경로를 쓰지 않는다 → **별도 과제**

### Alternatives

**A. 전부 UTC로 통일 (기각)**
가장 "깨끗한" 답이고 장기적으로는 맞다. 그러나 지금 하면 KST 테이블 전부의 기존 행을 옮기는
마이그레이션이 필요하고, 마이그레이션은 되돌리기 어렵다.

정확한 기각 근거는 **"두 규약의 값을 섞는 계산이 현재 없다"**는 것이다.
"KST 컬럼이 계산에 안 쓰인다"가 아니다 — 실제로 쓰인다:
`RefreshToken.java:90`의 `LocalDateTime.now().isAfter(expiresAt)`,
`RefreshTokenJpaRepository:25`의 `DELETE ... WHERE r.expiresAt < :before`.
다만 **양쪽 다 KST**라 자기들끼리는 정합하고, UTC 값과 만나지 않는다.

따라서 통일이 필요해지는 시점은 **"KST 컬럼과 UTC 컬럼을 함께 계산하는 쿼리가 처음 생길 때"**이고,
그때 §를 다시 연다. **가장 유력한 후보는 Sprint 7의 `admit_requests.created_at`(KST) −
`tokens.issued_at`(UTC)** 이다 — "admit 요청 접수 → 토큰 발행 소요" 지표로 가장 자연스럽게 나올 자리다.

**B. 세션/서버 `time_zone`을 `+00:00`으로 바꾸기 (기각)**
한 줄로 끝나 보이지만 **기존 KST 데이터의 해석을 바꾼다.** `DATETIME`은 오프셋을 저장하지 않으므로
이미 들어간 KST 벽시계가 UTC로 재해석되어 조용히 9시간 밀린다. 게다가 로컬·CI·운영이 각각
설정을 따라가야 해서 "설정을 안 바꾼 곳"이 생기는 순간 규약이 다시 갈린다.

> ⚠️ **여기서 "애플리케이션이 명시하니까 환경에 안 흔들린다"고 쓰면 거짓이다. 지금은 흔들린다.**
> 아래 [불변식]을 반드시 함께 읽어라.

**C. `TIMESTAMP` 타입으로 변경 (기각 — MySQL이 금지한다)**
`TIMESTAMP`는 세션 TZ로 자동 변환해줘서 이 문제를 근본적으로 없앤다. 그러나
**`tokens`에는 애초에 쓸 수 없다.** 실측:

```
CREATE TABLE _tz_probe_part (id BIGINT NOT NULL AUTO_INCREMENT,
  issued_at TIMESTAMP(3) NOT NULL, PRIMARY KEY (id, issued_at))
PARTITION BY RANGE (YEAR(issued_at)*100 + MONTH(issued_at)) (...);

ERROR 1486 (HY000): Constant, random or timezone-dependent expressions in
                    (sub)partitioning function are not allowed
```

MySQL은 `TIMESTAMP` 컬럼을 파티션 식에 쓰는 것 자체를 금지한다(`UNIX_TIMESTAMP()` 예외).
**금지 사유가 정확히 우리가 걱정하는 것** — 세션 TZ에 따라 파티션 경계가 움직이기 때문이다.
"비용이 커서" 기각이 아니라 **불가능해서** 기각이다.
(2038 상한도 사실이지만, 파티션 보존이 2달인 이 테이블에선 부차적이다.)

### Consequences

**긍정**
- 마이그레이션 0. 코드 변경 0(주석·DDL 기본값 제거뿐)
- Sprint 7이 `completed_at` 등을 구현할 때 **읽을 수밖에 없는 자리 셋**(DDL / 도메인 / consumer)에 규약이 있다
- 런북의 유령 토큰 탐지가 더 이상 자기 증상을 만들어내지 않는다 (아래 참조)

**부정 / 감수하는 것**
- **두 규약이 남는다.** 새로 오는 사람이 `queues.created_at`과 `tokens.issued_at`을 같은 것으로 보면 틀린다.
  이 문서와 DDL 주석이 유일한 방어선이다
- KST 컬럼과 UTC 컬럼을 함께 쓰는 쿼리가 생기면 **그때는 반드시 통일해야 한다.** 지금은 없다는 것이 전제다
- **일·월 집계 경계가 UTC다 — 이건 통일 논의와 별개로 이미 사실이다.**
  `schema.sql`의 파티션 표현식 `YEAR(issued_at)*100 + MONTH(issued_at)`, 일별 집계의 `DATE(issued_at)`,
  billing의 월 범위(`issued_at >= '2026-04-01'`)가 전부 UTC 기준이다.
  따라서 **KST 5/1 03:00에 발행된 토큰은 4월 파티션 · 4월 청구 · `stat_date` 4/30에 들어간다.**
  테넌트가 KST 기준 청구서를 기대하면 월 경계 9시간분이 어긋난다.
  tokens 내부끼리의 계산이라 이번 규약 위반은 아니지만, **과금 정합성 문제로 별도 판단이 필요하다(미해결).**

**부수적으로 고친 것 (1) — 런북의 `CURDATE()`**
`doc/monitoring/runbook/kafka-persistence.md`의 유령 토큰 탐지 절차가 `CURDATE()`를 쓰고 있었다.

**버그는 항상 나는 게 아니라 KST 00:00~09:00 창에서만 나고, 그 창에서는 결과가 통째로 0건이 된다.**
그 시간대에는 `CURDATE()`가 이미 오늘인데 `UTC_DATE()`는 아직 어제라, `issued_at >= CURDATE()`가
UTC로는 **오늘 09:00(KST) 이후** — 아직 오지 않은 시각 — 를 요구하기 때문이다.
KST 09:00 이후에는 두 값이 같아져 버그가 사라진다(실측: 15:26 KST에 `CURDATE()` = `UTC_DATE()` = `2026-08-12`).
**낮에 검증하면 재현되지 않는다는 뜻이라 더 위험하다.**

결과 0은 "Redis에는 있는데 DB에 없다"로 읽혀 **유령 토큰이 대량 발생한 것처럼 보인다** —
탐지하려는 증상을 절차가 새벽에만 스스로 만들어내고 있었다. `UTC_DATE()`로 정정.
(`queries/kafka-persistence.md`는 이미 `UTC_DATE()`를 쓰면서 "`CURDATE()`는 틀렸다"고 명시하고 있어,
같은 주제로 런북과 쿼리 문서가 정면으로 모순된 상태였다.)

**부수적으로 고친 것 (2) — 명세의 `NOW()-60s`**
`doc/FRS_final.md`와 `doc/FLOW.md`의 verify DB Fallback 명세가
`WHERE ... AND issued_at > NOW()-60s`였다. `NOW()`는 KST, `issued_at`은 UTC라 조건이 **항상 거짓**이다
— CLAUDE.md가 "verify DB fallback 무동작"으로 지목한 결함의 원본이 이 명세다.
Sprint 7이 이대로 구현하면 결함이 코드로 재생산되므로 `UTC_TIMESTAMP(3) - INTERVAL 60 SECOND`로 정정했다.

### 운영 반영

이번 결정은 DDL 변경이 아니다. 실 DB(master 3306 / replica 3307)에는 이미
`issued_at`의 DEFAULT가 없다 — 2026-08-12 세션에 `ALTER`를 실행했으나 커밋되지 않아
`schema.sql`과 어긋나 있었고, 이번에 `schema.sql`을 실물에 맞췄다.

**신규 환경**은 `schema.sql`이 정본이다. **기존 DB**에 재현이 필요하면 1회 실행:

```sql
-- issued_at의 DEFAULT 제거 (KST 유입 경로 차단)
ALTER TABLE tokens ALTER COLUMN issued_at DROP DEFAULT;
-- 메타데이터만 바꾸는 INSTANT 연산이다. 테이블 잠금·파티션 재구성 없음(13개 파티션 무영향).
```

이 프로젝트에는 마이그레이션 도구(Flyway/Liquibase)가 없으므로, DDL 변경은 이렇게
`schema.sql`에 반영 + DECISIONS에 실행문 기록의 형태로만 추적된다.

### 면접 포인트

> "DATETIME과 TIMESTAMP 중 뭘 쓰나요?"

`tokens`는 `DATETIME(3)` + 애플리케이션 UTC 주입을 택했다. `TIMESTAMP`의 자동 변환이 매력적이지만
이 테이블은 `issued_at` Range 파티션이라 타입 변경 비용이 크고, 2038 상한도 있다.
대신 **타입이 규약을 강제해주지 않으므로** 그 강제를 사람이 아니라 **코드 한 지점**
(`toToken()`)에 몰아두고, DDL·도메인 javadoc·런북 세 곳에 근거를 남겼다.

> "왜 통일 안 하고 두 규약을 남겼나요?"

통일은 마이그레이션이고 되돌리기 어렵다. 문제가 되는 조건은 **"두 규약의 값을 함께 계산할 때"**인데
현재 그런 쿼리가 없다. 실익 없는 위험을 지금 지는 대신, **문제가 실제로 나타날 시점
(Sprint 7의 `completed_at` 구현)에 방어를 걸었다.** 컬럼이 비어 있는 동안이 규약을 못 박는
유일하게 싼 시점이었기 때문이다.

---

## §77 — 시각을 전부 UTC로 통일한다 (§76을 대체)

**일자**: 2026-08-12 · **대체**: §76(통일하지 않고 명시) · **관련**: §73(Kafka 적재), §74(폴링)

### Context

§76은 "`tokens`만 UTC, 나머지는 KST"를 현 상태로 확정하고 통일을 미뤘다. 그 판단을 뒤집는다.

**뒤집은 이유는 §76이 스스로 발견한 것이다.** §76의 `[불변식]`은 UTC 보존이 코드가 아니라
`JVM TZ == serverTimezone == Asia/Seoul`의 **상쇄**에 의존한다는 것을 밝혔다. 그런데

- 이 지뢰는 **통일해도 사라지지 않는다.** 두 값이 어긋나는 순간 저장값이 밀린다
- **KST를 저장 규약으로 두면 지뢰가 더 위험해진다.** Docker·AWS ECS/EKS 기본이 `TZ=UTC`라
  모든 인스턴스에 `Asia/Seoul`을 빠짐없이 박아야 하고, 한 곳이라도 빠지면 그 인스턴스가 쓴
  데이터만 조용히 어긋난다
- **UTC로 두면 지뢰의 방향이 뒤집힌다.** 플랫폼 기본값이 곧 정답이 되고, 누가 굳이
  `Asia/Seoul`을 박아야 깨진다

즉 §76이 "지금 통일할 실익이 없다"고 본 전제가, 같은 문서가 발견한 사실 때문에 무너졌다.
비용도 지금이 가장 싸다 — `tokens` 0행, KST 테이블 합계 **21행**.

### 실측한 메커니즘

Hibernate는 `LocalDateTime`을 `Timestamp.valueOf`(JVM 기본 TZ 해석) → `setTimestamp`로 바인딩한다.
드라이버는 그 순간을 **연결 타임존**으로 렌더해 보낸다. mysql-connector-j 8.3.0 직접 실측:

| JVM TZ | URL `serverTimezone` | 서버 저장값 (바인딩 `2026-08-12T06:05`) |
|---|---|---|
| Asia/Seoul | Asia/Seoul | `06:05` ✅ 항등 |
| Asia/Seoul | UTC | `2026-08-11 21:05` ❌ −9h |
| UTC | Asia/Seoul | `2026-08-12 15:05` ❌ +9h |
| UTC | UTC | `06:05` ✅ 항등 |
| 아무거나 | **(없음)** | `06:05` ✅ 항등 (기본 `LOCAL` = JVM TZ) |

**항등은 둘이 같을 때만 성립한다.** 어느 TZ인지가 아니라 같은지가 조건이다.

**그리고 `serverTimezone`은 세션 `time_zone`을 바꾸지 않는다** — 이게 §76이 몰랐던 부분이다:

| 설정 | `@@session.time_zone` | `NOW()` |
|---|---|---|
| `connectionTimeZone=UTC` 만 | `+09:00` | KST |
| `+ forceConnectionTimeZoneToSession=true` | `+00:00` | **UTC** |

`serverTimezone`만 바꾸면 저장은 UTC가 되지만 `NOW()`/`CURDATE()`는 KST로 남아,
§76이 지적한 verify Fallback 문제가 그대로 남는다.
(참고: `forceConnectionTimeZoneToSession`에 `Asia/Seoul` 같은 **이름 있는 존은 실패**한다 —
MySQL에 tz 테이블이 안 실려 있어 `Unknown or incorrect time zone`. 오프셋만 쓸 수 있다.)

### Decision

**저장·`NOW()`·`CURDATE()`는 전부 UTC. 로그 표시만 `Asia/Seoul`.**

```
JVM TZ  = UTC                       ← main()에서 TimeZone.setDefault
JDBC    = connectionTimeZone=UTC & forceConnectionTimeZoneToSession=true
로그    = logging.pattern.dateformat: "yyyy-MM-dd'T'HH:mm:ss.SSSXXX, Asia/Seoul"
```

`LocalDateTime.now()`가 JVM 기본 TZ를 읽으므로, JVM을 UTC로 두면 **도메인 코드를 한 줄도 안 고치고**
전 테이블이 UTC가 된다. `LocalDateTime`은 틀린 타입이 아니다 — DB 컬럼이 `DATETIME`이라
존 정보를 저장하지 않으므로 "존 없는 벽시계"라는 의미가 컬럼과 정확히 대응한다.

**예외 하나 — `ApiResponse.timestamp`는 `Instant`로 바꿨다.**
이 값만 JSON으로 외부에 나가는데, `LocalDateTime`이면 `2026-08-12T08:12:51`처럼 존 없이
직렬화돼 클라이언트가 자기 로컬로 읽는다(한국이면 9시간 오해). `Instant`는 `...Z`가 붙어
UTC임이 값에 드러난다.

### Alternatives

**A. 전부 KST로 통일 (기각)**
지금은 **가장 싸다** — 21행이 이미 KST고, `tokens`는 0행이라 UTC 규약을 버려도 잃을 데이터가 없다.
설정 변경 0, 코드 1줄(`toToken()`), 로그도 그대로 읽힌다. 그럼에도 기각한 이유 셋:

1. **플랫폼 기본값과 영원히 싸운다.** 위 Context 참조. 지뢰가 더 위험해진다
2. **`ZoneOffset.UTC`는 고정 오프셋, `Asia/Seoul`은 tzdata 룰 조회.**
   `toToken()`의 결과가 `UNIQUE(token_id, issued_at)`의 절반이라 재처리 멱등성이 걸려 있는데,
   룰 조회는 tzdata 갱신에 따라 이론상 달라질 수 있다. 한국은 지금 DST가 없지만
   **1987~1988년에 실제로 서머타임을 했다** — KST 벽시계로 저장하면 그런 구간에서
   같은 시각이 두 번 나오거나 아예 없다. UTC엔 그런 구간이 없다
3. **B2B SaaS라 테넌트가 한국에만 있지 않을 수 있다.** `billing_snapshots`의 월 경계가
   "누구의 월인가"가 된다. UTC면 모두에게 일관되게 틀리고 표시 계층에서 테넌트별 변환이 가능하다

**B. 도메인 타입을 전부 `Instant`로 교체 (기각 — 이번 범위 아님)**
`Instant.now()`는 TZ 무관이라 JVM TZ 지뢰가 **근본적으로** 사라진다. 방향은 맞다.
그러나 도메인 4 + 엔티티 7 + 어댑터·리포지토리·Mixin·DTO까지 **22파일** 연쇄이고,
캐시 Mixin(`ApiKeyMixin`, `TenantMixin`)의 직렬화 포맷이 바뀌면 **기존 Redis 캐시 역직렬화가 깨진다**.
"로직 변경 없는 TZ 전환"과 성격이 달라 섞으면 문제 시 원인을 못 가린다.
→ **후속**. `Token`은 Sprint 7에서 `completedAt` 등을 추가할 때 함께 가는 것이 자연스럽다.

**C. `serverTimezone`을 URL에서 제거 (기각)**
없으면 기본이 `LOCAL`(=JVM TZ)이라 항등이 **항상** 성립한다. 얼핏 가장 안전해 보인다.
그러나 그러면 저장 규약이 **각 인스턴스의 JVM TZ에 좌우된다** — A 인스턴스(KST)는 15:05,
B 인스턴스(UTC)는 06:05를 같은 컬럼에 쓴다. 불일치가 인스턴스 단위로 갈라져 더 나쁘다.
**명시적으로 `UTC`를 박아 어긋남이 드러나게 하는 편**을 택했다.

### Consequences

**긍정**
- 두 규약이 하나가 됐다. §76이 경고한 "Sprint 7의 `admit_requests`(KST) − `tokens`(UTC)" 위험이 소멸
- `NOW()`/`CURDATE()`가 UTC가 되어 `NOW()-60s` 류 명세가 **그대로 맞는 표현이 됨**
- 지뢰가 플랫폼 기본값과 같은 방향이 됨 (Docker/AWS 기본 `TZ=UTC`)
- 도메인 코드 변경 0

**부정 / 감수하는 것**
- **로그와 DB가 9시간 다르다.** 로그는 KST(`+09:00` 표기), DB는 UTC. 대조 시 감안해야 한다.
  로그에 오프셋이 찍히므로 헷갈릴 여지는 줄였지만, **이게 이 결정의 실질 비용이다**
- **`mysql` CLI는 여전히 KST다.** `forceConnectionTimeZoneToSession`은 앱의 JDBC 커넥션에만
  적용된다. 서버 `default-time-zone`이 `+09:00`이라 셸에서 `mysql -e "..."`로 붙으면
  `@@session.time_zone = +09:00`이고 `NOW()`/`CURDATE()`가 KST다(실측).
  → **운영 쿼리는 계속 `UTC_DATE()`/`UTC_TIMESTAMP()`를 쓰거나 `SET time_zone='+00:00'`을 앞에 둔다.**
  → 근본 해소는 `my.cnf`의 `default-time-zone='+00:00'`인데 **MySQL 재기동이 필요해 미적용**(후속)
- **`ApiResponse.timestamp`의 형식이 바뀐다.** `2026-08-12T08:12:51` → `2026-08-12T08:12:51.799Z`.
  API 계약 변경이므로 SDK·클라이언트가 있으면 함께 봐야 한다(현재 없음)

### 운영 반영

이미 실행한 것 (2026-08-12, 로컬 master 3306 / replica 3307):

```sql
-- KST로 저장돼 있던 21행을 UTC로 이동. tokens/admit_requests/billing_snapshots/
-- queue_daily_stats 는 0행이라 대상 없음.
UPDATE tenants        SET created_at = created_at - INTERVAL 9 HOUR;
UPDATE api_keys       SET created_at = created_at - INTERVAL 9 HOUR,
                          revoked_at = revoked_at - INTERVAL 9 HOUR;
UPDATE queues         SET created_at = created_at - INTERVAL 9 HOUR,
                          deleted_at = deleted_at - INTERVAL 9 HOUR;
UPDATE refresh_tokens SET issued_at  = issued_at  - INTERVAL 9 HOUR,
                          expires_at = expires_at - INTERVAL 9 HOUR,
                          revoked_at = revoked_at - INTERVAL 9 HOUR;
-- 되돌리기: 위 문장의 - 를 + 로 바꿔 실행
```

⚠️ `refresh_tokens`는 시각 컬럼이 **3개**다(`issued_at`/`expires_at`/`revoked_at`).
`created_at`만 보고 옮기면 `isExpired()`가 9시간 어긋나 전원 만료되거나 전원 무만료가 된다.

**아직 안 한 것**: `my.cnf`의 `default-time-zone` 변경(재기동 필요) · Sprint 11의
Dockerfile `TZ=UTC` 명시 + 기동 fail-fast

### 검증

- **실서버 왕복 실측** (queue-api 8090 기동 → signup):
  실제 KST 17:12:51 → **로그 `17:12:37.020+09:00`(KST)** / **DB `created_at = 08:12:51.726`(UTC)**
- 드라이버 프로브: JVM TZ 2종 × `serverTimezone` 3종 = 6조합, 세션 TZ 6조합
- logback 타임존 패턴: JVM=UTC 상태에서 `%d{...XXX, Asia/Seoul}`이 `+09:00`으로 출력되는 것 실측
- 전체 스위트 **선언 224 / 실행 220 / 실패 0**
- **미검증**: 멀티 인스턴스에서 JVM TZ가 어긋났을 때의 실제 증상, `my.cnf` 변경 후 동작

### 면접 포인트

> "타임존을 어떻게 다루나요?"

저장은 UTC, 표시는 KST로 분리했다. 다만 그 전에 **왜 지금까지 UTC가 보존됐는지**를 먼저 실측했다.
Hibernate가 `LocalDateTime`을 `Timestamp.valueOf` → `setTimestamp`로 바인딩해서, JVM 기본 TZ와
JDBC 연결 타임존이 **상쇄될 때만** 값이 보존되고 있었다. 즉 코드가 명시해서가 아니라 우연이었다.
그걸 알고 나니 "어느 TZ로 통일하느냐"보다 **"플랫폼 기본값과 같은 방향으로 두느냐"**가 중요했다.
Docker·AWS 기본이 UTC라 UTC를 택하면 아무것도 안 하는 게 정답이 되고, KST를 택하면
모든 인스턴스에 TZ를 빠짐없이 박아야 하며 한 곳만 빠져도 조용히 어긋난다.

---

## §78 — 클라이언트 경계: enqueue는 Tenant, polling은 Platform 직접 (브라우저 직접 enqueue 기각)

**결정일**: 2026-08-13. §35(SDK 범위)가 "무엇을 만드나"라면, 여기는 **"누가 어디로 요청하나"**다.

### Decision

| 경로 | 호출 주체 | 인증 | 사용자당 빈도 |
|---|---|---|---|
| **Enqueue = 자격 판정** | **Tenant 서버** | `X-API-Key` | **1회** |
| **Polling = 순번 조회** | **클라이언트 → Platform 직접** | `tokenId` 소유 (§74) | 수십~수백회 |

**JS SDK 범위 = 폴링 + 대기 UI 전용.** enqueue는 SDK에 넣지 않는다.

### 기각한 안 — "브라우저가 직접 enqueue한다"

`X-API-Key`가 브라우저로 나가면 안 되므로, **Tenant가 발급하는 짧은 수명의 입장권 토큰**을
Platform이 검증하는 안을 검토했다. 서명 키 관리·재사용 방지·만료 처리가 딸린다. **기각한다.**

**① 없앨 왕복이 애초에 없다.**
대기 페이지 HTML·JS를 **Tenant가 서빙**하므로 사용자당 1회 왕복이 **이미 발생한다.**
그 응답에 `tokenId`·`seq`를 실어 보내면(서버 렌더링이면 HTML에, SPA면 부트스트랩 API 응답에)
**추가 왕복 0 / 브라우저에 자격 증명 0 / 새 인증 인프라 0**이다.
기각안조차 결국 "Tenant가 서명해준 것"을 검증하는 구조라, **Tenant를 거치는 건 똑같고 절차만 는다.**

**② 최소화할 곳은 enqueue가 아니라 폴링이었고, 그건 이미 돼 있다.**

| | 요청 수 (30만 대기 기준) | 성격 |
|---|---|---|
| Enqueue | 30만 × **1회** | **순간.** 60초에 몰려도 5,000 rps |
| Polling | 30만 × **대기 내내** (2~25초 간격) | **지속. 약 15,400 rps** (산식은 §79 "규모") |

폴링이 3배이고 **지속된다.** 그리고 폴링은 이미 클라이언트 → Platform 직접이다.
Tenant가 받는 enqueue는 요청을 Platform으로 넘기고 `tokenId`를 돌려주는 **무상태·무 DB 전달
엔드포인트** 5,000 rps다. Tenant의 병목은 결제·좌석 확정 같은 실제 트랜잭션이지 HTTP 전달이 아니고,
**그게 대기열을 쓰는 이유 자체**다.

**③ (본질) 편의 문제가 아니라 책임 문제다.**
`CLAUDE.md` 핵심 설계 원칙 1 — **"Platform은 순서만 관리, Tenant가 슬롯·입장 제어"**.
`enqueue`는 "줄을 세운다"가 아니라 **"이 사람이 줄 설 자격이 있나"**를 판정하는 지점이고,
그 판정은 Platform이 **할 수 없다**:

- **`identifier`가 Tenant의 개념이다** (§66 D1: identifier는 Tenant가 만들어 넘긴다). Platform은 그 값이 진짜 누구인지 모른다
- **줄 서기 전 규칙이 전부 Tenant 쪽에 있다** — 회원만 / 계정당 1회 / 이미 티켓 보유자 제외 / VIP 별도 큐 / 정지 계정 차단
- **봇 방어가 Tenant 자산이다** — 로그인, CAPTCHA, 기기 지문. 브라우저 직접 enqueue는 이걸 통째로 우회한다

브라우저 직접 enqueue는 **Platform이 인증할 수 없는 값을 근거로 자리를 내주는 것**이 된다.
어떤 자격 증명 모델을 붙여도 이 구조적 문제는 사라지지 않는다.
**Tenant 부담 경감이 아니라 책임 이전이고, Platform이 못 지는 책임이다.**

### 🔴 `identifier` 형식 = UUIDv7. 생성·전달 주체는 Tenant

Platform은 **형식 가이드만 제시**하고, `identifier` 검증 책임은 **전적으로 Tenant**에 있다.

🔴 **이 매핑은 브라우저 종료를 견뎌야 한다** — 세션 쿠키(비영속)만 쓰면 비로그인 유저가 재접속할 때
Tenant가 누구인지 몰라 새 identifier를 만들고, 그러면 §82의 `inactiveTtl` 유예 창이 그 유저에게는
무의미해진다. 이 요구와 나머지 두 경계(`tokenId` 보관처 · 비로그인 입장 처리)는
**`FRS_final.md` §6.2 "세션 경계 3종"** 에 있다.

**Tenant는 `userId → identifier(UUIDv7)` 매핑을 저장하고, 같은 사용자·같은 큐에는 항상 같은
UUID를 재사용한다.** 매 요청 새로 생성하면 `enqueue_bulk.lua`의 `HSETNX`가 안 걸려
**한 사람이 자리를 여러 개 차지한다.**

**왜 추측 가능한 값(이메일·순번 ID)이면 안 되나 — enqueue가 조회 오라클이 된다**

`enqueue`는 이미 등록된 identifier면 **기존 `tokenId`와 `seq`를 그대로 반환한다**
(`enqueue_bulk.lua`의 EXISTS 분기 — 멱등성을 위해 그렇게 설계했다).
그런데 `tokenId`는 §74에서 **폴링 자격 증명 그 자체**다. 따라서 identifier가 추측 가능하면:

```
공격자가 남의 identifier(예: victim@example.com)를 알고 있다
  → 그 값으로 enqueue 호출
  → EXISTS 분기 → 남의 tokenId·seq 획득
  → 남의 폴링 자격 증명을 손에 넣는다
```

이 경로는 **기각한 안(브라우저 직접 enqueue)보다 나쁘다.** 기각안은 최소한 Tenant의 서명을
요구했지만, 이쪽은 Tenant가 body의 identifier를 **그대로 전달**하기만 하면 성립한다.
아래 Consequences의 "무상태·무 DB 전달 엔드포인트"를 문자 그대로 읽으면 정확히 그 구현이 나온다.

**UUIDv7이면 이 경로가 죽는다** — 추측이 불가능하므로 남의 identifier를 알 방법이 없다.

### 갈리는 축

"프론트 있으면 SDK로 enqueue / 없으면 REST로 enqueue"가 **아니다.**
**두 경우 모두 enqueue는 Tenant가 한다.** 갈리는 건 **"대기 UI를 SDK로 만드냐, 직접 만드냐"**뿐이다.

### 브라우저가 아닌 클라이언트 (게임 · 네이티브 앱)

**아무것도 추가로 만들지 않는다.** 폴링은 이미 공개 REST 엔드포인트이고 JS SDK는 그 위의 편의
래퍼일 뿐이다. Unity(C#)·Unreal·Swift·Kotlin은 같은
`GET /api/v1/queues/{queueId}/tokens/{tokenId}?seq=&ka=`를 직접 호출한다.
언어별 SDK를 만들지 않는 이유는 §35와 동일하다 — 하나를 고르면 나머지를 버린다.

그리고 **§35가 브라우저 SDK를 정당화한 근거 4개 중 3개가 여기선 사라진다**:

| §35의 "SDK 아니면 반복될 실수" | 게임·네이티브에서 |
|---|---|
| 폴링 간격 미준수 → 429 | **정상 클라이언트의 실수(간격 미준수)만 방어한다.** `RateLimitFilter` 백스톱(tokenId 기준, **cap 5 / refill 1.0per sec**)이 있고, 클라가 지킬 건 "`pacing` 표대로 기다린다" 한 줄이다. ⚠️ 다만 RL 키가 `uri.substring(uri.lastIndexOf('/')+1)` — 즉 **클라이언트가 통제하는 URL 마지막 세그먼트**이고 필터가 소유권 검증보다 **먼저** 돈다. **악의적 클라이언트는 tokenId를 바꿔가며 매번 새 버킷을 얻어 우회**하며, `pacing` 값을 늘려도 그쪽에는 무효다 |
| 탭 비활성화 시 불필요한 폴링 | 안 지켜도 **플랫폼은 안 아프다.** 폴링을 멈추면 `last-active`가 갱신되지 않아 `inactive_ttl` 회수 대상이 된다. 클라 배터리 문제지 서버 문제가 아니다. ⚠️ **회수 배치는 아직 없다** — `last-active` ZSet은 폴링이 쓰기만 하고 읽는 곳이 0이다(Sprint 7/9 예정). 즉 이 칸은 **설계상 근거이지 현재 동작이 아니다** |
| `tokenId` 유실 | **게임이 브라우저보다 유리하다** — 계정 로그인 + 확실한 로컬 저장. 새로고침·탭 닫힘이라는 브라우저 고유 사고가 없다 |
| 대기 UI 제공 | 게임은 자체 UI 엔진이 있어 **어차피 안 쓴다** |

> ⚠️ **첫 칸은 §79(2026-08-14)가 뒤집었다.** 원래 이 칸의 근거는 *"지터를 서버가 응답에 실어
> 보낸다(`QueueEngineService.nextPollAfterSec`)"* 였는데, **§79가 그 필드를 응답에서 제거**하고
> 지터를 클라이언트로 옮겼다. 즉 게임·네이티브 클라이언트는 이제 `pacing` 표 해석 + 지터를
> **직접 구현**해야 한다. 위 칸은 그 사실을 반영해 다시 쓴 것이다. → §79 Related 참조

**폴링 Rate Limit 파라미터 (확정)** — 키는 `tokenId` 기준을 유지한다.

| 항목 | 값 | 근거 |
|---|---|---|
| 키 | `tokenId` | IP로 바꿔도 로테이션 우회는 **동일**하다. 바꿔서 얻는 게 없다 |
| refill | **1.0 per sec** | **최소 폴링 간격의 역수보다 커야 한다.** 최소 간격 2초 → `0.5/s`로 잡으면 여유가 **0**이고, 그건 PR #23이 이미 고쳤던 버그다 |
| capacity | **5** (현행 유지) | capacity가 정하는 건 정상 여유가 아니라 **버스트 흡수 깊이**다 — 재접속·화면 복귀 시의 연속 요청을 받아내는 몫이다. §79는 `rank<=0`에서 2초 폴링을 유발해 버스트가 **늘어나는** 방향이므로 흡수량을 줄일 근거가 없다 |

**enqueue는 오히려 게임 쪽이 더 자연스럽다.** 클라이언트가 이미 게임 서버와 세션을 갖고 있어
`클라 → 게임 서버(자격 판정) → Platform enqueue → tokenId 전달`이 그대로 성립한다.
브라우저의 "페이지 서빙 왕복에 실어 보낸다"의 게임판이고, 왕복이 더 확실히 존재한다.

필요한 건 SDK가 아니라 **명세 한 문단**이다 — `/status`의 `pacing` 표대로 간격 준수(+지터는 클라가
직접) / 429·5xx 백오프 재시도 / 404는 `errorCode`로 분기(§79) / `tokenId`를 잃으면 순번을 잃는다 /
폴링을 멈추면 자리가 회수된다.
언어별 클라이언트가 정말 필요해지면 **§35 Alternatives C(OpenAPI 생성)**가 언어 중립 답이다.

### Consequences

- **브라우저·게임 클라이언트는 `X-API-Key`를 절대 갖지 않는다.** 자격 증명은 `tokenId` 하나뿐이고,
  그 검증은 §74(폴링 소유권 검증)가 담당한다
- **Tenant 통합 문서에 "대기 페이지를 서빙할 때 `tokenId`·`seq`를 함께 내려보내라"가 명시돼야 한다.**
  이 한 줄이 빠지면 Tenant가 브라우저에서 enqueue를 시도하게 된다
- Tenant는 enqueue 전달용 엔드포인트 1개를 직접 만들어야 한다 (무상태·무 DB).
  ⚠️ **단, "전달"이 body의 `identifier`를 그대로 흘려보내는 것이어서는 안 된다.**
  `identifier`는 **Tenant 서버측 세션에서 도출**하고, 매핑은 Tenant가 저장한다 (위 UUIDv7 절).
  이 한 줄이 빠지면 위에서 설명한 조회 오라클이 그대로 열린다
- `enqueue`에는 폴링과 달리 **RL이 tenant 버킷으로 커버**된다 (개별 사용자 식별 불필요)
- **API Key 스코프는 Tenant 단위(전 큐)를 유지한다.** 큐 단위 스코프는 만들지 않는다 —
  유출 시나리오가 아직 가정이라 과설계로 판단했다.
  ⚠️ 다만 **`ApiKeyCache.invalidate` 프로덕션 호출이 0건이라 폐기가 최대 60초 지연**되는 것은
  사실이다. 이건 스코프와 무관한 별개 결함이며 **후속 코드 수정 대상**이다

### Related

- **§35** (SDK 범위 — "무엇을 만드나". 이 결정은 "누가 어디로 요청하나")
- **§66 D1** (identifier는 Tenant가 제공 — 이 결정이 **형식을 UUIDv7로 좁혔다**)
- **§74** (폴링 소유권 검증 — `tokenId` 소유가 자격. identifier 오라클이 위험한 이유)
- **§79** (폴링 응답 계약 — 이 결정의 게임·네이티브 표 첫 칸을 뒤집었다)
- **§63** (Rate Limit 신뢰 경계 — 폴링 RL 키가 클라이언트 통제값이라는 같은 뿌리의 문제)

### 면접 포인트

> "대기열인데 왜 브라우저가 직접 줄을 서지 않나요?"

`enqueue`는 줄을 세우는 게 아니라 **"이 사람이 줄 설 자격이 있나"를 판정하는 지점**이라,
그 판정에 필요한 정보가 전부 Tenant 쪽에 있습니다 — 회원 여부, 계정당 1회 제한, 봇 방어.
Platform은 `identifier`가 진짜 누구인지 모릅니다. 브라우저가 직접 enqueue하면 **Platform이
인증할 수 없는 값을 근거로 자리를 내주게** 됩니다.

처음엔 "Tenant 왕복을 없애자"고 단기 입장권 토큰 안을 설계했는데, 다시 세어보니
**대기 페이지를 Tenant가 서빙하므로 없앨 왕복이 애초에 없었습니다.** 그리고 부담을 줄여야 할 곳은
사용자당 1회인 enqueue가 아니라 **대기 내내 지속되는 폴링**이었고, 그건 이미 브라우저 →
Platform 직접으로 분리돼 있었습니다. **없는 문제를 풀려던 설계**였습니다.

---

## §79 — 폴링 응답 계약: `frontSeq` → `admitWatermark` + `pacing` 구간표 (엔드포인트 2분할)

> ✏️ **§80(2026-08-17)이 이 절의 미해결 2건을 닫았다.** 아래 "원자 범위"의 남은 장애물 표
> (ⓐ 중간 DB 확인 · ⓑ `verified-token` 소속 미정)는 **더 이상 유효하지 않다** —
> §80이 중간 DB 확인을 **삭제**했고(대상 선택의 권위는 Redis, §71 D11) `verified-token`을
> **폐기**했다. 그래서 "pop 성공 + 토큰 SET 실패" 창도 사라졌다(Lua 하나).
> **watermark 조건부 갱신·🔴 표시 전용 가드레일·A/B/C 판정·404 계약은 그대로 유효하다.**

**결정일**: 2026-08-14. **구현 완료 (2026-08-19).** §74(폴링 소유권 검증)가 만든 폴링 경로의
**응답 계약**을 바꾼다. Admit(Sprint 7) 착수 전에 닫아야 하는 결정이다 — watermark를 갱신하는
주체가 admit이고, JS SDK가 이 계약 위에 올라가기 때문이다.

### 문제

현행 폴링 응답 `{ready, admitToken?, frontSeq, total, nextPollAfterSec}` 은 **필드 3개가
사람마다 다르다.**

- `frontSeq` — WAS-local Caffeine 스냅샷(2초)이라 WAS·시점마다 다름
- `total` — `ZCARD`. 큐 크기만큼 부담
- `nextPollAfterSec` — 서버가 `rank = mySeq − frontSeq`로 등급 판정 (`QueueEngineService`)

**규모 (30만 대기 · 현행 사다리 기준)**

```
50/2 + 950/5 + 4000/10 + 5000/15 + 290000/20 = 25 + 190 + 400 + 333 + 14500
                                              ≈ 15,448 rps
```

전부 개인화 응답이라 **캐시가 불가능하고, 경로 전체가 `EVAL`(write)이라 master에 고정된다.**

> ⚠️ **초기 서술 정정 (2026-08-17).** 이 절에는 근거 오류 2건이 있었다.
> ① *"1.5만~6만 rps"* — 6만은 **전원이 5초 구간일 때만** 나오는 값인데 사다리 구조상 도달 불가다.
>   실제는 위 산식대로 **15,448 rps** 한 값이다.
> ② *"매 폴링이 `ZRANGE` + `ZCARD` + `ZCOUNT`로 Redis를 때린다"* — **세 항 전부 사실이 아니다.**
>   `ZCOUNT`는 §74에서 EVAL로 대체되어 **전 소스 0건**이고, `ZRANGE`/`ZCARD`는
>   `QueueSnapshotCache`(Caffeine 2초) 뒤에 있어 **큐·WAS당 0.5회/초**다.

**정정된 근거 — 현행 폴링당 Redis 왕복은 `poll_verify.lua` EVAL 1회다.**
그리고 §74가 이미 그 대가를 적어뒀다(§74 Consequences):

> "폴링이 `ZCOUNT`(read 1회) → **EVAL 3키**로 바뀌었다. Lua는 write로 분류되어
> **replica 라우팅 여지가 사라진다.**"

즉 15,448 rps가 **전부 master로 간다.** 이 결정의 근거는 "Redis 명령 수가 많다"가 아니라
**"개인화 응답이라 경로 전체가 `EVAL`(write)이고, 그래서 master에 15k rps가 고정된다"**이다.

> **2026-08-17 개정.** 초판은 이 근거를 **"캐시가 불가능하다"**로 적었다. 그런데 아래 D1에서
> **WAS 캐시를 만들지 않기로** 했으므로, 캐시를 전제한 근거는 자기부정이 된다.
> 살아남는 근거는 **자료구조 전환**이다 — 분할하면 평상시 폴링이 `EVAL`(write·master 고정)에서
> **`MGET`(read)**으로 바뀐다. 캐시는 **그 다음에 가능해지는 성질**이지 이 결정의 전제가 아니다.

### Decision

**응답을 "전원 동일"로 만들고, 개인화가 필요한 순간에만 개인 엔드포인트를 부른다.**

| 엔드포인트 | 응답 | 호출 빈도 | 캐시 |
|---|---|---|---|
| `GET /api/v1/queues/{queueId}/status` | `{lastAdmittedSeq, pacing}` — **30만 명 전원 동일** | 평상시 전부 | **가능**(전원 동일값). 단 **지금은 안 만든다** — 아래 D1 |
| `GET /api/v1/queues/{queueId}/tokens/{tokenId}?seq={seq}&ka={0\|1}` | `{ready, admitToken?}` — §74 소유권 검증 유지, 단 **분기 변경 필요**(아래) | 차례 근처 + keepalive 30~60초 1회 | ❌ 개인화 |

> 개인 엔드포인트의 `seq`·`ka`는 **뺄 수 없다.** `poll_verify.lua`가 `ZRANGEBYSCORE waiting seq seq`로
> 소유권을 대조하므로 `seq`가 없으면 검증 자체가 성립하지 않고, `ka`는 `last-active` 갱신 트리거다.
> 현행 컨트롤러도 둘 다 `@RequestParam`으로 받고 있다.

```json
GET /api/v1/queues/{queueId}/status
{ "lastAdmittedSeq": 47,
  "pacing": [[50,2],[1000,5],[5000,10],[10000,15],[null,20]] }
```

```
SDK:  rank = mySeq − lastAdmittedSeq        (뺄셈 1회, 서버는 계산 안 함)
      간격 = pacing 표 조회 + ±20% 지터
      mySeq <= lastAdmittedSeq  →  그때만 개인 엔드포인트로 admitToken 확인
```

**서버가 하는 일은 키 3개 `MGET` 하나다.** rank 계산·등급 판정을 서버에서 삭제한다.

```
MGET queue:{q}:admit-watermark   queue:{q}:pacing   queue:{q}:seq
```

### D1 — WAS-local 캐시는 만들지 않는다 (구현은 `MGET` 직행)

`/status` 응답이 전원 동일하다는 것은 **캐시가 가능하다는 성질**이지 캐시를 만들 이유가 아니다.
이 절은 스스로 **"캐시 적중률 미검증"**이라고 적었다. 없는 수치를 전제로 컴포넌트를 먼저 만들지 않는다.

- **재도입 비용이 낮다** — 필요해지면 캐시 한 겹(≈15줄)을 얹으면 된다. 되돌리기 쉬운 결정을
  미리 확정하는 것이 손해다
- **반대 방향의 위험이 더 크다** — 계약에 "WAS 캐시"를 못 박으면 *"이미 캐시가 있으니까"*로
  **CDN 도입 결정이 미뤄진다.** 실제 부하를 걷어내는 것은 CDN 쪽이다
- ⚠️ **`Cache-Control` 헤더도 지금은 붙이지 않는다.** 붙이는 순간 `max-age` 값 논쟁이 시작되는데
  **CDN이 없는 지금 그 헤더는 아무 일도 하지 않는다.** CDN 도입(Sprint 11) 시점에 값과 함께 정한다

### D2 — 큐 → 클러스터 라우팅은 이 절이 정하지 않는다 (→ §75)

초판은 여기서 "WAS-local 맵 선적재 + 주기적 통째 리로드"를 정했다. **철회한다.**

- 매핑의 거처는 **이미 §75 D27-1이 정했다** — `queues` 테이블 컬럼, 큐 생성 시 배정. 이 절이
  남의 결정을 대신 내리고 있었다
- 지금 맵을 만들면 **가치가 0**이다: `schema.sql`에 클러스터 컬럼이 없고, Redis 연결은 단일
  팩토리이며, 라우팅 코드가 0건이다. §75 전환은 Sprint 12+다
- 이 절이 답한 것은 라우팅이 필요한 **4경로 중 `/status` 하나뿐**이었다. enqueue·verify·complete는
  DB 행을 이미 읽어 컬럼이 딸려오고, **개인 폴링은 여전히 미해결**이다. 한 경로만 보고 정할 문제가 아니다

### D3 — 미지 `queueId` 판정: `MGET`에 `queue:{q}:seq` 한 키를 더한다

`seq`는 그 큐의 **첫 enqueue가 `INCR`로 만든다**(`enqueue_bulk.lua`). 큐가 실재하고 사람이
한 명이라도 들어왔다면 반드시 있다. **같은 해시태그라 같은 슬롯 → 여전히 1왕복, 추가 비용 0.**

> `seq` 없음 **AND** `admit-watermark` 없음 → **404**

이것으로 **맵 선적재 · 주기 리로드 · 리로드 주기 미정값 · "새로 만든 큐가 리로드 전까지 404"**
(이 절이 스스로 "하필 가장 나쁠 때 터진다"고 지적한 그 타이밍)가 **전부 소멸한다.**

**대가**: enqueue가 **0건인 실존 큐는 404**다. 대기 페이지는 enqueue 이후에 서빙되므로(§78)
실사용 경로가 아니지만, 큐를 만들고 아무도 안 넣은 채 `/status`를 열어보면 404다.

### `pacing`의 출처 — 코드 상수 기본값 + Redis 오버라이드

| 순위 | 출처 | 비고 |
|---|---|---|
| 1 | `queue:{queueId}:pacing` (Redis) | **있으면 이긴다.** 운영 중 즉시 변경용 |
| 2 | 코드 상수 | 키가 없을 때. 현행 `QueueEngineService`의 사다리와 같은 값 |

- **평상시 큐 대부분은 키가 없다** → 관리 대상이 0이다. 설정 테이블도, 관리 API도 만들지 않는다
- **추가 왕복 0.** `admit-watermark`·`seq`와 **같은 해시태그**라 `MGET` 한 번에 실린다.
  (초판은 근거를 *"`/status` 응답이 캐시되므로 Redis 조회는 캐시 미스 때만"*이라고 적었다 —
  **D1로 캐시를 안 만들므로 거짓이 됐다.** 매 요청 `MGET`이 맞다. 그래도 **왕복 수는 그대로 1회**라
  결론은 산다)
- **존재 이유는 장애 시 "전원 간격 2배"를 서버가 즉시 할 수 있다는 것.** 이 키가 없으면
  남는 수단이 429뿐인데, 이 문서 스스로 "429는 부하 제어가 아니라 사용자 대기 실패"라고 적었다

**D4 — 큐 생성 시 `SET`으로 항상 채워두는 안 (기각)**

*"큐 만들 때 pacing을 넣어두면 폴백 분기가 없어지지 않나"* — 없어지지 않는다.

- **폴백은 못 지운다.** AFTER_COMMIT 쓰기가 실패한 경우와 Redis 유실(§71) 두 경우에 키가 없다.
  즉 분기를 없애는 게 아니라 **분기 + 쓰기 경로 + (큐 삭제 시) 삭제 경로를 더하는 것**이다
- 폴백은 **`nil이면 상수` 한 줄**이고, watermark와 같은 슬롯이라 **추가 왕복이 0**이다. 지울 값이 없다
- **`pacing`은 큐 속성이 아니라 운영 레버다.** 큐 속성의 집은 이미 `queues` 테이블이다
  (`maxCapacity`·`waitingTtl`·`inactiveTtl`). 레버의 집은 Redis가 맞다 — 레버는 사고 중에
  즉시 돌려야 하고, 그때 DB 마이그레이션이나 관리 API를 거치게 만들면 레버가 아니다

### `admitWatermark` 저장·갱신

- 키: `queue:{queueId}:admit-watermark` — **해시태그 필수**(§70 D10). 단일 스칼라.
  **`QueueKeys.admitWatermark(queueId)`를 신설해 그것만 쓴다.** 문자열 리터럴로 조립하면
  해시태그가 빠져도 **로컬 Sentinel에서는 절대 안 잡히고 Cluster에서만 `CROSSSLOT`으로 깨진다**
  (CLAUDE.md 핵심 설계 결정 10)
- **admit Lua 안에서 갱신한다.** admit은 이미 원자 연산이어야 하고(ZSet에서 N개 pop + 상태 전이),
  그 스크립트가 방금 뽑은 최대 seq를 알고 있다. **왕복 추가 0회**
- ⛔ **아래 문단은 이력이다 — §80이 닫았다.** admit 전 구간이 단일 Lua로 원자가 됐다.
  닫은 방법은 스크립트를 정교하게 만든 게 아니라 **원인(중간 DB 확인)을 삭제한 것**이고,
  `verified-token`도 폐기됐다. 남은 장애물 표 ⓐ·ⓑ는 **둘 다 해소**됐다.
- **⚠️ 원자 범위는 아직 "pop + watermark"까지다. 전 구간 원자성은 미해결이다 (Sprint 7 과제).**
  verify·complete URL에 `queueId`를 넣어 admitToken 키를
  `queue:{queueId}:admit-by-token:{tokenId}` / `queue:{queueId}:admit-by-admit:{admitToken}` 로
  옮긴 것은 **필요조건**이다 — 구 표기(`admit-token-by-token:{tokenId}`)는 tokenId로 해시돼
  다른 슬롯이었으므로 애초에 한 스크립트에 담을 수조차 없었다. **그러나 충분조건이 아니다:**

  | 남은 장애물 | 내용 |
  |---|---|
  | ⓐ **중간에 DB가 낀다** | admit 흐름이 `① Lua(pop)` → `② DB WAITING 상태 확인` → `③ 토큰 SET` 순서다(`FLOW.md` admit 다이어그램, `FRS §6.4`). **Lua는 MySQL을 못 만진다.** 슬롯을 정렬해도 ①과 ③은 별개 호출이다 |
  | ⓑ **`verified-token:{tokenId}` 소속 미정** | ②가 이 키를 본다. 해시태그가 없고 §75 **D27-4의 "큐 비종속 키" 목록**(`rl:*`/`apikey:*`/`tenant:*`/`refresh-token:*`)에도 없다. 이중 라우팅에서 큐가 cluster2에 있으면 이 키는 cluster1 → **크로스 클러스터**다. 같은 클러스터 안의 `CROSSSLOT`보다 나쁘다 |

  **따라서 "pop은 성공했는데 토큰 SET은 실패"하는 창은 여전히 존재한다.**
  Sprint 7이 admit을 설계할 때 **해결안을 정해야 한다** — 이 절을 근거로 "한 Lua면 된다"고
  착수하면 성립 불가능한 스크립트를 전제하게 된다
- ⚠️ **`SET`이 아니라 "현재값보다 클 때만 쓴다".** WAS N대가 동시에 admit하므로 늦게 도착한
  작은 값이 watermark를 **후퇴**시키면 사용자 화면의 순번이 **늘어난다**

```lua
local cur = tonumber(redis.call('GET', KEYS[n]) or '0')
if maxPoppedSeq > cur then redis.call('SET', KEYS[n], maxPoppedSeq) end
```

- ⚠️ **읽는 쪽도 단조를 지켜야 한다 — SDK가 clamp한다.** 쓰기 Lua가 후퇴를 막아도, WAS N대가
  **각자 다른 시점의 값을 읽는다.** 세션 어피니티가 없으므로 같은 클라이언트의 연속 두 요청이
  서로 다른 WAS로 가면 받은 값이 **작아질 수 있고**(캐시가 없어도 타이밍 차만으로 발생한다),
  그러면 화면 순번이 **늘어난다** — 쓰기 쪽에서 막은 바로 그 증상이다.
  → **SDK는 `wm = Math.max(wm, 받은값)`으로 단조를 강제한다.** 쓰기 Lua의 후퇴 방지와 **한 세트**다

- **콜드 스타트 폴백 불필요**: 키가 없으면 `0`으로 읽고 `rank = mySeq − 0 = mySeq`인데,
  아무도 입장 안 했으므로 **그게 맞는 값**이다
- **캐시가 아니라 원본이다.** Redis 유실 시 전광판이 0으로 돌아가 전원 순번이 폭증한다.
  복구원: `tokens` 테이블 **`status IN (ADMIT_ISSUED, COMPLETED)`의 최대 seq**. 유실 감지·복구 설계와 같은 자리

  ```sql
  SELECT MAX(seq) FROM tokens WHERE queue_id = ? AND status IN (1, 2);  -- ADMIT_ISSUED, COMPLETED
  ```

  ⚠️ `status = ADMIT_ISSUED`만 세면 **정상 진행할수록 watermark가 후퇴한다** — admit된 사람이
  complete로 넘어갈수록 집합에서 빠지고, 전원이 complete하면 `MAX`가 NULL이라 **0**이 된다.
  "한 번이라도 admit된 적이 있는가"가 기준이므로 COMPLETED를 포함해야 한다.
  ⚠️ `schema.sql`의 `tokens` 인덱스에 `seq`가 포함돼 있지 않아 이 `MAX(seq)`는 인덱스로 풀리지
  않는다. **복구 경로에서만 도는 쿼리라 수용**하되, 상시 조회로 승격하면 인덱스를 다시 봐야 한다

### 🔴 가드레일 — watermark는 **표시 전용**이다

**admit 대상 선택은 언제나 실제 `waiting` ZSet의 최소 seq부터 한다.**
watermark 기준으로 "48번부터 뽑자"고 하면, ~~admitToken TTL 만료로 seq를 보존한 채 WAITING에
복귀한 토큰~~이 커서 뒤에 남아 **영구 누락**된다.
🔴 **§36이 복귀를 폐기(2026-08-21)해 이 시나리오는 소멸했다.** 다만 **규칙 자체는 유지한다** —
watermark는 표시 전용이고 커서가 아니라는 원칙은 다른 이유로도 유효하다.

### `mySeq <= lastAdmittedSeq` 일 때 — 상태 판정은 **새로 만들지 않는다**

| 실제 상태 | 원인 | 처리 |
|---|---|---|
| **A. admit됨** | 정상 | `admitToken` 전달, `ready: true` |
| ~~**B. 아직 WAITING**~~ | ~~admitToken TTL 만료 → seq 보존 복귀~~ | 🔴 **상태 B가 소멸했다 — §36이 복귀를 폐기(2026-08-21).** 만료자는 `waiting`으로 돌아오지 않으므로 **C로 수렴**한다 |
| **C. 사라짐** | 취소·만료 | `404` |

판정은 **Redis 키 2개의 존재 여부**가 이미 한다 — `queue:{queueId}:admit-by-token:{tokenId}`
있으면 A, `waiting` ZSet에 `mySeq` 있으면 B, 둘 다 없으면 C. 두 키가 같은 `{queueId}` 슬롯이므로
**한 번의 조회로 끝난다**(URL에 `queueId`를 넣은 결과). **DB `status` 컬럼은 폴링 경로에서 읽지 않는다**
(핫패스에 DB를 넣으면 15k rps가 MySQL로 간다). watermark는 **판정이 아니라 트리거**이고,
분기를 늘리지 않는다.

**🔴 상태 A 판정 키는 반드시 `tokenId` 기반이어야 한다.** `seq` 기반으로 만들면 §74가 고친 결함
(추측 가능한 `seq`로 남의 상태를 조회·연장)이 그대로 되돌아온다.

**⚠️ 개인 엔드포인트의 분기를 바꿔야 한다 — 안 바꾸면 admit된 사용자가 404다.**
현행 `poll_verify.lua`는 `waiting` ZSet에 없으면 `0`을 돌려주고, `QueueEngineService`가 그것을
`TOKEN_NOT_FOUND`로 던진다. admit되면 ZSet에서 빠지므로 **A가 곧 404**가 된다.
→ "`waiting`에 없으면 실패"가 아니라 **"`waiting`에 없고 `admit-by-token`도 없을 때만 실패"**로
바꾼다. `poll_verify.lua`의 소유권 대조(`tokens` Hash의 tokenId 문자열 비교)는 그대로 유지한다.

**404 계약 — SDK는 HTTP 상태가 아니라 `errorCode`로 재시도를 결정한다**

| 실제 상황 | errorCode | SDK 동작 |
|---|---|---|
| C. 취소·만료로 진짜 사라짐 | `TK001` (`TOKEN_NOT_FOUND`) | **종료** |
| ~~B′. admitToken TTL 만료 후 WAITING 복귀 **대기 중**~~ | 🔴 **소멸 (§36)** | C와 같다 — `TK001` 종료 후 **재접속 안내** |

🔴 **이 후속 과제는 §36의 복귀 폐기로 소멸했다(2026-08-21).** 구분할 두 상황이 하나로 합쳐졌으므로
신규 `ErrorCode`도, 그것을 던질 판정 수단도 필요 없다. `TK001` 하나로 충분하고, SDK는 그것을
**재접속 안내**로 처리한다. 아래는 당시 문제 서술의 기록이다.

~~현재 `ErrorCode`에는 `TOKEN_NOT_FOUND` 하나뿐이라 두 상황이 뭉개진다. 그대로 두면 TTL 만료 직후
~ 복귀 배치 실행 사이의 창에서 한 코호트 전체가 404를 받고 SDK가 일제히 종료한다.~~
"404 = 즉시 종료"라는 SDK 계약도 함께 폐기한다. **ErrorCode 추가는 코드 변경이라 후속 작업**이며,
이 표는 그 전까지의 계약 정의다.

**B가 표시상 정직한 이유**: admit이 항상 ZSet 최소 seq부터 뽑으므로 복귀자는 **다음 배치에서 가장
먼저** 뽑힌다. 화면의 "곧 입장"이 실제와 맞다.

### `pacing`을 응답에 싣는 이유 — 부하 제어 레버를 서버가 쥔다

사다리를 SDK에 하드코딩해도 **SDK 코드 복잡도는 같다**(if 사다리 vs 표 조회). 차이는 하나뿐이다:

> 오픈 당일 Redis가 버거워 **"전원 폴링 간격 2배"** 긴급 조치가 필요할 때,
> 하드코딩이면 테넌트들이 각자 npm 버전을 올려 재배포해야 한다. 옛 버전 테넌트는 계속 2초로 때린다.
> 서버에 남는 수단은 **429로 쳐내는 것뿐인데, 그건 부하 제어가 아니라 사용자 대기 실패다.**

우리가 만드는 게 부하 제어 플랫폼인데 그 레버를 클라이언트에 넘기고 되찾지 못하는 구조는 맞지 않다.
레버 값이 **응답 필드 하나**다. 기존 결정 **"페이스=서버 지휘, 실행=SDK"**도 그대로 지켜진다.

### `total` 제거

"전체 몇 명 대기 중"은 UI 장식인데 `ZCARD`는 큐 크기만큼 부담이다. **뺀다.**
필요해지면 `/status` 응답에 필드를 얹으면 된다 — 전원 동일 값이라 **개인화를 되살리지 않는다.**
(다만 `ZCARD`는 `MGET`에 못 실린다. 왕복이 하나 느는 것을 그때 감수할지 다시 판단할 것)

### Alternatives

**A. 현행 `frontSeq` 유지 (기각)**
`ZRANGE waiting 0 0`이라 취소·만료로 빠진 사람까지 반영해 전진하므로 **watermark보다 정확하다.**
그런데 기각 사유는 정확도 대 캐시가 아니다(D1로 캐시를 안 만드니 그 저울은 성립하지 않는다).

> **`frontSeq`는 단조가 아니다.** ~~§36의 seq 보존 WAITING 복귀가 일어나면~~ 맨 앞 seq가
> 🔴 **§36이 복귀를 폐기(2026-08-21)해 이 원인은 소멸했다.** 아래 서술은 당시 근거의 기록이다.
> 결론(비용 때문에 `frontSeq`를 쓰지 않는다)은 그대로다 — 30만 ZSet 조회 대 `GET` O(1).
> **작아진다** → 사용자 화면의 순번이 **늘어난다**. §79가 watermark에 대해 Lua 조건부 갱신으로
> 막아둔 바로 그 사고가, `frontSeq`에서는 **막을 방법 없이 정상 동작으로 발생**한다.

비용도 다르다 — watermark는 `GET` 1키 **O(1)**이고, `frontSeq`는 30만 ZSet 접근이다.
정확한 대신 단조가 아닌 값은 **전광판으로 쓸 수 없다.**

**B. 서버가 rank를 계산해 내려준다 (기각)**
가장 친절하지만 **응답이 사람마다 달라져 이 결정의 목적 자체가 사라진다.**
뺄셈 한 번을 서버가 대신해주려고 15k rps를 전부 개인화한다.

**C. `pacing` 없이 SDK가 사다리를 하드코딩 (기각 — 위 "부하 제어 레버" 참조)**
필드 하나를 아끼고 운영 레버를 잃는다.

**D. 엔드포인트를 안 쪼갠다 (기각 — 이게 핵심이다)**
분할의 이득은 **캐시가 아니다**(D1로 캐시를 안 만든다). **`EVAL` → `MGET` 전환**이다.

현행 폴링 1건은 `EVAL` **2회**다 — `token-bucket.lua`(Rate Limit) + `poll_verify.lua`(§74).
**둘 다 write로 분류되어 replica로 뺄 수 없다.** 30만 큐면 master가 **30.9k EVAL/s**를 받는다.
쪼개면 평상시 트래픽이 `MGET`(read)으로 빠지고 master EVAL은 **10.3k~15.4k/s**로
**절반~1/3**이 된다(개인 엔드포인트 비율 1/2~1/3, 아래 Consequences).

캐시(WAS·CDN)는 그 위에 **얹을 수 있게 되는 성질**이지 분할의 이유가 아니다.
**watermark 채택과 엔드포인트 분할은 한 세트다** — 쪼개지 않으면 경로가 여전히 개인 URL이라
`MGET` 전환 자체가 불가능하다.

### Consequences

- **rank가 과대 추정(항상 상한)이다.** watermark는 admit할 때만 전진하므로 중간에 취소·만료로
  빠진 사람을 못 뺀다. 이탈이 많은 큐에서 실제보다 많이 남은 것처럼 보인다.
  → 사용자 우선순위가 **"정확한 순번 불필요, admit 누락 없이 통과가 핵심"**이라 수용한다.
  방향이 항상 "생각보다 빨리 입장"이지 반대가 아니다
- **API 계약 변경**: `PollResponse`의 `frontSeq`·`total`·`nextPollAfterSec` 제거, 엔드포인트 신설.
  JS SDK 미착수라 깨질 클라이언트는 현재 없다
- **`QueueSnapshotCache`(Caffeine) 존재 이유가 사라진다.** `frontSeq` 스냅샷 전용으로 만든
  것이라 제거 대상이다 — 프로젝트의 유일한 로컬 캐시 의존성도 같이 검토한다
- 🔴 **테스트가 같이 죽는다. 그리고 죽는 이유는 캐시가 아니라 이 결정 본체(개인화 삭제)다.**
  `QueueEnginePollTest` 5개가 전부 `QueueSnapshotCache`를 생성자로 받고, 그중:

  | 테스트 | 운명 |
  |---|---|
  | `nextPollAfterSec_tiers` (`@ParameterizedTest` 5케이스) | **통째로 소멸** |
  | `emptySnapshotEdge` (`frontSeq=-1` 엣지) | **통째로 소멸** |
  | `waiting_noKeepalive` | `frontSeq`·`total` 단언만 소멸 (`ready`·`admitToken`은 생존) |
  | `notWaiting_throws` · `waiting_keepalive` | 목 설정만 걷어내면 생존 |

  **캐시를 남겨도 결과는 같다** — 제거하는 것은 필드(`frontSeq`/`total`/`nextPollAfterSec`)이지
  캐시가 아니기 때문이다.
  특히 `nextPollAfterSec_tiers`가 지키던 불변식 — **지터는 등급 하한 위로만**(`base ~ base+max(1,base/4)`,
  Rate Limit `refill 1.0/s`와 맞물린 계약) 그리고 **구간 양 끝이 실제로 나오는가** — 가
  **JS SDK로 이관되는데 JS SDK에는 테스트 인프라가 없다**(미착수).
  → 즉 "후속: `QueueSnapshotCache` 제거"의 실체는 **"서버가 지키던 페이싱 하한 계약을,
  테스트가 없는 클라이언트로 옮기는 것"**이다. 이관 자체를 막을 이유는 없으나 **비용을 모르고
  옮기면 안 된다** — SDK 착수 시 이 불변식의 테스트를 먼저 세워야 한다
- **구현 노트**: `queue-api`의 `spring-boot-starter-data-redis`는 **`testImplementation` 스코프**다
  (`queue-api/build.gradle` — 주석에 "테스트 스코프 전용"). 따라서 `/status` 핸들러가
  `redisTemplate`을 직접 부를 수 없고, **`QueueEngine` 포트에 조회 메서드를 신설**해야 한다.
  헥사고날 정석이라 추가 의존성은 0이며, **캐시 유무와 무관하게 강제된다**
- **⚠️ 용량 표 — 어느 전제인지 밝히고 써라. `/status`와 개인 엔드포인트를 반드시 함께 더한다**

  | 전제 | WAS가 받는 HTTP 요청 | WAS → Redis |
  |---|---|---|
  | **분할 전 (현행 코드)** | 15,448 rps | **30,896 EVAL/s** (`token-bucket` + `poll_verify`, 둘 다 write) |
  | **분할 · 캐시 없음 (이번 결정)** | `/status` 15,448 + 개인 5,000~7,700 = **20.4k~23.1k rps** | `MGET` 15,448 (read) + **EVAL 10.3k~15.4k/s** (개인 경로만) |
  | **CDN까지 (Sprint 11)** | 큐·엣지당 `1 ÷ max-age` + 개인 5,000~7,700 rps | 위와 동일 |

  > **정정(2026-08-17).** 초판 표는 CDN 행에만 개인화를 더하고 **캐시 행에는 안 더해** 두 행을
  > 비교 불가능하게 만들었다. 분할하면 **HTTP 요청 수는 오히려 는다**(한 사람이 두 엔드포인트를
  > 나눠 부르므로). 줄어드는 것은 **master EVAL**이다. 톰캣 스레드·커넥션 산정은 **20k rps 이상**
  > 기준이어야 한다. **Sprint 7 용량 산정의 입력값이므로 여기서 못 박는다**
  >
  > HTTP 요청 수를 실제로 깎는 것은 **CDN뿐이다.** WAS-local 캐시는 Redis 왕복만 줄이고
  > HTTP는 그대로다 — D1이 그걸 안 만들기로 한 이유이기도 하다
- **⚠️ 엔드포인트 분할과 "살아있음 확인"이 상충한다.** (초판은 원인을 **캐시**라고 적었다 — 틀렸다.
  **`/status`에는 `tokenId`가 없다.** 캐시가 있든 없든 그 요청만으로는 누가 살아 있는지 알 수단이
  아예 없다. 원인은 캐시가 아니라 **분할 그 자체**(Alternative D)다.)
  → **D1으로 캐시를 빼도 `ka`는 그대로 필요하다.** "캐시를 안 만드니 `ka`도 없앨 수 있다"는
  결론은 성립하지 않는다.
  → 기존 결정 **"SDK가 `ka=1`을 30~60초에 1번만"**이 그대로 해법이다.
  **개인화 비율은 1/2~1/3이다** — 1/15~1/30은 2초 구간(rank≤50 = 50명)에서만 성립하는 수치였고,
  대다수는 20초 구간이라 `ka` 주기(30~60초)와 폴링 주기가 2~3배밖에 차이 나지 않는다.
  귀결: 개인화 트래픽은 500 rps가 아니라 **5,000~7,700 rps**다.
  **단, `inactive_ttl` 회수 배치는 아직 없다** — `last-active` ZSet은 폴링이 쓰기만 하고
  읽는 곳이 0이다(Sprint 7/9)
- **⚠️ ~~장애 시 이 결정의 이득이 사라진다.~~** 🔴 **§36 복귀 폐기로 소멸(2026-08-21).**
  ~~테넌트가 admitToken 소비에 실패해 TTL 만료 → seq 보존 WAITING 복귀가 누적되면~~ **전원이 `mySeq <= watermark`**가 되어 매 폴링이 개인
  엔드포인트 + `pacing` 최저 구간(2초)을 탄다. **분할로 얻은 `MGET` 전환이 통째로 무효화되고
  트래픽 전량이 `EVAL`로 되돌아간다** — 하필 부하가 가장 높을 때.
  → `pacing` 표에 `rank<=0` 구간을 신설할지는 **관측 후 결정(후속)**. 지금 값을 정하면 근거 없는 상수가 된다
- `/status`는 **인증이 필요 없다.** 근거는 "개인 식별자가 없어서"가 아니라 **`queueId`가 대기
  페이지 JS에 박히는 사실상의 공개값이라 인증으로 막을 수 있는 게 없어서**다. 노출되는 것은
  **admit 진행률(`lastAdmittedSeq`)과 `pacing` 표** 두 가지이며, 둘 다 대기자가 알아야 하는 값이다
- **Rate Limit은 걸지 않는다.**
  - 🔴 먼저 사실: `RateLimitFilter`는 **미등록 public 경로를 무조건 통과**시킨다. `/status`에
    `permitAll`만 추가하면 **인증 0 + 제한 0**이 된다. 이걸 모르고 두는 것과 알고 안 거는 것은 다르다
  - **막아야 할 것은 "인증 없는 요청이 MySQL을 때우는 증폭"이고, 그건 D3이 끝낸다.**
    미지 `queueId`는 `MGET`에 포함된 `queue:{q}:seq`가 비어 있으므로 **Redis 1왕복 안에서 404**다.
    **DB로 내려보내지 않는다.** 남는 비용은 값싼 404 하나뿐이고, 그건 `/status` 고유 문제가 아니라
    모든 공개 엔드포인트에 공통인 L7 flood이라 **CDN·WAF 소관**이다
    (초판은 여기서 맵 선적재·주기 리로드·negative cache 부정을 길게 정했다 — **D2·D3으로 전부 소멸**했다)
  - **큐 단위 RL은 오히려 해롭다.** 30만 명이 한 버킷을 공유하므로 남용자 1명이 **정상 대기자
    전원을 429**시킨다 — 이 문서가 스스로 "429는 부하 제어가 아니라 사용자 대기 실패"라고 적은 상태다
  - 남는 위험은 **엣지 대역폭**이고, 그건 CDN 소관이지 앱 필터가 할 일이 아니다
- **미검증**: watermark 후퇴 방지 Lua, **SDK clamp**, **`seq` 키로 미지 큐 404 판정**(D3),
  3키 `MGET`이 실제로 1왕복인지. 전부 구현 시 검증 대상.
  (~~캐시 적중률~~ → D1로 캐시를 안 만들어 대상 소멸. ~~실제 CDN 서빙·쿼리스트링 제외 캐시 키~~
  → CDN 도입 시점(Sprint 11)으로 이월)

### 면접 포인트

> "30만 명이 폴링하면 서버가 못 버티지 않나요?"

폴링 응답에서 **개인화된 값을 전부 걷어냈습니다.** 원래는 "당신 앞에 몇 명"을 서버가 계산해
내려줬는데, 그러려면 사람마다 Redis에 물어봐야 하고 그 조회가 **Lua라 전부 쓰기로 분류돼
master 한 대에 몰립니다.**
그래서 **"마지막으로 입장한 번호"** 하나만 내려보내고, 뺄셈은 클라이언트가 합니다.
전광판과 같습니다 — 은행이 대기자마다 "당신은 15번째"라고 계산해주지 않고 **"현재 47번 처리 중"**
한 줄을 띄우면, 62번 손님이 스스로 15명 남았다고 압니다.

서버가 하는 일은 **키 3개 `MGET` 한 번**으로 줄었습니다. 더 중요한 건 자료구조가 바뀐 건데,
원래는 폴링마다 Lua를 돌려서 **전부 쓰기로 분류돼 master로만** 갔습니다. 지금은 평상시 트래픽이
읽기가 됐습니다. 전원이 같은 응답이라 **나중에 CDN을 앞에 세울 수도** 있는데, 적중률을 아직 못 재서
**캐시는 일부러 안 만들었습니다** — 필요해지면 얹으면 되고, 미리 넣으면 "이미 캐시가 있으니까"로
정작 효과가 큰 CDN 도입이 미뤄집니다.
대가는 **중간에 포기한 사람을 못 세서 순번이 실제보다 크게 보이는 것**인데, 방향이 항상
"생각보다 빨리 입장"이라 감수했습니다.

덧붙여 **폴링 간격 사다리를 응답에 실어 보냅니다.** SDK에 박아두면 장애 때 "전원 간격 2배"를
할 수가 없거든요 — 테넌트들이 각자 SDK를 재배포해야 하니까요. 부하 제어 플랫폼이 부하 제어
레버를 클라이언트에 넘기면 안 된다고 봤고, 그 레버 값이 응답 필드 하나였습니다.

### 🔴 사다리는 `admitToken` TTL과 결합돼 있다 (2026-08-21 추가 실측)

**입장권을 늦게 받는 만큼 쓸 시간이 줄어든다.** 이 절과 §80이 따로 정해 놓은 세 값이 사실 한 덩어리다.

```
admit 발생 ──[유저의 다음 /status 폴링까지]──> admitToken 수령 ──[남은 시간]──> verify
            └─ 이 절의 pacing 구간표가 정한다        └─ §80의 ADMIT_TTL 60초에서 깎인다
```

**핵심은 `rank <= 0`이 admit이 일어난 *뒤*의 상태라는 것이다.** admit 직전 그 사람의 rank는 0이
아니라 **같이 뽑히는 인원수만큼** 크고, **그 rank가 폴링 간격을 정한다.** 즉 Tenant가 한 번에
많이 뽑을수록 뒤쪽 사람의 수령이 늦어진다.

| Tenant의 admit 방식 | 뽑히는 rank 범위 | 적용 구간 | 60초 중 손실 |
|---|---|---|---|
| `count=100` 1회 | 0~99 | 2초(≤50) / **5초**(51~99) | ~8% |
| 100씩 연속 루프로 1,000명 | 0~999 | **5초** | ~8% |
| 〃 5,000명 | 0~4,999 | **10초** | **~17%** |
| 〃 10,000명 이상 | | **15~20초** | **~25-33%** |

`AdmitRequest`의 `@Max(100)`은 **한 번에** 뽑는 것만 막는다. **연속 호출은 막지 않는다** —
폴링 한 주기 안에 admit을 10번 부르면 1,000명을 한꺼번에 뽑은 것과 같다.

**그래서 Platform은 이 손실을 계산할 수 없다.** 크기가 Tenant의 admit 배칭 방식에 달려 있고
Platform은 그걸 모른다. `ADMIT_TTL`이 `private static final` 상수인 채로 "60초 안에 못 오면 탈락"을
판정하면, **Platform이 자기가 만든 지연을 유저에게 청구**하는 셈이 된다.

📌 현재는 `admitToken` TTL 만료가 **WAITING 복귀**(§36)라 이 손실이 드러나지 않는다. 복귀가
결과를 되돌려주기 때문이다. **복귀 정책이 바뀌면 이 결합이 곧바로 유저 이탈로 나타난다.**

⚠️ 세 값이 서로를 모른 채 **다른 층에 산다** — `count` 상한은 API DTO의 `@Max`, 사다리는 Redis
오버라이드 키(큐 단위), TTL은 `QueueEngineService`·`RedisQueueEngine`의 **상수 2중복**
(`QueueEngineService:22` 주석이 중복을 자인한다). 하나를 만질 때 나머지 둘을 같이 봐야 한다.

### Related

- **§80** (Admit) — 사다리가 `ADMIT_TTL`과 결합돼 있다(위 실측). `count` 상한·사다리·TTL 셋은 한 덩어리다
- **§74** (폴링 소유권 검증) — 이 결정이 §74가 만든 폴링 경로의 **응답 계약·엔드포인트를 변경**한다.
  개인 엔드포인트의 `poll_verify.lua` 검증 자체는 그대로 유지된다
- **§36** (admitToken TTL 만료 → **EXPIRED**) — 🔴 **2026-08-21에 복귀가 폐기되어 상태 B가 소멸했다.**
  아래 가드레일·상태 B 서술과 그 미해결 `ErrorCode` 후속은 **더 이상 유효하지 않다**(대상이 없다).
  ~~가드레일과 상태 B가 전적으로 이 결정에 기댄다.~~
  seq 보존 복귀가 없다면 watermark 기준 admit도 안전했을 것이다
- **§70 D10** (Hash Tag) · **§75 D26** (한 큐의 키는 같은 클러스터) — `admit-watermark`·`pacing`·
  admitToken 키 2종·`admit-idem`을 `{queueId}` 슬롯으로 모았다. 이것은 **필요조건**이었고
  **§80이 충분조건을 채웠다** — 중간 DB 확인 삭제 + `verified-token` 폐기로 admit 전 구간이
  Lua 하나에 들어간다
- **§78** (클라이언트 경계) — 이 결정이 §78 표의 "지터를 서버가 응답에 실어 보낸다"를 **뒤집는다**
  (해당 칸에 상호 참조 표시함)
- **§71** (Redis 유실 복구) — watermark는 캐시가 아니라 원본이므로 복구 대상이다
- **§75 D27** — 이 절이 초판에서 정했던 **큐 → 클러스터 라우팅을 §75로 되돌렸다**(D2).
  폴링 경로(`/status`·개인)가 DB 행을 안 읽는다는 사실은 §75에 남겼다
- ~~후속: `QueueSnapshotCache` 제거(도메인 포트 시그니처 변경 + 위 테스트 4건 재작성)~~ **완료(2026-08-19)** —
  Caffeine 의존성도 함께 제거됐다(유일한 사용처였다). `QueueSnapshot` → `QueueBoard`,
  `readSnapshot` → `readStatus`(`Optional`, 미지 큐는 빈 값)
- 🔴 **후속(미해결): `ErrorCode` 신규 추가(404 계약).** ErrorCode만 추가해서는 **아무도 던질 수 없다** —
  복귀 대기자는 `admitted` ZSet에 있는데 멤버가 `"seq|identifier"`라 조회에 `identifier`가 필요하고,
  `seq → identifier` 역방향은 `waiting` ZSet을 통해서만 되는데 그 사람은 거기서 빠져 있다.
  즉 **판정 수단부터 없다.** 자료구조 변경이 함께 필요하며 이 절이 정하지 않은 사항이다
- 🔴 **후속(미해결): 지터 규약이 이 절 안에서 갈린다.** 본문 "간격 = pacing 표 조회 + **±20% 지터**"(대칭)와
  Consequences "지터는 **등급 하한 위로만**"(비대칭, `base ~ base+max(1,base/4)`)은 양립하지 않는다.
  삭제된 서버 구현(`nextPollAfterSec`)은 비대칭이었다. **서버가 간격을 계산하지 않게 된 지금
  어느 쪽도 서버 코드로 강제할 수 없다** — SDK 착수 전에 결론이 필요하다
- ~~후속: ETA(`estimatedWaitSeconds`) 거처~~ → **§81이 닫음(폐기).** 유일한 입력이던
  `avgWaitingTime`을 함께 폐기했고, 응답에 개인화를 되살리지 않는 한 ETA가 앉을 자리가 없다
- 후속: pacing `rank<=0` 구간(관측 후), ~~admit 전 구간 원자성~~ · ~~`verified-token` 클러스터 소속~~
  (**둘 다 §80이 닫음** — Lua 하나 / 키 폐기),
  **CDN 도입 시 `Cache-Control max-age` 값 결정**(Sprint 11 — D1이 지금은 안 붙이기로 한 것)
- **소멸한 후속**(2026-08-17): ~~`/status` 캐시 TTL~~(D1 — 캐시를 안 만든다. CDN 시 `max-age`로 부활) ·
  ~~맵 리로드 주기~~(D2·D3) · ~~`pacing` "즉시 변경" ↔ "캐시 미스 때만" 모순~~(캐시가 없으니 **진짜 즉시**다.
  CDN 도입 시 `max-age`만큼의 지연으로 부활)
- 🔴 **후속 — 큐 상태 키의 회수 경로가 거의 없다 (회수 배치는 Sprint 9)**
  - ✏️ **정정(2026-08-20).** `queue:{q}:tokens` Hash의 `HDEL`이 **0건이었던 것은 §80 구현 전까지**다.
    지금은 `cleanupCompleted`(complete 경로)에 있고, **거기서 맨 마지막 명령**이다(§80 구현 결과 ①).
    남은 누수는 **complete하지 않고 떠난 사람**이다 — 이탈·TTL 만료 경로가 아직 없어
    그만큼 필드가 영구 축적된다. 큐 상태 키에 `EXPIRE`는 여전히 **0건**이다
  - `queue:{q}:last-active` ZSet — `ZREM`이 0건. 폴링이 쓰기만 하고 읽는 곳이 없다(§74 Consequences)
  - 🔴 **§36 폐기 후 의존 방향이 바뀌었다.** 이제 claim 잡이 그 필드를 **직접 `HDEL`한다** —
    만료자의 중복 게이트를 푸는 유일한 주체다. 회수 배치(§82)는 그 사람들을 건드리지 않는다
    (`waiting`에 없어 identifier 역산이 안 된다)

---

## §80 — Sprint 7 Admit: 전 구간 원자 Lua + 동기 응답 (`verified-token`·`admit_requests` 폐기)

**결정일**: 2026-08-17. **구현 완료 2026-08-20** (`dev` `ba21221`, PR #31~38 — 아래 "구현 결과" 참조).
§79가 "pop 성공 + 토큰 SET 실패 창은 미해결"로 남긴 것과, `FRS §6.4`가 3단계(Lua → DB 확인 → SET)로
적어둔 흐름을 닫는다.

### Decision

**admit은 `queues` 행 하나를 읽고, Lua 하나를 돌리고, Kafka에 알리고 응답한다.**

```
① queues 행 읽기 (Tenant 소유권 검증)      ← tokens 행은 읽지 않는다
② EVAL admit.lua                            ← 전 구간 원자. Redis 밖 호출 0회
     ZPOPMIN queue:{q}:waiting N            → (identifier, seq) N쌍
     HGET   queue:{q}:tokens identifier     → "tokenId|issuedAt"
       └ 미스/레거시(구분자 없음)면 ZADD로 원래 seq에 되돌리고 건너뛴다
     SET    queue:{q}:admit-by-token:{tokenId}   PX 60000   ← 동적 키. 접두사는 Java가 ARGV로
     SET    queue:{q}:admit-by-admit:{admitToken} PX 60000  ← 동적 키. 접두사는 Java가 ARGV로
     ZADD   queue:{q}:admitted  {만료 epoch ms}  "seq|identifier"
     watermark 조건부 갱신 (현재값보다 클 때만, §79)
     queue:{q}:admit-idem:{requestId} 에 결과 payload 저장 (재시도 시 REPLAY)
③ Kafka publishAll — ADMITTED × N (key = tokenId)
④ 200 { admitted: [...] }
```

**200이 보장하는 것** = "이 사람들은 대기열에서 빠졌고 admitToken을 쥐었다" (Redis의 사실).
**보장하지 않는 것** = "`tokens.status`가 이미 1이다" (Kafka 소비 후에 그렇게 된다).

**확정 결정**

| 항목 | 결정 |
|---|---|
| verify · complete | **분리 유지** |
| `verified-token:{tokenId}` | 🔴 **폐기.** 대신 complete를 관대하게 — `WHERE token_id = ? AND admit_token = ? AND status IN (0, 1)` + `admitted_at` 유효 창 |
| TTL 만료 후 verify | **404** |
| `admit_requests` 테이블 | 🔴 **폐기** (`schema.sql`에서 삭제) |
| `admit.lua` 동적 키 조립 | **Java가 접두사까지 만들어 ARGV로 넘긴다** — `QueueKeys.admitByTokenPrefix(queueId)` 등. Lua는 `prefix .. tokenId`만 한다. Lua 파일에 접두사 리터럴 박기(B안)는 기각 (⑥) |
| TTL 만료 트리거 | **`queue:{q}:admitted` ZSet + claim-Lua** (score = 만료 시각). ~~→ WAITING 복귀~~ → **EXPIRED + `HDEL tokens`**(§36) |
| claim-Lua 실행 주체 | **`queue-batch`** — actuator·micrometer 추가 완료(`6647ca5`). `TokenReclaimJob`, 주기 10초 `fixedDelay` |
| claim-Lua의 leader election | **쓰지 않는다.** `EVAL` 자체가 claim이다 — `CLAUDE.md` "`@Scheduled` 단독 금지" 규칙의 **명시적 예외** (⑧) |
| `tokens.admitted_at` | **추가** |
| Kafka 이벤트 타입 | **판별 필드**(한 토픽·한 스키마 안에서 분기) |
| ~~`ADMIT_ISSUED → CANCELLED`~~ | 🔴 **무의미해졌다 — Cancel API를 만들지 않는다(§82).** 이탈은 admitToken TTL 만료 → WAITING 복귀 → `inactiveTtl` 판정을 거친다 |
| ~~복귀가 `last-active`를 리셋하는가~~ | **안 한다** → 🔴 **§36이 복귀 자체를 폐기(2026-08-21)해 논점 소멸.** claim 잡은 남고 마지막 동작이 `ZADD waiting` → `HDEL tokens`로 바뀐다 |
| `count` 상한 | **100.** `@Max(100)` Bean Validation 한 줄 — 전용 검증 클래스를 만들지 않는다 (⑦). ⚠️ **한 번에** 뽑는 것만 막는다 — 연속 호출은 안 막으므로 폴링 한 주기에 수천 명이 뽑힐 수 있고, 그만큼 뒷사람의 입장권 수령이 늦어져 TTL에서 깎인다 (**§79의 결합 절**) |
| 포트 rename | **미룬다** (순수 미용) |
| admit이 `last-active` 정리 | **Sprint 9 회수 배치에서 일괄** |

**상태 전이 = Kafka 소비 측 가드 (전부 key = `tokenId`)**

| 이벤트 | 허용 출발 | SQL |
|---|---|---|
| `ENQUEUED` | (신규) | `ON DUPLICATE KEY UPDATE token_id = token_id` (현행 no-op) |
| `ADMITTED` | 0 | `IF(status = 0, 1, status)` |
| ~~`RETURNED`~~ | — | 🔴 **발행처가 없다(§36 복귀 폐기).** 만료자는 `status = 1`로 남는다 — `EXPIRED` 가드가 `status = 0` 전용이라 no-op이고, 그것이 `complete`의 300초 창을 살린다 |
| `COMPLETED` | 1 | `IF(status = 1, 2, status)` |
| ~~`CANCELLED`~~ | — | 🔴 **발행처가 없다(§82).** `TokenJpaAdapter`의 맵 엔트리는 status 재번호 비용 때문에 남겨 둘 뿐이다 |
| `EXPIRED` | 0 | `IF(status = 0, 4, status)` |

**🔴 이 UPSERT에 함정이 둘 있다. 둘 다 조용히 깨진다.**

1. **MySQL ODKU의 `SET` 절은 좌 → 우로 평가된다.** `status`를 먼저 쓰면 다음 줄이 **이미 갱신된
   값**을 보므로 `IF(status = 1, VALUES(admit_token), admit_token)` 같은 식이 안 걸려
   **`admit_token`이 영원히 NULL**로 남는다. → **`status` 갱신을 항상 마지막에 둔다.**
2. **ODKU 절에 `?` 플레이스홀더를 쓰면 `rewriteBatchedStatements`가 조용히 꺼진다**
   (Connector/J `QueryInfo.java:168` — ODKU에 파라미터가 있으면 재작성을 포기한다).
   **500건 배치가 500왕복으로 퇴화하는데 예외도 로그도 없다.**
   ✏️ **구현에서 정정됐다(2026-08-20).** 여기 적었던 `VALUES(admit_token)`은 MySQL **8.0.20부터
   deprecated**라 서버(8.0.46)가 **사용 1회마다 경고 1287**을 돌려준다(실측: 한 문장에 2회 → 2건).
   그래서 구현은 **`VALUES (...) AS new` 별칭**을 쓰고 값 참조를 `new.col`로 한다.
   ⚠️ 별칭을 붙이면 ODKU 안의 맨 컬럼명이 **모호해진다** — `status = IF(status = 0, ...)`은
   `ERROR 1052 Column 'status' is ambiguous`로 실패한다(실측). **기존 행은 `tokens.`, 새 값은
   `new.`로 전부 한정**할 것. `AS` 절은 Connector/J가 VALUES 절의 끝으로 인식하므로 재작성은 유지되며,
   `TokenUpsertRewriteTest`가 그것을 왕복 횟수로 못박는다.

**관측 — 8개 후보에서 2개 + 조건부 1개. 🔴 셋 다 아직 미구현이다** (2026-08-20 `grep` 0건 —
계측 코드가 없고, 현재 남는 신호는 `publishAdmitted`의 ERROR 로그와 기본 `http_server_requests`뿐이다)

| 메트릭 | 라벨 |
|---|---|
| `queue_admit_requests_total` | `queueId`, `result` = `ok\|empty\|replay\|error` |
| `queue_admit_tokens_issued_total` | `queueId` |
| (조건부) `queue_admit_returned_to_waiting_total` | `queueId` — 복귀 배치 구현 시 |

뺀 것과 이유: **`admit_seconds` 히스토그램** — 기본 `le` 버킷이 **실측 69개**라 큐 100개면
6,900 시계열이고 이는 현재 전체 시계열 857개의 **8배**다 / **`idem_replay`** — `result=replay`
라벨이 흡수 / **`last_request_timestamp`** — 카운터 증가율로 같은 알람이 된다 /
**`orphan`** — 정의가 없다 / **`complete_missing`** — 실시간 계측이 불가능하다.

> 🔴 **메트릭보다 먼저인 것이 있다.** 현재 **알람 규칙 0개, Alertmanager 미실행, 익스포터 0개**다.
> 즉 **위기 A(Redis 포화)는 admit 메트릭을 몇 개 만들어도 안 보인다.**
> 우선순위: **redis_exporter > 알람 규칙 > `queue-batch` actuator > access log > 커스텀 메트릭.**

**착수 전 검증 2건** — ①은 **미검증**, ②는 **실증 완료(2026-08-17)**

- ⬜ **admit Lua의 동적 키가 Cluster에서 도는가.** 키가 런타임에 정해지므로(`{tokenId}`·`{admitToken}`)
  `KEYS[]` 선언이 불가능하다. 해시태그가 같아 이론상 같은 슬롯이지만 **로컬 Cluster A(7001-7008)에서
  실제로 돌려봐야 한다. Sentinel로는 절대 안 잡힌다**(§70 D10)
  - **기전은 2026-08-18 실측으로 규명됐다**(Cluster A, Redis 7.0.15): 선언 없는 키 접근은 CROSSSLOT이
    아니라 `ERR Script attempted to access a non local key`이고, **슬롯이 달라도 같은 노드면 조용히
    성공한다**(마스터 4대 = 약 25%). 그래서 이 검증의 통과는 **안전의 증거가 아니다** — 진짜 방어는
    A안(⑥) + `QueueKeysSlotTest`의 슬롯 동일성 단언이다
  - 남은 것은 **A안으로 조립한 실제 `admit.lua`를 Cluster A에서 돌려보는 것**이다
- ✅ **`ALGORITHM=INSTANT`는 파티션 테이블 `ADD COLUMN`에서 된다 — 실증 완료(2026-08-17 22:26:27 KST).**
  `ALTER TABLE tokens ADD COLUMN admitted_at DATETIME(3) NULL AFTER issued_at, ALGORITHM=INSTANT` 가
  MySQL **8.0.46**(master 3306)에서 성공했다. 근거는 master binlog `master-bin.000427` 의 `error_code=0`
  기록이다 — `ALGORITHM=INSTANT` 는 미지원이면 **ER 1845/1846으로 실패해 binlog에 남지 않으므로 기록 자체가 증명**이다.
  master·replica 양쪽에서 컬럼 존재도 확인했다. **13개 파티션 재구축·replica 지연은 발생하지 않는다.**
  단 (a) 마지막이 아닌 위치의 INSTANT `ADD COLUMN` 은 **MySQL 8.0.29+** 이고,
  (b) 실증 당시 `tokens` 는 0행이라 대용량에서의 MDL 배타 락 대기까지 잰 것은 아니다.
  ⚠️ §75의 `ALTER COLUMN ... DROP DEFAULT`(4639행 부근)는 **다른 연산**이다. 그 서술을 이 근거로 쓰지 마라.

### 구현 결과 (2026-08-20 · `dev` `ba21221` · PR #31~38)

**설계대로 들어간 것**은 다시 적지 않는다. 아래는 **설계와 달라진 3건 + 설계에 없던 사실**이다.

**① 중복 게이트가 `ZADD NX` → `HSETNX`로 옮겨졌다** (`c748fe6`)

`enqueue_bulk.lua`의 신규/기존 판정이 `waiting` ZSet이 아니라 **`tokens` Hash의 필드 존재**다.
`waiting`으로 판정하면 **admit된 사람이 게이트를 통과**한다 — admit이 `ZPOPMIN`으로 그를 빼갔기
때문이다. 그 사람의 재-enqueue는 새 `tokenId`·새 `seq`를 받고, 결과는 셋이다:
폴링 404(옛 tokenId로 물어보므로) · `billing_snapshots`의 `COUNT(*)` **과금 중복** · `status=1` 고아 행.

> 🔴 **그래서 사람을 큐에서 빼는 경로는 반드시 `HDEL`한다.** 현재 그 경로는 `cleanupCompleted`
> 하나이고, 거기서 `HDEL`은 **맨 마지막 명령**이다. 이 메서드는 Lua가 아니라 개별 명령 4개라
> 중간에 죽을 수 있고 **순서가 결과를 가른다**:
> `HDEL`이 먼저면 Hash만 사라지고 `waiting`에 남아 `poll_verify`가 HGET 미스로 계속 0을 반환한다
> → **그 사람은 영영 404이고 복구 경로가 없다.** 마지막이면 재입장만 막히고 다음 complete·회수
> 배치로 해소된다. "정리 순서 통일" 같은 이유로 위로 올리지 말 것.

**② verify가 DB를 한 번도 읽지 않는다** (`c748fe6`)

`admit-by-admit`의 값이 `tokenId` → **`"tokenId|identifier"`**로 넓어졌다. verify가 돌려줄 값은
identifier인데 tokenId만 담으면 그걸 **DB에서만** 얻을 수 있고, 그러면 **컨슈머 백로그 구간의
정상 토큰이 404**가 된다(행이 아직 없으므로). 그 값은 admit 시점에 이미 손에 있었다.
읽는 쪽은 **첫 `'|'`로만** 쪼갠다 — identifier는 Tenant 자유 문자열이라 `'|'`가 들어올 수 있고
`tokenId`(`tok_`+UUID)에는 없다. DB fallback은 **Redis 미스와 구 포맷**에만 남았다.

**③ `publishAdmitted`가 첫 발행 실패에서 끊는다**

발행은 건별 `.get(12초)` 블로킹이라, 브로커 무응답 시 `count=100`짜리 admit 한 건이 **최대 20분**
요청 스레드를 잡는다. 첫 건이 시한을 다 쓰고 실패했다면 나머지 99건도 같은 브로커를 기다릴 뿐이다.
병렬 발행으로는 못 고친다 — 메타데이터가 없으면 `send()` 자체가 블로킹이라 스레드만 늘고 벽시계는 그대로다.

> ⚠️ **건너뛴 분은 자동으로 복구되지 않는다.** admit은 발행이 실패해도 200을 주므로 **Tenant에게
> 재시도할 이유가 없다.** "복구는 REPLAY"는 Tenant가 **마침** 같은 `requestId`로 다시 불렀을 때만
> 성립하는 **가능성이지 경로가 아니다.** 그래서 건너뛴 건수와 첫 `tokenId`를 ERROR로 남긴다 —
> 그 로그가 유일한 흔적이다.

**④ 🔴 `ApiKeyAuthenticationFilter` 화이트리스트 누락 — admit·verify·complete가 전부 401이었다** (`28106ba`)

`shouldNotFilter`가 **화이트리스트**라, 이 결정이 컨트롤러 3개를 추가했는데 조건이 `enqueue`
하나에 머물렀다. FRS §4가 X-API-Key로 명세한 세 경로가 **전부 401**이었고, **JWT Bearer로는
통과해서 테스트가 잡지 못했다.** 새 엔드포인트를 추가하면 컨트롤러의 `@*Mapping`을 전수로 세어
대조할 것.

> 🪤 **인증 주체가 경로마다 다르다.** 아래 둘은 이 필터를 **타면 안 된다** —
> `GET /{queueId}/tokens/{tokenId}`(폴링)는 **유저가 직접** 부르고 API Key가 없으며,
> `GET /{queueId}/status`는 `permitAll`이다. 그래서 `/tokens/{tokenId}/complete`와
> `/tokens/{tokenId}`를 구분해야 하고, **정규식이 뭉개지면 폴링이 401**이 된다.
> 아직 미구현인 `DELETE /{queueId}/tokens/{tokenId}`(이탈)도 착수 시 같은 함정에 걸린다.

**⑤ `CLAIM_LIMIT = 500` 적체는 실재한다 — 그리고 조용하다**

통합테스트에서 **14,747건이 약 30주기(≈300초)에 걸쳐** 복귀했다. 한 주기(10초)에 큐당 500건이
상한이기 때문이다. **에러도 경고도 없이 지연만 늘어난다** — 관측 없이는 보이지 않는다.
올릴 값은 맞지만, 올리면 그만큼 Redis 단일 스레드를 오래 잡으므로 **폴링 p99와 함께** 재야 한다
(⑦의 `count` 상한과 같은 기준).

**⑥ 통합테스트 실측 (2026-08-20)**

| 확인 | 결과 |
|---|---|
| 8만 건 enqueue — Redis · DB · 응답 **3자 교차대조** | `tokenId`·`seq`·`issuedAt` **불일치 0** |
| admit된 사람의 재-enqueue | 같은 `tokenId` 반환, `tokens` 행 **+0** (게이트 동작) |
| 혼합 청크 500건(신규 + admit된 사람 섞임) | **전부 200** — `ZRANK` nil 가드가 배열 절단을 막았다 |
| complete 후 재-enqueue | **새 `tokenId`** — `HDEL`이 게이트를 연다 |
| ~~TTL 만료 복귀~~ | **60.9초 후**, score가 **원래 seq 그대로** — ⚠️ **옛 복귀 정책(v1.9) 하의 측정치다.** §36이 복귀를 폐기해 지금은 `HDEL tokens` 후 종료다. **만료 감지 시각(≈61초)만 여전히 유효**하다 |
| ~~`admit-by-token` 만료 ~ 복귀 사이 404 창~~ | **≈ 1초** (이론 최악은 배치 주기 10초) — ⚠️ **옛 정책 하의 측정치다.** 복귀가 없어져 그 창은 소멸했고, 만료 뒤 404는 **종단 상태**다(재접속 → 재-enqueue) |
| ~~`RETURNED` 가드~~ | 🔴 **§36 폐기로 대상 없음.** 대신 **만료자의 `status`가 `1`로 남는지**와 `HDEL tokens`로 재-enqueue가 새 seq를 받는지를 검증한다 |

**⑦ 남은 것**

- ⬜ **착수 전 검증 ①은 여전히 미검증이다.** A안으로 조립한 실제 `admit.lua`를 로컬 Cluster A
  (7001-7008)에서 돌려본 적이 없다. **구현이 끝난 것과 이 검증은 별개다** — 그리고 통과해도
  안전의 증거가 아니다(슬롯이 달라도 같은 노드면 약 25% 확률로 조용히 성공)
- ⬜ **관측 메트릭 3종 미구현** (위 표)
- ✅ ~~`DELETE /tokens/:tokenId`(이탈) 0건~~ — **§82가 만들지 않기로 확정**했다. 이탈 회수는
  `inactiveTtl` 판정 배치가 전담하며 **구현 완료**(`inactive_expire.lua` · `TokenReclaimJob`)

### Rationale

**① 중간 DB 확인(②단계)을 없앤 것이 이 결정의 전부다.**

개정 전 `FRS §6.4`는 `Lua pop` → `DB WAITING 확인, 불일치 즉시 ZREM` → `토큰 SET` 3단계였다.
이 중간 단계가 **enqueue의 저장 순서와 정면으로 충돌한다.** §71 D11이 확정한 것은
**"Redis 먼저, DB 나중"**이고 그 사이에는 Kafka 소비 지연만큼의 창이 있다. admit이 그 창에
들어온 사람을 "DB에 없으니 불일치"로 판정해 `ZREM`하면 — **아직 적재되지 않았을 뿐인 정상
대기자를 영구 삭제한다.** 되돌릴 수단도 없다(대기열에서 사라졌고 DB에도 없다).

**대상 선택의 권위는 Redis다**(§71 D11). DB는 그 권위를 검증할 자격이 없다. 이걸 인정하면
②는 존재할 이유가 없고, 없애면 **거를 게 없어져 `ZRANGE + ZREM`이 `ZPOPMIN N` 한 명령**이 된다.
그래서 "전 구간 원자"가 목표여서 Lua가 하나가 된 게 아니라, **거르지 않기로 하니 저절로 하나가 됐다.**

**② 동기 응답이 성립한다 — admit은 저빈도다.**

admit은 Backpressure Pull이라 Tenant가 슬롯이 빌 때만 부른다(설계 목표 10 rps). 폴링(15k rps)과
성격이 다르다. 저빈도이므로 **응답을 기다려도 되고**, 그러면 `admitTokens` 목록을 같은 요청에
실을 수 있다. 알려진 결함 — **"admit 응답이 목록을 약속하는데 처리는 비동기"** — 이 그대로 소멸한다.
비동기로 만들 이유가 성능이었는데, 저빈도 경로에 그 대가(멱등 테이블·상태 폴링·응답 계약 모순)를
치를 필요가 없다.

**③ 순서 역전 방어는 파티션이 아니라 DB의 조건부 쓰기다.**

같은 `tokenId`면 같은 파티션이니 순서가 보장된다 — **고 믿으면 안 된다.** 프로듀서가 여러 WAS라
브로커 도착 순서가 뒤집힐 수 있다. 특히 **`ZADD`(enqueue Lua)가 Kafka 발행보다 먼저**이므로
"아직 `ENQUEUED`가 안 실렸는데 그 사람이 admit되는" 창이 실재한다.
그래서 순서를 **파티션에 기대지 않고** 위 가드 표의 조건부 UPSERT로 막는다.
`ENQUEUED`의 no-op upsert가 이미 그 역전을 흡수한다 — 늦게 온 `ENQUEUED`는 행을 만들되
`status`를 건드리지 않는다.

**④ `verified-token` 폐기 — 목적이 이미 사라져 있었다.**

이 키의 원래 목적은 **"admit이 verified 상태인 토큰을 대상에서 제외한다"**였다(§13 P1-③).
그런데 위 ①에서 **admit이 그 조회를 하지 않기로** 했다. 읽는 곳이 없는 플래그는 플래그가 아니다.

남은 명분은 "verify 없이 complete를 호출하는 것을 서버가 거절한다"인데, 이건 §35의
**괄호 안 예시**가 `FRS §12` 제약표로 승격된 것이고 **독립적인 실효가 없다** —
complete 자체가 `admit_token`을 검증하므로, verify를 건너뛴 호출도 **어차피 정당한 토큰을
가진 정당한 호출**이다. 거절할 근거가 없다.

대신 complete를 **관대하게** 만든다: `WHERE token_id = ? AND admit_token = ? AND status IN (0, 1)`.
`0`을 허용하는 이유는 **TTL 만료로 WAITING에 복귀했지만 Tenant는 이미 입장시킨** 경우가 실재하기
때문이다. `admitted_at` 유효 창으로 무한 소급은 막는다.

**⑤ `admit_requests` 폐기 — 동기 + Lua면 "미실행 상태"가 없다.**

이 테이블의 존재 이유는 "요청은 받았는데 아직 처리 안 됐다(PENDING)"를 기록하는 것이었다.
동기 처리에는 그 상태가 없다 — 성공했거나 예외가 났거나다. 멱등은 `admit-idem` 키가 payload를
들고 있어 REPLAY로 답한다.

감사 기록으로서도 **반쪽이다**: 흐름이 `Lua 성공 → INSERT`라 **실패한 요청은 애초에 안 남는다.**
남는 건 성공 기록뿐인데 그건 Kafka 이벤트와 메트릭이 이미 갖고 있다.
**과금 근거도 아니다**(과금은 enqueue 수 기준). ~~장기 이력은 `queue_daily_stats.total_admit_count`~~
→ **§86에서 정정**: 그 컬럼은 `total_admit_issued`(발급된 입장권 수)가 됐다. admit **호출 수**는
`tokens`에서 뽑을 수 없으므로 **장기 이력으로 남지 않는다** — 필요하면 Micrometer 카운터다.

**⑥ 동적 키의 접두사는 Java가 만든다 — Lua에 문자열을 박지 않는다.**

`admit.lua`가 건드리는 키 셋(`admit-by-token:{tokenId}` · `admit-by-admit:{admitToken}` ·
`admit-idem:{requestId}`)은 두 번째 조각이 **런타임에 정해진다.** 그래서 `KEYS[]`에 미리 선언하는
것이 원리적으로 불가능하다. 그러면 접두사를 누가 만드느냐가 남는데 — **Java가 만들어 ARGV로
넘기고 Lua는 `prefix .. tokenId`만 한다(A안).** 접두사 문자열을 `.lua` 파일에 박는 B안은 기각한다.

**왜 이게 취향 문제가 아닌가.** 2026-08-18 로컬 Cluster A(Redis **7.0.15**) 실측 두 가지:

1. **`CROSSSLOT` 사전 검사는 선언된 `KEYS`에만 걸린다.** 선언하지 않고 접근한 키는 다른 에러가
   난다 — `ERR Script attempted to access a non local key`. 즉 런타임 검사는 슬롯이 같은지가
   아니라 **이 노드가 그 키를 소유하는지**만 본다.
2. 그래서 **슬롯이 달라도 그 노드가 우연히 소유하고 있으면 조용히 성공한다.** 마스터 4대면
   약 25%다. 리샤딩이나 failover로 슬롯이 옮겨가는 그날 처음 깨진다.

`enqueue_bulk.lua`는 3키를 `KEYS`로 선언하므로 CROSSSLOT이라는 그물이 있다. **`admit.lua`에는
그 그물이 없다.** 남는 방어는 `QueueKeysSlotTest`의 슬롯 동일성 단언 하나뿐이고, 그 테스트는
`QueueKeys`의 public static 팩토리를 **리플렉션으로 전수 열거**해서 새 키가 추가돼도 자동으로
걸리게 만든 것이다. B안이면 접두사가 Java 밖에 살아 **그 전수 단언이 닿지 않는다** — 해시태그를
빠뜨린 접두사가 단위 테스트를 통과하고, Sentinel을 통과하고, Cluster에서도 25% 확률로 통과한다.
**그물 셋이 동시에 샌다.**

대가는 ARGV가 늘고 Lua가 문자열 결합을 한다는 것이다. 접두사 조립 규칙이 `QueueKeys` 한 곳에만
사는 값이 그보다 크다. 완성된 키가 필요한 경로(폴링의 `admit-by-token` 조회, verify의
`admit-by-admit` 조회)도 **접두사 메서드를 재사용해서** 만든다 — 두 벌로 만들면 단일 출처가 깨진다.

**⑦ `count` 상한은 100에서 시작한다 — "견딜 수 있는 최대"가 아니라 "필요를 채우는 최소"다.**

**상한 변경은 비대칭이다.** 올리는 것은 하위호환이라 기존 호출자가 그대로 돌지만, **내리는 것은
파괴적 변경**이다 — 어제 되던 호출이 오늘 400이 된다. 그래서 시작값을 근거 없이 크게 잡으면
되돌릴 수 없고, 시작값은 수요를 채우는 최소여야 한다.

- **기준점**: `enqueue_bulk.lua`는 `CHUNK_SIZE = 500`으로 총 `redis.call`이 약 2,001회다.
  임시값 1,000은 `1 + 4N ≈ 4,004`로 **이미 검증된 최대 배치의 2배**였다. 100이면 ≈ 401로 그 1/5.
- **수요**: 30만 명을 2시간에 소진하면 평균 **42/s**다. cap 100이면 admit 10 rps(설계 목표)로
  **1,000/s = 필요치의 24배**. 모자랄 이유가 없다.
- **이중 제어**: admit은 `X-API-Key` 경로라 `RateLimitFilter`의 Tenant Plan 토큰버킷이 함께
  걸린다. cap은 **한 요청의 크기**를, 토큰버킷은 **요청의 빈도**를 막는다.
- **구현은 `@Max(100)` 한 줄이다.** 전용 검증 클래스를 만들지 않는다 — 넘으면 400이면 되고
  그건 Bean Validation의 기본 동작이다.

**실측은 "올릴 근거"로만 쓴다.** 그리고 그때 재야 하는 것은 admit 단독 지연이 아니다 —
**같은 노드에 폴링 부하를 걸어놓고 폴링 p99가 얼마나 밀리는가**를 봐야 한다. 문제는 admit이
느린 것이 아니라 **이웃을 밀어내는 것**이기 때문이다.

**⑧ 복귀 claim-Lua는 ShedLock을 쓰지 않는다 — `EVAL` 자체가 claim이다.**

`CLAUDE.md`는 "`@Scheduled` 단독 금지, leader election 필요"라고 적고 있고 `CONCURRENCY.md`
매트릭스도 스케줄러를 ShedLock 칸에 넣었다. **이 잡은 그 규칙의 명시적 예외다** — 근거를 여기
남기지 않으면 리뷰에서 규칙 위반으로 지적돼 필요 없는 ShedLock이 들어온다.

`ZRANGEBYSCORE admitted 0 now` + `ZREM`을 **한 Lua 안에** 두면 Redis 단일 스레드가 그 둘을
쪼개지 않는다. `queue-batch` 3대가 같은 초에 깨어나도 멤버를 가져가는 것은 한 대뿐이고 나머지는
**빈 배열**을 받는다. 중복 실행의 대가는 낭비된 `EVAL` 한 번이지 중복 복귀가 아니다.
동시성 사다리에서 **2단(Redis 단일 키 원자 연산)이 5단(분산 락)을 이긴다** — `CLAUDE.md`가 정한
우선순위 그대로다. 리더 선출을 얹으면 락 획득·갱신·만료라는 실패 모드가 새로 생기고 얻는 것은 없다.

ShedLock이 필요한 잡은 **원자 claim이 불가능한 것들**이다 — 파티션 DROP, 월간 스냅샷처럼 DB
여러 문장에 걸쳐 있어 "집었다"를 한 명령으로 표현할 수 없는 작업. 그 구분이 기준이다.

⚠️ **단, 큐 목록 열거는 DB `queues`에서 한다.** Cluster에서 `SCAN queue:*:admitted`는 **접속한
노드만** 훑으므로 마스터마다 따로 돌려야 한다. 단일 노드를 가정하고 짜면 다른 마스터에 사는 큐가
**조용히 누락되고, 복귀되지 않은 토큰은 아무 에러도 내지 않는다.** DB에서 큐 목록을 읽으면 이
문제가 원천에서 사라진다.

### Consequences

- **좀비가 admit 한 자리를 낭비한다.** 브라우저를 닫은 사람도 대기열에 남아 있으므로 뽑힌다.
  10개 요청하면 토큰 10개가 나가지만 실제 입장은 9명일 수 있다. **탐지는 관측으로 한다** —
  `tokens_issued_total` 대비 complete 수의 격차가 그 값이다. ②를 없앤 대가이며, 되살려도
  해결되지 않는다(DB에도 좀비인지는 안 적혀 있다)
- **Redis 멱등키가 유실되면 중복 admit을 감지할 수단이 없다.** `admit_requests`의
  `UNIQUE (request_id)`가 마지막 방어선이었는데 폐기했다. Redis 전손 시 같은 `requestId`
  재시도가 두 번 실행된다 — **T1의 대가로 명시적으로 수용한다**
- **③ Kafka 발행이 실패해도 200을 준다.** Lua는 이미 커밋됐고 되돌릴 수단이 없다. 여기서 5xx를
  주면 Tenant가 재시도하는데, `admit-idem`이 REPLAY로 같은 답을 돌려줄 뿐 **Kafka는 여전히 안 간다** —
  상태가 나아지지 않는 무한 반복이다. 실패는 로그와
  `queue_admit_requests_total{result=error}`로 남긴다. enqueue가 발행 실패를 QE001(503)으로 돌리는
  것과 반대인데, enqueue는 **아직 아무것도 확정되지 않은** 시점이라 거절이 성립하기 때문이다

  > 🔴 **정정 (2026-08-19).** 이 항목은 원래 *"미반영의 피해는 complete가 `status IN (0, 1)`로
  > 관대한 덕에 이미 흡수하도록 설계돼 있다"*고 적었다. **거짓이다.** complete의 술어에는
  > `admitted_at > UTC_TIMESTAMP(3) - INTERVAL {유효 창} SECOND`가 함께 있고, **`admitted_at`을
  > 채우는 유일한 경로가 바로 그 실패한 `ADMITTED` 이벤트**다. 값이 NULL이면 `NULL > x`가 NULL이라
  > 행이 제외되므로 `status IN (0, 1)`의 관대함은 여기 닿지 못한다.
  >
  > **수용하는 대가**: `ADMITTED` 발행이 실패한 토큰은 admit 200을 받았고(사용자는 입장했고),
  > verify는 60초간 통과하지만(Redis 히트 경로는 `admitted_at`을 보지 않고, `admit-by-admit`
  > 값이 `"tokenId|identifier"`라 DB를 아예 읽지 않는다 — §6.5),
  > **complete는 영구히 404다.** `tokens.status`는 0에 남고 그 자리는 회수되지 않는다.
  >
  > 같은 뿌리의 두 번째 창이 있다 — **`ADMITTED` 소비 전(컨슈머 랙 · replica 랙)에도 complete가
  > 404**다. complete는 `tokens` 행이 있어야 하고 그 행은 컨슈머가 만든다.
  > (verify는 §6.5 변경으로 Redis 히트 시 DB를 아예 읽지 않아 **이 창을 타지 않는다.**
  > Redis가 키를 잃었을 때의 DB fallback만 `readOnly = true`로 replica 랙에 걸린다.)
  >
  > 발행은 동기 `ack=all` + `enable.idempotence`라 실패율이 낮고, 실패해도 200을 주는 쪽이
  > 무한 재시도보다 낫다는 판단은 유지한다. **다만 "흡수된다"는 근거는 철회한다.**
  > 실제 발생률은 통합테스트에서 관측한다
  >
  > ✏️ **구현에서 하나 더 붙었다(2026-08-20).** `publishAdmitted`는 **첫 발행 실패에서 끊는다**
  > (건별 12초 블로킹 × 100건 = 최악 20분). 그래서 피해가 1건이 아니라 **그 요청의 나머지 전부**로
  > 번질 수 있다. **REPLAY를 복구 경로라고 적지 마라** — admit은 실패해도 200이라 Tenant가
  > 재시도할 이유가 없다. REPLAY는 경로가 아니라 **가능성**이고, 남는 흔적은 ERROR 로그뿐이다
- **`count` 상한이 필요하다.** Redis는 단일 스레드이고 `ZPOPMIN N` + `N × (HGET + SET·SET·ZADD)`가
  한 스크립트 안에서 돈다. `N = 10,000`이면 **수십~100ms 블로킹**이고, 그동안 폴링을 포함한
  모든 명령이 대기해 **p99가 연쇄로 무너진다.** **상한은 100**이다(⑦). 올릴 때 재야 하는 것은
  admit 단독 지연이 아니라 **폴링 부하를 함께 건 상태의 폴링 p99 증가분**이다
- **`0 → 1 → (TTL 만료) → 0` 왕복 뒤에 옛 `ADMITTED` 이벤트가 재전달되면** 낡은 토큰으로 다시
  `1`이 된다. 가드가 `status = 0`만 보고 **어느 세대의 admit인지는 모르기 때문**이다.
  60초를 넘겨 재전달돼야 하므로 희박하다. 막으려면 **버전(세대) 컬럼**이 필요하다 —
  **지금은 만들지 않고 적어만 둔다**(과설계 방지). 관측에 잡히면 그때 판단한다
- **`tokens` Hash의 레거시 값**(구분자 `|` 없이 `tokenId`만 저장된 항목)은 `issuedAt`을 복원할 수
  없다. `tokens` 테이블의 PK가 `(token_id, issued_at)`이라 `issuedAt` 없이는 행을 특정하지 못한다.
  → **원래 seq로 `ZADD` 되돌린 뒤 건너뛰고, reconciliation에 맡긴다.** admit 경로에서 추측해
  만들어 넣으면 중복 행이 생긴다.
  ⚠️ **되돌리지 않으면 그 사람은 대기열에서 빠진 채 admitToken도 못 받아 사라진다.**
  이 결정이 ②(중간 DB 확인)를 폐기한 이유가 정확히 그 사고인데, 되돌리지 않으면 같은 사고를
  다른 문에서 다시 여는 셈이다. 되돌린 사람은 admit되지 않은 것이므로 `admitted` ZSet에도
  들어가지 않고 Kafka 발행도 없다 — TTL 만료 복귀(§36)와는 다른 경로다
- **`queue:{q}:admitted` ZSet이 새로 생긴다** — 큐 상태 키가 4종 → 5종. `QueueKeys` 경유 필수(§70 D10),
  §75 D26의 "한 큐의 키는 같은 클러스터" 대상에 포함된다
- **`admit.lua`의 키 안전성은 테스트 하나에 걸려 있다.** `KEYS[]` 선언이 불가능해 CROSSSLOT 그물이
  없으므로(⑥), `QueueKeys`를 우회해 서비스나 Lua에서 키 문자열을 직접 만들면 **그 순간 방어가 0이 된다.**
  Cluster에서 돌려봐도 25%는 통과하므로 **초록은 안전의 증거가 아니다**
- **`queue-batch`가 처음으로 Redis Lua를 돌린다.** 지금은 Application 클래스뿐인 껍데기다.
  actuator·micrometer 추가가 선행이고, 그건 reconciliation의 선행조건과 **같은 작업**이다

### Interview Point

> "대기열에서 사람을 꺼낼 때 DB를 확인하지 않는 이유가 뭔가요?"

확인하면 **정상 대기자를 지웁니다.** 저희는 순번을 Redis에 먼저 쓰고 DB에는 Kafka를 거쳐 나중에
넣습니다. 그 사이에 admit이 들어와서 "DB에 없네, 유령이네" 하고 대기열에서 빼버리면, 방금 줄 선
사람이 흔적 없이 사라집니다. 대기열에서도 빠지고 DB에도 없으니 복구할 근거조차 없습니다.

그래서 **누가 대기 중인지에 대한 권위는 Redis 하나로** 정했습니다. DB는 그걸 검증할 자격이 없고,
검증을 빼고 나니 거를 대상이 없어져서 `ZRANGE` 후 `ZREM` 하던 두 명령이 **`ZPOPMIN` 하나**가 됐고,
중간에 DB 왕복이 없으니 전체가 **Lua 하나에 들어가 원자**가 됐습니다. 원자성을 목표로 잡아서
얻은 게 아니라, 잘못된 검증을 걷어내니 따라온 결과입니다.

대신 브라우저를 닫은 좀비도 뽑히는 걸 감수합니다. 10자리를 열면 9명만 들어올 수 있는데,
이건 DB를 봐도 해결되지 않습니다 — DB에도 그 사람이 좀비인지는 안 적혀 있으니까요.
그래서 막는 대신 **발급 수와 완료 수의 격차로 관측**하기로 했습니다.

### Related

- **§71 D11** (Redis 먼저 · DB 나중) — 이 결정의 근거. 대상 선택의 권위가 Redis라는 것이 D11의 귀결이다
- **§79** — "pop 성공 + 토큰 SET 실패 창"을 이 결정이 닫는다(Lua 하나). watermark 조건부 갱신과
  🔴 표시 전용 가드레일은 그대로 유효하며, admit 대상은 여전히 `waiting` ZSet 최소 seq부터다
  (`ZPOPMIN`이 곧 그것이다)
- **§13 P1-③** — `verified-token` 도입 결정. **이 결정이 폐기한다**
- **§22 · §33** (verify / complete 분리) — 분리는 유지. 다만 verify의 Redis 쓰기가 사라져
  "상태 변경 없음"이 문자 그대로 참이 된다
- **§35** — "verify 없이 온 complete는 서버가 거절"이라는 괄호 예시를 **철회한다**
- **§14** (admit 요청 순서 보장 — Kafka) — 명령 토픽 `enqueue-admit`을 **만들지 않는 것으로 닫는다.**
  동기 처리라 전달할 명령이 없다
- **§36** (TTL 만료 → WAITING 복귀) — 트리거를 `EXISTS admit-by-token` 스캔에서
  **`admitted` ZSet claim-Lua**로 확정한다
- **§73 D16·D18** — `ADMITTED`·`EXPIRED`도 같은 토픽 `token-lifecycle`, key = `tokenId`
- **§75 D26** — 큐 상태 키 목록에 `admitted` 추가, `verified-token` 제거
- **`CLAUDE.md` "`@Scheduled` 단독 금지, leader election 필요" · `CONCURRENCY.md` 동시성 매트릭스**
  — 복귀 claim-Lua는 그 규칙의 **명시적 예외**다(⑧). 매트릭스에도 같은 행을 넣었다
- **§70 D10** (Hash Tag 필수) — `admit.lua`는 `KEYS[]` 선언이 불가능해 **D10의 그물이 안 걸리는
  첫 스크립트**다. 그래서 접두사를 Java(`QueueKeys`)가 만든다(⑥)
- 후속: `count` 상한 **상향** 실측(내리는 것은 파괴적 변경이라 100에서 시작한다),
  버전 컬럼(위 왕복 문제), `tokens` Hash 레거시 값 reconciliation, 포트 rename


---

## §81 — `avgWaitingTime` · ETA · `queue-stats` 폐기 (한 줄도 구현된 적 없다)

**결정일**: 2026-08-20. Sprint 7(§80) 구현이 `dev`에 전부 들어간 뒤 문서와 코드를 대조하다 나왔다.

### Decision

**`avgWaitingTime` · `estimatedWaitSeconds`(ETA) · `queue-stats:{t}:{q}` 키를 셋 다 폐기한다.**
complete 경로의 `HINCRBYFLOAT` / `HINCRBY` 두 줄도 **만들지 않는다.**

| 폐기 대상 | 문서상 있던 곳 | 대체 |
|---|---|---|
| `avgWaitingTime` | FRS 용어표 · §6.6 4단계, FLOW complete, README 트레이드오프, §5·§9·§23·§24·§32 | **없음.** 필요해지면 `tokens`의 `completed_at − issued_at` 사후 집계 |
| `estimatedWaitSeconds` (ETA) | FLOW 미채택 polling 다이어그램, §79 후속 | **없음.** §79가 폴링 응답에서 개인화를 이미 전부 걷어냈다 |
| `queue-stats:{t}:{q}` | FRS §8 · README 키표, §23 키표 | **키 삭제.** 용도가 avgWaitingTime 하나뿐이었다 |

### Rationale

- **ETA를 실을 자리가 이미 없다.** §79가 `/status`를 `{lastAdmittedSeq, pacing}`으로, 개인 응답을
  `{ready, admitToken?}`으로 고정했다. ETA를 살리려면 §79가 지운 **개인화를 되살려야** 한다 —
  그건 이 결정이 아니라 §79를 뒤집는 일이다
- **입력이 사라지면 키가 남을 이유가 없다.** `queue-stats`의 용도는 avgWaitingTime 하나였다
- **한 줄도 구현된 적이 없다.** `grep -rn "avgWaitingTime\|queue-stats" --include=*.java --include=*.lua`
  = **0건**. 지우는 결정이 아니라 **만들지 않기로 하는 결정**이다
- **값 자체가 우리 것이 아니다.** `completed_at − issued_at` 평균에는 **Tenant가 admitToken을
  얼마나 빨리 소비했는가**(플랫폼 소관 아님)와 **TTL 만료 복귀 왕복**이 섞인다. 큐 규모가 바뀌면
  과거 평균이 미래를 못 맞춘다. 이상치 필터(`waitingTtl × 0.8`)는 그 편향을 못 없앤다
- **§79와 같은 판단이다.** §79는 "정확한 순번 불필요, admit 누락 없이 통과가 핵심"이라며 rank
  과대 추정을 수용했다. **틀린 ETA는 그보다 나쁘다** — 사용자가 시계를 보고 이탈한다

### Alternatives

**A. `avgWaitingTime`만 남기고 ETA는 안 쓴다 (기각)** — 읽는 곳이 없는 통계다. complete마다 Redis
왕복 2회를 더 치르고 얻는 것이 0이다.

**B. `COMPLETED` Kafka 컨슈머가 집계 (기각)** — 대체가 아니라 **확대**다. 지금 이 값을 필요로 하는
사람이 없는데 컨슈머·토픽 소비 분기·시계열을 새로 만든다.

**C. 나중에 필요해지면 (수용)** — `tokens`에 `issued_at` · `completed_at`이 이미 있다. **사후 집계가
가능하므로 지금 실시간 누적을 만들 이유가 없다.** 되살리는 비용이 낮은 쪽을 택한다.

### Consequences

- **실시간 대기시간 지표가 0이 된다.** "이 큐는 평균 얼마나 기다리나"를 지금 당장은 답할 수단이 없다.
  월 단위는 `queue_daily_stats`가 갖고, 임의 구간은 `tokens`에서 뽑는다
  (⚠️ `tokens` 인덱스에 `completed_at`이 없어 그 집계는 파티션 스캔이다 — **상시 조회로 승격하려면
  인덱스를 다시 봐야 한다.** §79의 watermark 복구 쿼리와 같은 성격)
- **Redis 복구 표(§5)에서 "복구 불가" 한 줄이 줄어든다** — 없는 값은 잃을 수도 없다
- **되살릴 때는 거처부터 다시 정해야 한다.** "complete API가 직접 Redis를 갱신"이라는 안은 이 절과
  함께 폐기된다 — 저빈도 경로에 Redis 왕복 2회를 더하는 설계였고, 그때 다시 고를 이유가 없다
- 폐기 대상이 문서 6개 파일에 흩어져 있었다. **`DECISIONS`는 지우지 않고 폐기 표시**했고
  (§5·§9·§23·§24·§32), 명세·흐름도·README에서는 **삭제**했다 — 명세에 남으면 만들어야 할 것으로 읽힌다

### Interview Point

> "예상 대기 시간을 왜 안 보여주나요?"

**보여줄 근거가 없어서**입니다. ETA를 만들려면 평균 대기시간이 필요한데, 그 평균에는 **저희가
통제하지 못하는 시간**이 섞입니다 — 입장권을 받은 뒤 테넌트가 얼마나 빨리 입장시키는지는 테넌트
사정이고, 60초 안에 못 하면 그 사람은 대기열로 돌아와서 다시 기다립니다. 큐 규모가 바뀌면 과거
평균이 미래를 못 맞추기도 하고요.

그래서 **"현재 47번 입장 중"** 하나만 내보냅니다. 은행 전광판과 같습니다. 틀린 시계를 보여주면
사용자는 그 시계를 믿고 창을 닫습니다. 정직한 번호가 낫다고 봤습니다.

포기한 게 아니라 **미룬 것**이기도 합니다 — `tokens` 테이블에 발급 시각과 완료 시각이 남아 있어서,
필요해지면 사후 집계로 만들 수 있습니다. 지금 실시간 누적을 만들면 complete마다 Redis 왕복이
두 번 늘어나는데, 읽는 사람이 없습니다.

### Related

- **§79** — ETA(`estimatedWaitSeconds`) 후속 항목을 **이 절이 닫는다**(폐기). 해당 줄에 상호 참조를 남겼다
- **§9** (Admit = Dequeue + 통계 갱신) — 세 번째 단계 "avgWaitingTime 갱신(마지막)"을 **폐기한다.**
  남는 순서는 DB 먼저 → Redis 정리 나중
- **§23** (Redis Key 설계 이유) — `queue-stats:{t}:{q}` 행 폐기
- **§24 ⑧** (avgWaitingTime ETA 왜곡) — **문제 자체가 소멸**한다
- **§32** (Kafka 도입 설계) — "avgWaitingTime은 Kafka 없이 complete API에서 직접 갱신" 및 그 면접 답변 폐기
- **§5** (Redis 장애 복구) — 복구 불가 항목에서 제외
- **§80** — complete 구현(`QueueEngineService.complete`)에는 이 갱신이 **처음부터 없다.**
  이 절은 코드를 바꾸는 결정이 아니라 **문서를 코드에 맞추는 결정**이다

---

## §82 — Cancel API를 만들지 않는다 (이탈은 TTL 하나로, 과금은 상태를 보지 않는다)

### Context

두 개의 열린 질문이 하나로 합쳐졌다.

**첫째, 과금 술어.** 집계는 `tokens` 원본을 직접 센다(§24 ⑥, §44 Step 2). 그 술어가
`WHERE issued_at BETWEEN M월 AND M+1월` **하나뿐이라 상태를 보지 않는다.** 취소한 사람도
만료된 사람도 아직 줄에 서 있는 사람도 전부 한 건으로 청구된다. 이게 맞는가?

**둘째, Cancel API.** `DELETE /queues/:queueId/tokens/:tokenId`는 Sprint 6의 마지막 잔여
항목으로 설계까지 끝나 있었다(`cancel.lua` + 동기 UPSERT). 구현은 착수 전이었다.

한때 첫째의 답을 **"취소분은 뺀다(`status <> 3`)"** 로 정했으나, 그 결정은 **머지 전에
뒤집혔다.** 뒤집은 것은 과금 계산이 아니라 **둘째의 전제**다 — 취소를 별도 개념으로 둘
이유가 없다면 뺄 대상 자체가 없다.

### Decision

**① `DELETE /queues/:queueId/tokens/:tokenId`(Cancel API)를 만들지 않는다.**

**② 이탈 회수는 `inactiveTtl` 판정 배치 하나가 전담하고, 도달 상태는 `EXPIRED(4)` 다.**

**③ 과금 술어는 `issued_at` 범위 하나다. 상태를 보지 않는다.**

```sql
SELECT tenant_id, COUNT(*)
FROM tokens
WHERE issued_at >= M월 AND issued_at < M+1월
GROUP BY tenant_id
```

### 왜 Cancel이 필요 없는가

"안 만들면 무엇이 깨지는가"(협업 규칙 §4)에 답이 남지 않았다.

| 이탈 경로 | Cancel이 잡나 | `inactiveTtl`이 잡나 |
|---|---|---|
| 유저가 취소 버튼을 누른다 | ✅ | ✅ (폴링이 멈춘다) |
| 유저가 탭을 닫는다 | ❌ **Tenant가 그 사실을 모른다** | ✅ |
| 네트워크가 끊긴다 · 기기가 꺼진다 | ❌ | ✅ |

Cancel은 이탈의 **일부만** 덮는데, 덮는 그 일부조차 `inactiveTtl`이 이미 덮는다. 그리고
**이탈 감지 배치는 Cancel이 있든 없든 어차피 필요하다** — 지금 그게 없어서 이탈자가
`waiting` ZSet에 무기한 남는다(회수 배치 미구현).

남는 차이는 **자리 반납이 즉시냐 최대 `inactiveTtl`이냐** 하나인데, `inactiveTtl`은 이미
`QueueCreateRequest`로 Tenant가 큐마다 정한다. **조절 노브가 이미 있는데 엔드포인트를 더
만드는 것**이 과설계다.

⚠️ **브라우저가 직접 못 불러서 기각한 것이 아니다.** `GET /api/v1/queues/*/tokens/*`가
`permitAll`(`SecurityConfig:46`)이므로 `DELETE`도 한 줄이면 열린다 — 폴링과 같은 경로,
같은 인증 모델(tokenId 소지)이다. 기각 근거는 **덮는 범위가 없다**는 것이다.
다만 열었다면 `permitAll` **쓰기**가 되어, tokenId 유출의 대가가 "순번 노출"에서
**"타인 축출"** 로 올라갔을 것이다. `?seq=` 대조는 방어가 못 된다 — seq는 폴링 응답에
함께 실려 같이 샌다. 이건 부차적 근거이지 주 근거가 아니다.

### 왜 과금에서 아무것도 빼지 않는가

**취소가 사라지면 남는 이탈은 전부 EXPIRED(4)이고, EXPIRED는 자리를 끝까지 점유했다.**
`inactiveTtl`을 다 쓰고 밀려난 것이라 Platform이 쓴 자원은 정확히 그만큼이다.

| 상태 | 과금 | 근거 |
|---|---|---|
| WAITING(0) · ADMIT_ISSUED(1) | ✅ | 집계 시점에 아직 살아 있는 줄이다. 빼면 월말에 걸친 큐가 통째로 무료가 된다 (§44가 1달 유예를 두는 이유와 충돌) |
| COMPLETED(2) | ✅ | 설명 불필요 |
| **EXPIRED(4)** | ✅ | **자리를 점유했다.** 서비스를 썼다 |
| ~~CANCELLED(3)~~ | — | **도달하는 경로가 없다** |

그리고 취소분 제외의 유일한 근거였던 *"취소를 과금하면 Tenant에게 취소 API를 안 붙일
동기가 생긴다"* 는 **Cancel API가 존재할 때만 성립하는 논증**이다. API가 없으면 Tenant에게
선택지 자체가 없으므로 왜곡될 동기가 없다. **전제가 사라지면 결론도 사라진다.**

`inactiveTtl` 창을 넘겨 돌아온 사람이 새 `tokenId`로 다시 서면 **한 건이 더 청구된다.**
이것도 의도한 결과다 — 줄을 두 번 섰고 자원도 두 번 썼다(§21의 유예 창 설명 참조).

### Consequences

**① ~~`TokenStatus.CANCELED(3)`은 예약값으로 남긴다~~ — 🔴 2026-08-21 정정. 지웠다.**
당시 근거였던 *"enum 상수와 TINYINT 값을 재번호하는 비용"* 은 **틀렸다.** `TokenStatus`는
명시적 `statusCode`를 갖는 enum이라(`CANCELED(3)`) 상수를 지워도 `EXPIRED(4)`는 그대로 4다 —
**재번호가 일어나지 않는다.** `status = 3`인 행은 Cancel 미구현으로 한 번도 존재한 적이 없어
`fromCode(3)`이 불릴 일도 없다.

→ `TokenStatus.CANCELED`·`TokenEventType.CANCELLED`·`TokenJpaAdapter`의 SQL 맵 엔트리를 **전부
삭제**했다. **결번 3은 `schema.sql`의 `status` 컬럼 주석으로 남긴다** — 재사용 금지 정보가
필요한 곳은 enum이 아니라 DB 쪽이다. 개념이 사라진 상수를 남기면 읽는 사람이 "취소가 있나?"를
다시 묻는다.

**② `tokens.cancelled_at`과 `queue_daily_stats.total_cancelled`는 삭제한다.** 항상 NULL과
0이 될 컬럼이고, `schema.sql`은 아직 미배포 DDL이라 지금이 유일하게 공짜인 시점이다.

**③ 취소 경로의 `issued_at` 원본 전파 함정이 소멸한다.** `UNIQUE (token_id, issued_at)`이라
1ms만 어긋나도 두 번째 행이 생기는 문제(§83 Context)는 **취소 경로에서만** 사라진다.
`ADMITTED` · `COMPLETED` · `EXPIRED`는 여전히 원본을 그대로 실어야 한다(~~`RETURNED`~~는 §36이 폐기).

**④ §21이 대체된다.** 초판 이탈 정책은 폐기 표기와 함께 기록으로만 남는다.

**⑤ 아직 코드가 없다.** `BillingSnapshotJob`은 미구현(Sprint 9)이고, 이 결정으로 **추가되는
술어도 없다** — 원래 상태를 안 보는 쿼리를 그대로 두는 것이다. Cancel 쪽은 애초에 착수 전이라
**삭제할 프로덕션 코드가 0줄**이다. 바뀌는 것은 문서와 `schema.sql` 뿐이다.

**⑥ 새로 만들 것은 `inactiveTtl` 판정 배치 하나뿐이고, 그것도 신설이 아니다.** Sprint 9의
회수 배치(`tokens` Hash `HDEL` 0건 · `last-active` ZSet `ZREM` 0건 누수 정리)가 그 자리다.
거기에 "누구를 회수할지"의 판정 기준이 `last-active` score라는 것이 더해질 뿐이다.
claim 패턴(`ZRANGEBYSCORE` + `ZREM`을 한 EVAL에)은 `admit_expire.lua`와 같지만 **스크립트를 복사할
수는 없다** — `admitted`의 member는 `"seq|identifier"`라 조회 한 번으로 둘 다 나오지만, `last-active`의
member는 **`seq`뿐**이라(`QueueKeys.lastActive` 주석 · `poll_verify.lua`) `ZRANGEBYSCORE waiting seq seq`로
identifier를 역산하는 단계가 하나 더 필요하고, `HDEL` 전에 `HGET tokens`로 `issuedAt` 원본을 확보해야
한다(위 ③). 필요한 키는 `last-active`·`waiting`·`tokens` 3종이며 **전부 `QueueKeys`에 이미 있고 같은
해시태그**라 새 키도 CROSSSLOT 위험도 없다.

### 🔴 미해결 — `last-active` 커버리지 구멍 3개 (2026-08-21 architect·planner 교차 검토)

위 "왜 Cancel이 필요 없는가" 표는 *"탭 닫음 → `inactiveTtl`이 잡나 ✅"* 라고 단언했다.
**그 단언은 세 경우에 거짓이다.** 원인은 하나다 — `last-active` ZSet을 채우는 코드가
`poll_verify.lua`의 `ZADD` **한 곳뿐**이고, 그것도 `ka=1`일 때만 실행된다
(`ka`는 30~60초에 1회, `@RequestParam(defaultValue = "false")`). 평상시 폴링은 `/status`가
받으므로(§79) `/tokens/{tokenId}`는 자주 오지도 않는다.

**§82 이전에는 무해했다** — `last-active`에 소비자가 없었기 때문이다. 이 절이 그 값을
**사람을 회수하는 판정 기준으로 승격**시키면서 아래가 위험으로 바뀌었다.

**① 생존 신호가 클라이언트 자율에 달려 있다.**
"이 사람이 살아 있다"의 유일한 근거가 **인증 없는 엔드포인트의 쿼리 파라미터 하나**다.
`ka`를 안 붙이는 클라이언트(구 SDK · 자작 클라이언트 · 버그)는 2초마다 폴링해도 회수된다.

**② ~~`inactiveTtl` 하한~~ — 🔴 소멸했다 (§36, 2026-08-21).**
파국 체인의 전제가 *"복귀 후 `tokens` 없는 고아"* 인데 **복귀 자체가 폐기**됐다. 만료자는
`waiting`으로 돌아오지 않으므로 고아가 될 대상이 없다. 아래는 당시 서술의 기록이다.

~~**`inactiveTtl`에 하한이 있다 — 그런데 검증이 없다.**~~
admit되면 `admit.lua`의 `ZPOPMIN`으로 `waiting`에서 빠지고, 그러면 `poll_verify.lua`가
`ZRANGEBYSCORE waiting seq seq`에서 identifier를 못 찾아 **`ZADD last-active`에 도달하지 못한다.**
즉 admit 대기 중인 60초 동안 그 사람의 `last-active`가 언다. `inactiveTtl`이 짧으면:

```
sweep이 admit 대기자를 만난다 → ZRANGEBYSCORE waiting seq seq 미스(그 사람은 waiting에 없다)
→ identifier를 모르므로 HDEL도 ZREM waiting도 할 수단이 없다
→ 남는 선택은 "ZREM last-active만 하고 넘어간다"뿐
→ 복귀 후 다음 ka=1 폴링이 오면 ZADD로 스스로 복구된다 (자가 치유)
→ ka가 영영 안 오면 회수 신호를 잃은 채 waiting에 잔류 (= ③과 같은 누수)
```
🔴 **sweep이 identifier를 다른 데서 얻으면(DB `user_id`, `admitted` ZSet) 파국이 부활한다** —
`HDEL tokens`가 중복 게이트를 풀어 복귀자가 영구 404 + 회수 불가 고아가 된다.
그래서 **sweep의 "역산 미스" 처리 규약을 착수 전에 못박아야 한다**: 미스면 `ZREM last-active`만.
필요 여유 ≈ `admitTokenTtl`(60) + sweep 주기(10) + 복귀 후 첫 `ka`(30~60) = **130~150초.**
기본 300초는 안전하지만 `QueueCreateRequest`에 `inactiveTtl` 검증이 **하나도 없다.**
DB는 안전하다 — `IF(tokens.status = 0, 4, ...)` 가드가 status=1을 no-op으로 만든다.
**위험은 오직 `HDEL tokens`가 중복 게이트를 푼다는 데서 온다.**

🔴 **정적 하한으로는 ②를 원리적으로 완전히 닫을 수 없다.** 130~150초는 "admit 1회"를 가정한
값인데, 실제로는 `admit(60s 언다) → 복귀 → 다음 ka까지 최대 60초 → 또 admit`이 반복된다.
**사이클 3회면 기본값 300초를 넘는다.** 그리고 사이클이 도는 조건이 *"Tenant가 60초 안에
입장 처리를 못 했다"* 라, **Tenant 서버가 느릴 때 정상 대기자가 무더기로 회수되는 장애 증폭**이다.
F가 이걸 크게 줄인다 — 복귀 후 첫 poll에 갱신되므로 사이클마다 리셋된다.

**③ 첫 `ka=1` 이전에 떠난 사람은 `last-active`에 멤버가 아예 없다.** (§36 폐기 후 **파괴력만 소멸**)

🔴 **§36이 복귀를 폐기(2026-08-21)해 좀비의 admit 슬롯 독점은 사라졌다** — 좀비는 한 번 뽑히고
`HDEL`로 정리된다. **admit 자체가 좀비 청소기가 된다.** 남는 것은 **admit이 아직 닿지 않은 뒤쪽
좀비**의 `waiting`·`tokens` 잔류뿐이고, 그건 head를 막지 않으므로 해가 작다.
`enqueue_bulk.lua`는 `last-active`를 건드리지 않는다(KEYS 3종). enqueue 직후 30~60초 안에
탭을 닫으면 `ZRANGEBYSCORE last-active`가 **영원히 못 찾는다** → `waiting`·`tokens`에 영구 잔류.
🔴 **그리고 이건 조용한 누수가 아니다.** `admit.lua`는 `ZPOPMIN`이라 좀비를 그냥 뽑아가고,
`admit_expire.lua:57`이 **원래 seq 그대로** 되돌린다(§36) → 좀비가 큐 맨 앞으로 복귀 →
다음 admit에서 또 뽑힌다. 좀비 Z명, Tenant의 60초당 처리율 R명일 때 매 사이클 `min(Z,R)`의
**admit 슬롯이 좀비에게 간다. Z ≥ R이면 정상 입장자가 0에 수렴한다.**
`waitingTtl`(7200초)은 구제책이 못 된다 — 그 사이 좀비는 admit 사이클을 약 120회 돌고,
**2시간 안에 끝나는 이벤트(티켓팅)에서는 한 번도 안 돈다.**
⚠️ 다만 `waitingTtl`의 판정 소스는 **미정이 아니다** — `tokens` Hash 값이 `"tokenId\|issuedAt"`라
같은 키 공간에 시간축이 이미 있다(`HSCAN` 커서). DB `tokens.issued_at`을 쓸 이유가 없다.
**초반 이탈률이 높은 큐라면 회수 배치를 다 만들고도 누수가 남는다.**

#### 선택지 (확정하지 않는다 — 사용자 판단)

| 안 | 내용 | 닫는 구멍 | 대가 |
|---|---|---|---|
| **A** | `enqueue_bulk.lua`에 `KEYS[4] = last-active` + `ZADD` 한 줄 | ③ | 청크 500건당 `ZADD` +500. `last-active` 멤버가 "ka 보낸 사람" → "enqueue한 전원"으로 커진다(멤버가 짧은 seq 문자열이라 `waiting`보다 작다). ⚠️ **롤링 배포 불일치는 없다** — `DefaultRedisScript`가 본문에서 SHA를 계산하고 `NOSCRIPT`면 `EVAL` 폴백이라 구/신 WAS가 각자 실행한다. **①은 완화하지 않는다**(①은 반대 방향이다) |
| **B** | sweep이 `waiting`을 소스로 돌고 `ZSCORE last-active` 미스를 "한 번도 폴링 안 함"으로 판정 | ③ | 큐 전체 스캔이 되어 `LIMIT` 기반 claim 설계가 바뀐다 |
| ~~**C**~~ | ~~`inactiveTtl` 하한 검증~~ | ~~②~~ | 🔴 **불필요해졌다 (§36).** ②가 소멸했다 |
| ~~**D**~~ | ~~sweep이 "`waiting`에 없는 seq"는 안 죽인다~~ | ~~②~~ | 🔴 **불필요 (§36).** 그리고 | ⚠️ **겉보기보다 비싸다.** `ZREM`까지 안 하면 stale 멤버가 매 주기 `LIMIT` 앞자리를 먹어 진짜 회수를 굶기고, `ZREM`만 하면 admit 복귀자의 유일한 회수 신호가 사라져 §80이 막으려던 좀비가 부활한다 |
| **E** | 문서에만 하한·한계를 명시하고 넘어간다 | — | 깨지는 건 Tenant가 문서를 안 읽었을 때뿐 |
| **F** | `poll_verify.lua`의 `if keepalive == '1'` **분기를 삭제**하고 항상 `ZADD` | ① (②의 연속-admit 잔여도 줄인다) | **코드가 줄어든다.** `ka`는 무시되므로 API 하위호환. §80과 충돌 없음 — §80이 금지한 건 *배치*가 리셋하는 것이고, F는 **사람이 폴링해야** 갱신된다. `/tokens/{tokenId}`는 rank 근접 + keepalive에만 오므로 증가분은 `ka=0` 몫뿐 |

⚠️ **되돌리기 어려운 지점**: `last-active`의 member 포맷(`seq`)이 sweep을 짜는 순간 굳는다.
`"seq|identifier"`로 바꾸려면 폴링 핫패스(`poll_verify.lua`)를 고쳐야 하고 롤링 배포 중 두 포맷이
섞인다. **B·D를 검토한다면 sweep 작성 전이 유일하게 싼 시점이다.**

📌 이 세 구멍은 **Cancel API가 있었어도 닫히지 않는다** — Cancel은 취소 버튼만 덮고, ①②③은
전부 "폴링이 안 온다"를 정확히 판정하는 문제다. 즉 §82의 결론(Cancel 폐기)을 뒤집을 근거가
아니라, **§82가 유일하게 남긴 경로를 실제로 작동시키기 위한 잔여 과제**다.

### 🔴 2026-08-21 갱신 — §36 복귀 폐기 이후 남는 것

| 구멍 | 상태 |
|---|---|
| ① `ka` 신호가 클라이언트 자율 | ✅ **닫혔다 — F안 채택**(`poll_verify.lua`의 `keepalive` 분기 삭제). 폴링이 오면 언제나 `last-active`를 갱신한다. `ka` 파라미터는 API 하위호환으로 자리만 남고 무시된다 |
| ② `inactiveTtl` 하한 | ✅ **소멸.** 복귀가 없어 고아가 생기지 않는다 |
| ③ 첫 폴링 이전 이탈 | 🔴 **열려 있다 (A 미채택).** 2026-08-21 실측으로 확인 — enqueue만 하고 **한 번도 폴링하지 않은 사람은 `last-active`에 멤버가 아예 없어** sweep이 훑는 ZSet에 나타나지 않는다. 27초를 기다려도 회수되지 않았고, 폴링을 1회라도 한 사람은 정상 회수됐다. admit이 지나가면 정리되므로 파괴력(슬롯 재순환)은 없지만 `waiting`·`tokens` 잔류는 남는다 |

### ✅ 구현 완료 (2026-08-21) — `inactive_expire.lua` + `TokenReclaimJob`

**F안 채택**으로 ①이 닫혔고, **회수 배치가 구현**되어 §82가 선언한 "이탈 회수의 유일한 경로"가
실제로 존재한다. `admit_expire.lua`와 같은 claim 패턴(`ZRANGEBYSCORE` + `ZREM`을 한 `EVAL`에)이라
ShedLock을 쓰지 않는다.

```
cutoff = now - queue.inactiveTtl * 1000      ← 큐 설정이라 Java가 계산한다
ZRANGEBYSCORE last-active -inf (cutoff LIMIT 0 500   ← 이 EVAL이 claim
  → ZREM last-active (먼저. 여기까지 오면 다른 인스턴스는 못 본다)
  → seq마다: ZRANGEBYSCORE waiting seq seq 로 identifier 역산
              HGET tokens (issuedAt 원본) → ZREM waiting → HDEL tokens
  → EXPIRED 발행
```

🔴 **`waiting`에 없는 seq는 `last-active`에서만 뺀다**(§36 역산 미스 규약). 그 사람은 admit되어
큐 밖이고, `tokens`를 지우면 중복 게이트가 풀려 재-enqueue가 새 자리를 받는다. 그렇다고 `last-active`에
남겨두면 stale 멤버가 매 주기 한도의 앞자리를 먹어 진짜 대상을 굶긴다.

**남는 것은 A 하나다.**
- **A**(`enqueue_bulk.lua`에 `last-active` `ZADD` 한 줄) — ③(첫 폴링 이전 이탈)의 잔류를 닫는다.
  핫패스 쓰기 +1이 대가라 **미채택**. `waitingTtl` 배치가 받아줄 수 있으나 그 판정도 아직 없다
  (`waiting`의 score는 `seq`라 시간축이 없다 — 소스 후보는 `tokens` Hash의 `issuedAt`).
  📌 **실측으로 재현된다** — 위 ③ 참조. "enqueue 직후 이탈"이 흔한 큐라면 A를 다시 검토해야 한다

**C·D는 §36 폐기로 불필요해졌다.**

### Related

- **§21** (이탈 정책) — **이 절이 대체한다.** 초판은 폐기 표기로 남긴다
- **§36 · §80** (`admitToken` TTL 만료 → WAITING 복귀) — `TokenReclaimJob`이 복귀 시
  `last-active`를 **일부러 갱신하지 않는** 이유가 바로 이 절이다. 갱신하면 브라우저를 닫은
  사람이 복귀할 때마다 되살아나 영원히 회수되지 않는다
- **§44** (파티션 유예) — Step 2 집계 쿼리에 "상태 술어 없음" 주석을 남겼다. 쿼리 자체는 불변
- **§24 ⑥** (과금 누락) — 같은 쿼리가 복제돼 있어 같은 주석을 남겼다
- **§66 D1** (자유 identifier) — 유예 창 안의 순번 복원이 성립하는 근거. Platform은 identifier가
  같은지만 본다. 비로그인 유저의 identifier 유지는 **Tenant 책임**이다(§78 경계)
- **§78** (클라이언트 경계) — enqueue가 Tenant인 것과 같은 이유로, 이탈 판정도 Platform이
  관측한 사실(`last-active`)에 근거한다
- **§83** (파티션 키) — `issued_at` 원본 전파 요구는 위 ③대로 **다른 이벤트에는 그대로** 유효하다
- ⚠️ **§32의 `BillingConsumer` 예시 코드는 `COMPLETED`만 세는 다른 기준**이다. 초판 스케치이고
  한 줄도 구현된 적이 없다. **집계 기준의 정본은 이 절**이다

### Interview Point

> "대기열에 취소 API를 안 만들었습니다. 이유는 취소가 이탈의 일부만 덮기 때문인데, 유저가
> 취소 버튼을 누르면 API가 잡지만 탭을 닫거나 지하철에 들어가면 Tenant 서버는 그 사실을
> 알 방법이 없습니다. 그래서 이탈 감지는 어차피 폴링 중단으로 판정하는 배치가 필요했고,
> 그 배치가 있으면 취소 버튼도 '폴링을 멈춘다'로 같은 경로에 실립니다. 남는 차이는 자리를
> 즉시 반납하느냐 최대 inactive TTL 뒤에 반납하느냐 하나인데, 그 TTL은 이미 큐 생성 API에
> 노출돼 있어서 회전이 빠른 큐는 60초로 낮추면 됩니다. 조절 노브가 있는데 엔드포인트를
> 더 만들 이유가 없다고 판단했습니다.
>
> 이 판단이 과금 정책도 같이 정리했습니다. 원래는 취소분을 과금에서 빼려고 했는데, 그
> 근거가 '취소를 과금하면 Tenant가 취소 API를 안 붙일 동기가 생긴다'였습니다. API가 없어지면
> 그 논증의 전제가 사라집니다. 그래서 과금은 상태를 보지 않고 enqueue 수 그대로 셉니다.
> 만료를 과금하는 건 자리를 끝까지 점유했기 때문이고, 실제로 유예 창을 넘겨 돌아온 사람은
> 줄을 두 번 선 것이라 두 건이 청구되는 게 맞습니다.
>
> 여기서 재미있는 게 inactive TTL의 의미가 바뀐다는 겁니다. 단순한 청소 주기가 아니라
> '몇 초까지 자리를 지켜줄 것인가'라는 유예 창이 됩니다. 그 안에 돌아와서 같은 identifier로
> 다시 enqueue하면 Redis Lua의 HSETNX 게이트가 EXISTS를 돌려주면서 원래 순번을 그대로
> 복원해주고, 창을 넘기면 게이트가 이미 HDEL로 풀려 있어서 맨 뒤에 서게 됩니다.
>
> 다만 창의 하한이 있습니다. admit을 받은 사람은 waiting에서 빠져 있어서 폴링이 last-active를
> 갱신하지 못하는데, admitToken TTL 60초와 배치 주기와 복귀 후 첫 keepalive까지 합치면
> 130~150초는 있어야 admit 대기 중인 사람이 잘못 회수되지 않습니다."

---

## §83 — 파티션 키를 바꾸지 않는다 (범위 조건 프루닝 실패의 실측)

### Context

`tokens`는 `PARTITION BY RANGE (YEAR(issued_at) * 100 + MONTH(issued_at))`이다. 그리고 MySQL은
**유니크 키가 파티션 표현식의 컬럼을 전부 포함**하도록 강제하므로 `UNIQUE (token_id, issued_at)`이
됐다. `issued_at`이 `DATETIME(3)` **밀리초**라, 모든 상태 전이 이벤트가 그 값을 **원본 그대로**
실어 날라야 한다 — 1ms만 어긋나면 유니크 충돌이 안 나 **같은 토큰의 두 번째 행**이 생기고,
그 행은 같은 토큰의 **두 번째 청구 건**이 된다(§82 — 과금은 상태를 보지 않는다).

여기서 두 질문이 나왔다. ① 이 제약을 없앨 수 있나? ② 애초에 프루닝은 되고 있나?

`schema.sql`은 ②를 2026-08-17에 이미 정정해 뒀는데(파티션 운영 쿼리 절), **§26·§44·ROADMAP·README는
반대를 말하고 있었다.** 이 절은 그 갈라짐을 닫고 ①의 판정을 남긴다.

### 실측 (MySQL 8.0.46, `tokens` 160,808행, buffer pool warm)

| # | 확인 | 결과 |
|---|---|---|
| 1 | `WHERE issued_at >= '2026-04-01' AND < '2026-05-01'` | 🔴 `partitions:` **13개 전부** |
| 2 | `WHERE YEAR(issued_at)*100+MONTH(issued_at) = 202604` (파티션식과 동일하게) | 🔴 **여전히 13개** |
| 3 | 범위 조건과 파티션식을 **병기** | 🔴 **여전히 13개** |
| 4 | `FROM tokens PARTITION (p2026_08)` | ✅ **1개** |
| 5 | 점 조회 3종(`findOneByTokenId`·`findAdmittedByAdmitToken`·`markCompleted`) | 🔴 **13개 전부.** 호출자가 월을 모르므로 **어떤 파티션 설계로도 못 고친다** |

**원인:** 옵티마이저가 `YEAR(c)*100 + MONTH(c)`를 `c`에 대해 단조(monotonic)라고 증명하지 못한다.
프루닝이 지원되는 것은 컬럼에 직접 적용된 제한된 함수 집합뿐이다.

**그런데 손해가 작다:**

| | 실행 시간 |
|---|---|
| §44 Step 2 집계, 현행(13개 스캔) | **54.4 ms** |
| 〃 `PARTITION` 절 적용(1개) | **52.8 ms** |

**차이 3%.** 13개 중 **12개가 0행**(데이터 16KB)이라 스캔해도 페이지 몇 개다. 운영 상태에서도
§44의 M+2 유예가 데이터 있는 파티션을 `M`·`M+1`·`M+2` **3개로 제한**하므로 최악이 **상수배 3**이고,
그것도 **월 1회**다. 점 조회는 파티션 13개일 때 0.036ms vs 비파티션 0.027ms로 **차이가 무의미**하다.

### Decision

**파티션 키를 바꾸지 않는다.** 프루닝이 필요한 월말 집계는 `FROM tokens PARTITION (pYYYY_MM)`으로
파티션을 직접 지목한다 — **배치는 대상 월을 알고 있다.** `BillingSnapshotJob`은 미구현이므로
작성 시점에 넣으면 되고 비용이 0이다.

### 검토한 대안 4개

| 안 | 앱 변경 | 프루닝 | 월경계 안전 | 판정 |
|---|---|---|---|---|
| **현행 유지** | 0 | `PARTITION` 절로 | ✅ | ✅ **채택** |
| C: `RANGE COLUMNS(issued_at)` | **0** | ✅ 범위 조건 그대로 | ✅ | 🟡 유효하나 불필요 |
| A: `issued_month` 실제 컬럼 | 본코드 14 + 테스트 11 | ✅ | ✅ | ⛔ 기각 |
| B: `issued_month` 생성 컬럼 | 0 | ✅ | 🔴 실패 | ⛔ 기각 |

**C안은 실제로 동작한다**(실측: PK·UNIQUE를 그대로 둔 채 범위 조건이 `partitions: p2026_04`로
프루닝된다). `RANGE COLUMNS`는 표현식이 아니라 **컬럼 값을 직접 비교**하므로 단조성 증명이 필요 없다.
재구축 1회(13.5초, `LOCK=SHARED`)면 되고 앱은 한 줄도 안 바뀐다. **채택하지 않은 이유는 이득이
월 1회 배치의 3배 스캔뿐이고 그마저 `PARTITION` 절로 공짜이기 때문**이다. 필요해지면 그때 해도 된다
— 다만 **비용이 행 수에 비례**하므로 미룰수록 비싸진다.

**A·B안이 기각된 이유는 프루닝이 아니라 ①이 성립하지 않아서다:**

- **`issued_month`는 `issued_at`을 대체하지 못한다.** `issued_at`은 `NOT NULL`이고 전이 UPSERT는
  `INSERT ... ON DUPLICATE KEY`라 **행이 없으면 실제로 INSERT된다.** 그래서 Redis Hash 값이
  `tokenId|issuedAt|issuedMonth` **3원소로 늘어난다** — 줄이려던 것이 늘어난다
- **없애려던 분기가 안 없어진다.** `admit.lua`·`admit_expire.lua`·`TokenReclaimJob`의
  "발행 생략" 게이트는 전부 **`HGET tokens` 미스**(= tokenId를 모름)지 `issuedAt` 유실이 아니다.
  Hash가 살아 있으면 `issuedAt`은 `tokenId`와 **같은 문자열에 붙어 공짜로** 따라오고, 죽으면
  tokenId가 없어 어차피 발행을 못 한다. **`issuedAt`만 유실되는 경로가 존재하지 않는다**
- A안은 Hash 포맷을 또 바꾸므로 롤링 배포 중 **구포맷 이중 파싱 분기가 4곳 새로 생긴다**
- **B안은 "전이 시점에 월을 계산해도 된다"는 유혹을 스키마에 심는다.** 그러면 §44가 보호하려는
  바로 그 인구(월말에 걸친 토큰)에서 **2행이 생겨 이중 과금**된다(실측). 지금은 안전한데
  **설계 의도대로 쓰는 순간 깨지는** 스키마다
- 키 크기 절감은 **2.8%**이고, 인덱스 2개(`idx_tokens_queue_status_issued`·`idx_tokens_status_admit`)는
  `issued_at`이 인덱스 컬럼으로 남아 **오히려 커진다**

### Consequences

**① 파티션 표현식 변경은 되돌리기 어렵다.** 실측한 제약:
```
ALGORITHM=INPLACE            → ERROR 1845 (COPY를 쓰라)
COPY + LOCK=NONE             → ERROR 1846 (SHARED가 필요)
PK만 먼저 바꾸고 파티션은 나중 → ERROR 1503 (PK는 파티션 함수 컬럼을 전부 포함해야 한다)
```
즉 **PK 변경과 파티션 재정의는 한 문장**이어야 하고 **쓰기가 차단**된다(읽기는 가능).
160,808행에서 A안 3단계 10초 / C안 13.5초. 행당 40~80µs 규모라 1,000만 행이면 **7~14분 쓰기 차단**이다.
그동안 enqueue API는 Redis+Kafka라 **무영향**이고 `queue-consumer`의 적재만 lag으로 쌓인다.

**② 점 조회 3종은 어떤 안으로도 프루닝되지 않는다.** 호출자(Tenant)가 `tokenId`·`admitToken`만
들고 오므로 월을 술어에 넣을 방법이 없다. 비용은 **보관 파티션 수에 비례**하며,
**미리 만들어 둔 미래 파티션도 프로브 대상**이다. 현재 실측으로는 무해하다.

**③ `issued_at`을 원본 그대로 싣는 제약은 남는다 — 그리고 그 비용은 0이다.** 모든 전이 경로가
`tokenId`를 얻는 **같은 `HGET`**에서 `issuedAt`을 함께 받는다. 지킬 것이 없어 제약이랄 것도 없다.

**④ 문서 4곳을 정정했다.** §26(선택 이유·면접 포인트), §44(문제 서술·Step 1·트레이드오프 표),
`ROADMAP` Sprint 9 DoD, `README` 트레이드오프 표. `schema.sql`은 2026-08-17에 이미 정정돼 있었고
**나머지가 그 정정을 반영하지 않아 정본끼리 반대를 말하고 있었다.**

### Interview Point

> "파티셔닝하면 Partition Pruning으로 빨라지지 않나요?"

**저희는 안 됩니다.** 파티션식이 `YEAR(c)*100+MONTH(c)`인데 옵티마이저가 이 식을 단조라고 증명하지
못해서, `issued_at` 범위 조건으로는 13개 파티션을 전부 훑습니다. 실측으로 확인했고 문서도 그렇게
고쳤습니다.

**그래도 파티셔닝을 유지합니다. 저희가 산 것은 프루닝이 아니라 `DROP PARTITION`이거든요.** 월별
정리가 밀리초에 끝나고, 그게 없으면 수천만 행 `DELETE`가 binlog를 통째로 복제로 밀어 넣습니다.
프루닝은 집계 배치에서 `PARTITION (p2026_04)`로 파티션을 직접 지목해 따로 얻습니다 — 배치는 어차피
대상 월을 아니까요.

`RANGE COLUMNS(issued_at)`로 바꾸면 범위 조건도 프루닝됩니다. 실측도 해봤습니다. 안 바꾼 이유는
**이득이 월 1회 배치에서 54ms가 53ms 되는 것**이었기 때문입니다. 파티션 12개 중 11개가 비어 있어서
스캔해도 페이지 몇 개거든요. 되돌리기 어려운 변경을 3% 때문에 하진 않았습니다.

---

## §84 — BillingSnapshotJob: 매일 2개월 재집계 (그리고 실측이 뒤집은 것 5개)

> ⚠️ **§86에서 매월 1일 00:05로 바꿨다가 되돌렸다(왕복).** 아래 세 근거는 **전부 유효하다** —
> 바꿔 보니 그중 둘을 포기하는 대가가 생각보다 컸다. 왕복의 기록과 이유는 §86 "주기" 절에 있다.

### Context

B2B SaaS인데 **과금 구현이 0건**이었다. 스키마(`billing_snapshots`)와 집계 SQL 예제
(`schema.sql` Step 2)는 2026-04부터 있었지만, 그걸 실행하는 코드가 없었다.

과금 단위는 §82가 확정했다 — **발급된 `tokens` 행 하나**. 상태를 보지 않는다. 그래서 집계 자체는
`GROUP BY tenant_id`에 `COUNT(*)` 하나면 끝나는 일이었다. **문제는 집계식이 아니라 그 주변이었다.**

### Decision

**① 주기: 월 1회 → 매일 UTC 00:30, 전월 + 당월 재집계.**

원안(`ROADMAP` Sprint 9)은 "월 1회, M+2월 초"였다. 그건 **파티션 DROP의 일정**이지 집계의 일정이
아니다. 둘을 한 잡에 묶어 두면 집계가 DROP 일정에 인질로 잡힌다. 떼어내면:

- 테넌트가 **당월 사용량을 그날 본다**. 월말에만 채우면 청구서가 나오기 전까지 아무도 자기 요금을 모른다
- 한 번 실패해도 다음 날이 가져간다. **월 1회는 그 한 번이 곧 한 달치 미청구다**
- 16만 행 두 달 집계에 **0.063초**(실기동 실측). 비용이 반박 근거가 못 된다

**전월까지만** 다시 본다. 4/30 23:59:59에 enqueue된 토큰은 5/1 00:30에 아직 Kafka를 타는 중일 수
있다(`ReconcileJob`의 정착 시간과 같은 이유). 반대로 **더 과거는 보지 않는다** — 파티션이 DROP된
달을 집계하면 남은 행만 세어 **이미 청구한 금액을 깎는다**. 원장은 지난 뒤엔 건드리지 않는다.

**② `queue_daily_stats` 집계·파티션 DROP/REORGANIZE는 이 잡에서 뺐다.** 과금이 아니라 파티션
운영이고 DDL이라 성격이 다르다. 미착수로 남는다.

**③ ShedLock·`batch-lock`을 쓰지 않는다.** UPSERT가 멱등이라 batch N대가 각자 같은 값을 쓸 뿐이고,
동시에 돌아도 둘 다 같은 SELECT의 결과라 어느 쪽이 이겨도 정답이다. `ReconcileJob`이 같은 논리의
선례다. 실측: 가상 스레드 8개 동시 UPSERT에서 **데드락 0, 값 정확**.

> ⚠️ **정확성만 같고 비용은 다르다.** `ReconcileJob`의 중복 실행은 큐당 `ZCOUNT` + `COUNT` 읽기지만,
> 이쪽은 **파티션 스캔이 인스턴스 수만큼 곱해진다.** 그걸 감당 가능하게 만드는 것이 아래 ④다.

**④ `FROM tokens PARTITION (pYYYY_MM)`** — §83이 확정한 그 절이다. 아래 "뒤집힌 것 ②" 참조.

**⑤ `@Transactional(isolation = READ_COMMITTED)`** — 안 걸면 집계가 적재를 막는다. 아래 "③" 참조.

---

### 🔴 실측이 뒤집은 것 5개

이 절이 §84의 본론이다. **설계는 30분, 나머지는 전부 아래를 밟느라 썼다.**

#### ① `schema.sql`의 Step 2 예제는 실행하면 죽는다 (4개월간 아무도 안 돌려봤다)

```sql
INSERT INTO billing_snapshots (tenant_id, year_month, count) ...   -- ERROR 1064
```

`YEAR_MONTH`는 MySQL **예약어**(INTERVAL 단위)다. 백틱이 없으면 문법 오류다.

> 🔴 **정정 (2026-08-25, CI 통합 레인 작업 중 실측).** 위 문단의 초판은 *"`CREATE TABLE`은
> 백틱 없이 통과한다 — 컬럼 정의 자리라서"*라고 적었다. **사실이 아니다.** MySQL 8.0.46에서
> `CREATE TABLE probe (year_month CHAR(6))`는 **`ERROR 1064`로 죽는다.** 정의 자리도 예외가 아니다.
>
> 그래서 결론이 오히려 더 나빠진다 — **`schema.sql`의 `CREATE TABLE billing_snapshots`도
> 실행 불가였다.** 그런데도 로컬 DB에는 테이블이 있었다. 즉 **그 테이블은 이 파일이 아닌
> 경로로 만들어졌다**(손으로 쳤거나, 백틱이 있던 옛 버전으로 만들었거나).
>
> **`schema.sql`이 실제로 스키마를 만들 수 있는지 아무도 확인한 적이 없었다는 뜻이다.**
> CI 통합 레인이 스키마를 주입하기 시작하면서 첫 실행에서 드러났다. DDL도 함께 고쳤다.
> (⚠️ 구 서술이 이 자리를 "(§85)"로 참조했으나 §85는 **다른 주제**(큐 Redis 키 purge)로 쓰였다.
>  CI 레인 결정은 별도 절이 없고 근거는 `ci.yml` 주석과 `scripts/ci/*.sh`에 있다.)
>
> 🪤 **교훈은 "DDL이 성공했으니 DML도 되겠지"가 아니라 그 반대다** — 나는 로컬에 테이블이
> **존재한다**는 사실에서 "그 CREATE 문이 성공했었다"를 역추론했다. **존재는 그 파일이
> 만들었다는 증거가 아니다.**

같은 문장에 두 번째 함정도 있었다 — `updated_at = NOW(3)`. `NOW()`는 세션 TZ를 따르는데
`mysql` CLI 세션은 KST다(같은 파일 [시각 규약]이 경고하는 그 함정). **UTC 컬럼에 KST가 들어간다.**
SET 절에서 아예 빼는 것이 답이었다 — `ON UPDATE CURRENT_TIMESTAMP(3)`이 **값이 실제로 바뀔 때만**
찍어 주므로, 재실행해도 "마지막으로 금액이 변한 시각"이 보존된다.

#### ② §83의 `PARTITION` 절을 빠뜨렸다 — 그 한 줄이 C안 기각의 근거였는데

첫 구현은 `FROM tokens WHERE issued_at >= ? AND < ?`였다. §83 Decision 원문:

> 프루닝이 필요한 월말 집계는 `FROM tokens PARTITION (pYYYY_MM)`으로 파티션을 직접 지목한다 —
> **배치는 대상 월을 알고 있다.** `BillingSnapshotJob`은 미구현이므로 작성 시점에 넣으면 되고
> **비용이 0이다.**

이 문장이 `RANGE COLUMNS` 재구축안(C안) **기각의 근거**다. 안 넣으면 §83의 전제가 거짓이 된다.

🪤 **놓친 이유가 더 중요하다.** 기억에 남아 있던 것은 "§83 = 프루닝 안 됨, 손해 3%, **현행 유지**"
뿐이었고, **`PARTITION` 절이라는 나머지 절반이 빠져 있었다.** 결론만 요약하면 그 결론을 성립시키는
조건이 사라진다. 코드 리뷰가 문서 원문 6곳을 들고 와서야 드러났다.

실측: `PARTITION (p2026_08)` → `partitions: p2026_08` **1개**. 범위 조건은 **13개 전부**.

🪤 대가는 **fail-loud**다. 미생성 파티션을 지목하면 `ERROR 1735: Unknown partition`으로 죽는다 —
범위 조건이라면 `p_future`로 **조용히 성공**했을 자리다. 파티션 사전 생성 누락을 청구서가 나온
뒤에 아는 것보다 그날 죽는 게 낫다고 판단했다. 잡의 월별 `try/catch`가 받으므로 한 달이 죽어도
다른 달은 돈다.

#### ③ 🔴 집계가 `tokens` 적재를 막는다 (REPEATABLE READ)

`INSERT ... SELECT`는 REPEATABLE READ에서 source 행에 **shared next-key lock**을 건다.
프루닝이 없으면 `tokens` 전체가 잠긴다.

| 격리수준 | 집계가 도는 동안 `tokens` INSERT |
|---|---|
| REPEATABLE READ (MySQL 기본값) | **6초 대기 후 `ERROR 1205`** |
| READ COMMITTED | **0.033초** |

레포 전체에 격리수준 설정이 **0건**이라(`grep -rni isolation`) 기본값 REPEATABLE READ였다.
막히면 `queue-consumer`의 적재가 밀리고 → Kafka lag → **`ReconcileJob`의 정착 판정까지 오염된다.**
과금 배치가 정합성 대사를 망가뜨리는 경로다.

🪤 **④(PARTITION 절)와 겹치지 않는다.** 당월 집계는 **컨슈머가 지금 쓰고 있는 바로 그 파티션**을
훑기 때문이다. 파티션을 좁혀도 hot 파티션은 그대로 잠긴다. 격리수준을 낮추는 것만이 답이다.
격리수준은 **이 메서드에만** 건다 — 기본값에 기대는 다른 경로를 건드리지 않기 위해서다.

#### ④ 반환값을 해석할 수 없다 — CLI와 JDBC가 다른 값을 준다

ODKU의 영향 행 수를 "갱신된 테넌트 수"로 읽으려 했다. 둘 다 틀렸다:

- ODKU의 UPDATE는 한 행을 **2**로 센다
- Connector/J는 `CLIENT_FOUND_ROWS`를 켜고 붙어서 **값이 안 바뀐 행도 1**로 돌려준다.
  같은 문장이 `mysql` CLI에서는 **0**이다

🪤 **CLI에서 확인한 동작이 앱에서 다르게 나온다.** 처음 작성한 멱등성 테스트가 `isZero()`로
깨지면서 드러났다. 결론: **반환형을 `void`로.** 성패는 예외로 판정하고, 값이 실제로 변했는지는
`updated_at`이 증언한다. 도메인 포트가 JDBC 드라이버 플래그를 설명하고 있던 것도 함께 사라졌다.

#### ⑤ Spring Boot 기본 스케줄러 메트릭은 이 잡의 실패를 못 잡는다

실기동해서 `/actuator/prometheus`를 보고 알았다. Boot가 자동으로 내는
`tasks_scheduled_execution_seconds{outcome="SUCCESS"}`는 **메서드 실행 단위**다. 이 잡은 월별
예외를 안에서 삼키므로(그래야 전월 실패가 당월을 안 막는다) **한 달이 죽어도 `outcome=SUCCESS`**다.

그래서 `queue.billing.snapshot{result=success|failure}` counter를 따로 둔다. **생성자에서 미리
등록**한다 — 한 번도 안 돌면 시계열 자체가 없어 "어제 안 돌았다"를 PromQL로 물어볼 수 없다.
0에서 시작해야 `increase()`가 말을 한다.

---

### 검증 결과 (실기동)

`queue-batch` 실기동(`--spring.profiles.active=local`, cron 임시 20초) — 로컬 MySQL 16만 행:

```
과금 집계 month=2026-07              ← p2026_07 존재, 0행이지만 에러 없음
과금 집계 month=2026-08
queue_billing_snapshot_total{result="success"} 2.0
queue_billing_snapshot_total{result="failure"} 0.0
소요 0.063초
```

`billing_snapshots` **25행 / 합계 160,843건**, `tokens` 원본과 **테넌트별 전수 대조 불일치 0**.

테스트 **9건**(통합 7 실 MySQL + 잡 2). 결함 주입으로 고유성을 실증한 것 둘 —
상한을 `atEndOfMonth() + 23:59:59`로 바꾸면(밀리초 유실) 밀리초 테스트만 잡고,
`SUM(COUNT(*)) OVER ()`로 테넌트 스코프를 없애면 다중 테넌트 테스트만 잡는다.

### 미검증 / 남은 것

- cron `zone = "UTC"`의 **실제 발화 시각**. 어노테이션 값을 어노테이션 값과 비교하는 테스트는
  동어반복이라 만들지 않았다. 실기동은 cron을 덮어썼으므로 이걸 검증한 게 아니다
- **batch 2 프로세스** 동시 실행. 같은 JVM 가상 스레드 8개까지만 확인했다 — DB 레벨 동시성의 근사다
- 🟠 **0건 테넌트의 비대칭.** 토큰이 3→1로 줄면 갱신되지만 3→**0**이 되면 `GROUP BY` 결과에 없어
  **옛 값 3이 남는다**(과다청구). 지금은 전월·당월만 봐서 경로가 좁지만, 유령 토큰 복구가 붙으면
  열린다. 통합 테스트가 "0건 = 행 없음"을 현재 명세로 못박아 뒀다 —
  **조회 측이 행 없음을 0으로 읽어야 한다**는 계약이다
- `queue_daily_stats` 집계 · 파티션 DROP/REORGANIZE (위 Decision ②)

### Interview Point

> "과금 집계요? `COUNT(*)`에 `GROUP BY` 하나면 끝 아닌가요?"

**집계식은 그렇습니다. 저희가 시간을 쓴 건 그 주변이었습니다.**

셋만 말씀드리면, 첫째로 **그 `INSERT ... SELECT`가 적재를 막습니다.** REPEATABLE READ에서
source 행에 shared next-key lock을 걸거든요. 실측으로 토큰 INSERT가 6초 대기하다 `ERROR 1205`로
죽었고, READ COMMITTED에서는 0.033초였습니다. 과금 배치가 큐 적재를 세우는 건데, 그러면 Kafka가
밀리고 정합성 대사의 정착 시간 판정까지 오염됩니다. **배치 하나가 파이프라인 전체를 흔듭니다.**

둘째로 **스키마 파일이 4개월간 실행 불가인 채로 있었습니다.** 컬럼명이 `year_month`인데 그게
MySQL 예약어(INTERVAL 단위)라 백틱이 없으면 `ERROR 1064`입니다. 집계 예제도, **`CREATE TABLE`
자체도** 죽습니다.

그런데 로컬 DB에는 그 테이블이 **있었어요.** 저는 처음에 "DDL은 통과하고 DML만 죽는구나"라고
결론냈는데, CI에 통합 레인을 붙여서 빈 MySQL에 스키마를 주입해 보니 `CREATE TABLE`부터
멈췄습니다. **테이블이 존재한다는 사실이 그 파일이 그걸 만들었다는 증거가 아니었던 겁니다** —
어딘가에서 손으로 만들어졌고, 아무도 그 파일로 스키마를 세워 본 적이 없었습니다.

**빈 환경에 처음부터 세워 보기 전까지는 "우리 스키마 파일"이 동작한다고 말할 수 없다**는 걸
여기서 배웠습니다.

셋째는 좀 부끄러운 건데, **제가 기억하던 결정 요약이 결론만 담고 조건을 버리고 있었습니다.**
파티션 결정(§83)을 "프루닝 안 됨, 손해 3%, 현행 유지"로 기억했는데, 원문은 "월말 집계가
`PARTITION` 절로 프루닝을 따로 얻으니까 현행 유지"였습니다. **그 절이 다른 안을 기각한 근거였는데
제가 그 절반을 빠뜨린 겁니다.** 코드 리뷰가 문서 6곳을 들고 와서야 드러났고, 그 뒤로는 결정을
인용하기 전에 원문을 다시 엽니다.

---

## §85 — 큐 종속 Redis 키의 purge 배치를 만들지 않는다

### Context

`queues` 31행이 살아 있는데 Redis에 큐 키가 남아 있는 것을 보고 "고아 키가 누적된다"고 판단해
purge 배치 안건을 열었다. `architect`·`dba`·`backend` 셋에게 같은 안건을 주고 각자 답을 받았다.

**셋 다 "만들지 마라"였고, 그 과정에서 안건의 전제 세 개가 전부 뒤집혔다.**

### 🔴 뒤집힌 전제 셋 — 여기가 이 절의 본론이다

#### ① "고아 키"가 아니었다 — 대조를 안 하고 단정했다

Redis에 키가 있는 큐 전부가 **DB에 `status = 0`(ACTIVE)로 살아 있다.**
`waiting`이 빈 **유휴 큐의 정상 상태**였다. `seq`는 다음 enqueue가 이어서 써야 할 카운터이고
`admit-watermark`는 pacing 기준이다. **지우면 안 되는 키였다.**

🪤 `queues` 행 수와 Redis 키 수만 보고 **queueId를 대조하지 않은 채** "고아"라고 불렀다.

#### ② 키 개수를 과소계수했다 — Cluster에서 `SCAN`은 접속 노드만 훑는다

```
redis-cli -c -p 7001 --scan 'queue:*'   →  2개
마스터 전수                              →  43개
```

🪤 **이 레포는 그 함정을 이미 문서화해 뒀다** — `QueueRepository.findAll()` javadoc이
"큐 목록은 DB에서 읽는다. Cluster `SCAN`은 노드별로 따로 돌아 조용히 누락된다"고 적고 있다.
**그 경고를 읽고 인용까지 해 놓고, 내 집계가 그 함정을 밟았다.**

#### ③ 위험의 방향이 반대였다 — `watermark > seq`는 구조적으로 불가능하다

"잔존 watermark 때문에 `/status`가 옛 값을 내려 전원이 `rank <= 0`으로 보인다"고 적었다.
`architect`가 잔존 17개를 전수 대조해 반증했다 — **17/17이 `watermark <= seq`**다.

우연이 아니라 불변식이다. `admit.lua`가 watermark에 넣는 값은 `ZPOPMIN waiting`이 뱉은
score이고, waiting의 모든 score는 `INCR seq`가 만든 값이다. 그리고 **`seq`를 지우는 코드가
레포에 0건**이다. 따라서 재사용 시 새 토큰의 seq는 반드시 watermark보다 크다.

> **`watermark > seq`가 되는 유일한 길은 `seq`만 지우는 것이다.**
> 즉 이 위험은 "정리를 안 해서 생기는 사고"가 아니라 **"부분 purge를 하면 생기는 사고"**다.
> 만들 근거가 아니라 **만들지 말 근거**였다.

### Decision

**purge 배치를 만들지 않는다.** `queues` 행 하드 삭제도 하지 않는다(§44 재확인).
**`seq`는 "지우지 않는 키"로 못박는다.**

### Rationale

- **누수 실측 205 B/큐.** 마스터당 `maxmemory 1gb` × 8대 기준 **큐 40만 개**까지 무해하다
- **컬렉션 키 5종은 이미 자기정리된다.** 토큰 160,843건이 지나갔는데 `tokens`·`last-active`·
  `admitted`·`pacing` **잔류 0**이다 — 회수 3경로가 실제로 다 지운다는 증거다.
  새는 것은 스칼라 2종(`seq`·`admit-watermark`)뿐이고 "7종이 남는다"는 전제도 틀렸다
- **`status = DELETED` 트리거는 안 걸린다.** 31개 중 DELETED가 **0건**이다.
  `delete()`는 PAUSED에서만 오는데 테넌트가 `pause`→`delete`를 부를 유인이 없다.
  만들면 **첫날부터 대상 0인 잡**이 돈다
- **배치 순회 비용은 이 안건과 무관하다.** `TokenReclaimJob`이 도는 근거는
  `queueRepository.findAll()` = **DB 행**이지 Redis 키가 아니다. 키를 아무리 지워도
  순회는 1회도 안 준다. **두 문제를 한 배치로 묶으려던 것이 이 안건의 함정이었다**
- **`redis_cluster_no`가 `queues` 행에만 있다.** 행을 지우면 그 큐의 키가 A인지 B인지 알
  방법이 사라진다 — **하드 삭제는 purge를 돕는 게 아니라 불가능하게 만든다**

### 🔴 `seq`를 지우면 되돌릴 수 없다

`tokens`에 `UNIQUE (queue_id, seq)`가 **없다**(있는 것은 `(token_id, issued_at)`뿐).
seq가 1부터 재시작해도 DB가 막지 않는다. 조용히 중복 `(queue_id, seq)` 행이 쌓이고
`ReconcileJob`의 `COUNT(*) WHERE status = 0 AND seq <= settledSeq`가 옛 이벤트 행을 함께 센다.
**그리고 무너졌다는 사실을 대사 자신이 오염돼 있어 알 수 없다.**

seq가 정체성 역할을 하는 곳이 넷이고 각각 다르게 깨진다.

| 어디 | seq의 역할 | 되감기면 |
|---|---|---|
| `waiting` ZSet | score | 같은 score 2건 → `members[1]`이 임의 선택 → **정당한 사용자가 404** |
| `last-active` ZSet | **member** | 새 `ZADD`가 옛 항목의 score를 덮어쓴다 → 좀비가 되살아난다 |
| DB `tokens.seq` | `MAX(seq)`·`seq <= :maxSeq` | 대사 기준선 붕괴, §71 Redis 전손 복원 붕괴 |
| `route()` | `EXISTS seq`가 소유 클러스터 판정 | 소유권 증명이 사라진다 |

### Consequences

- **큐를 재사용하면 첫 admit 전까지 rank가 부풀고 폴링이 느려진다.**
  잔존 watermark가 작아 `rank = mySeq - wm`이 과대평가되고, `pacing` 마지막 구간에 걸려
  폴링이 2초가 아니라 20초가 된다(admitToken TTL 60초의 1/3을 수령 지연으로 태운다).
  🔑 **고치는 것은 삭제가 아니라 리셋**(`SET watermark = GET seq`)이다 —
  지우면 `readStatus`가 `null → 0`으로 읽어 **rank가 더 커진다.**
  그리고 "이벤트가 시작됐다"를 아는 주체는 배치가 아니라 **테넌트**다. 지금은 만들지 않는다
- **큐 1만 개 지점에서 `findAll()` 순회가 먼저 포화된다.** 그때의 해법도 purge가 아니라
  `findAll()`에 술어를 넣는 것이다 — 별건
- **레거시 무태그 키 `queue:q_bts:*` 2개**는 어떤 코드로도 도달 불가능한 사물로 남는다.
  `QueueKeys`는 `queue:{q_bts}:*`(태그 있음)를 조립하므로 **다른 슬롯·다른 노드**다.
  Sprint 5 해시태그 학습 때 손으로 만든 키이고, 정리한다면 `redis-cli del` 2회다 —
  **배치가 할 일이 아니다.** 🔑 그리고 이것이 보여주는 원리: **DB 기반 purge는 고아를
  원리적으로 못 본다.** 고아의 정의가 "DB에 행이 없다"인데 목록을 DB에서 가져오기 때문이다
- ⚠️ **더 급한 것이 따로 나왔다** — `queue_daily_stats`가 **0행 · 쓰는 코드 0건**이다.
  `schema.sql` Step 1(파티션 DROP 전 필수 집계)이 미구현이라, M+2 DROP이 실행되면
  **큐 단위 청구 근거가 영구 소실**된다. 남는 건 `billing_snapshots`의 테넌트·월 합계뿐이라
  "어느 큐가 얼마"를 분해할 수 없다. **purge보다 이쪽이 먼저다**

### Interview Point

> "안 쓰는 Redis 키가 쌓이면 정리 배치를 만들어야 하지 않나요?"

**저희는 만들지 않기로 했고, 그 판단 과정에서 제가 세운 전제가 세 개 다 틀렸습니다.**

처음엔 "고아 키가 쌓인다"고 봤는데 **queueId를 DB와 대조해 보니 전부 살아 있는 큐**였습니다.
`waiting`이 빈 유휴 큐의 정상 상태였고, 지우면 안 되는 키였습니다.

두 번째로 **개수를 잘못 셌습니다.** Cluster에서 `SCAN`은 접속한 노드만 훑는데 한 노드에서만
세서 2개로 봤습니다. 전수로는 43개였습니다. 더 부끄러운 건 **저희 코드에 그 함정이 주석으로
적혀 있었고 제가 그걸 인용까지 했다**는 겁니다.

세 번째가 결정적인데, 제가 제시한 위험이 **방향이 반대**였습니다. "잔존 watermark 때문에
전원이 앞줄로 보인다"고 했는데, 잔존분 17개를 전수 대조하니 **전부 `watermark <= seq`**였습니다.
watermark에 들어가는 값이 `ZPOPMIN`이 뱉은 score이고 그건 `INCR seq`가 만든 값이라
**구조적으로 그럴 수가 없습니다.** `watermark > seq`가 되는 유일한 길은 **`seq`만 지우는 것**,
즉 제가 만들자던 그 배치가 하는 일이었습니다.

**그래서 얻은 것은 "안 만든다"라는 결론보다, 안 만들 이유를 재는 과정 자체입니다.**
205바이트, 40만 큐, `watermark <= seq` 불변식 — 이 셋이 없었으면 저는 필요 없는 배치를
만들고 그 배치가 순번을 되감아 대사를 조용히 오염시켰을 겁니다.

---

## §86 — queue_daily_stats: 파티션 DROP 이후를 위한 큐×일 집계

**결정일**: 2026-08-26
**관련**: §80(admit_requests 폐기) · §82(과금은 상태 무관) · §83(파티션 프루닝) · §84(BillingSnapshotJob)

### Context

`tokens`는 월별 RANGE 파티션이고 M+2월에 파티션째 사라진다. 그 뒤 남는 집계표는
`billing_snapshots`(tenant×month 합계)뿐이다. 테넌트가 큐를 **둘 이상** 쓰는 순간
"어느 큐가 어느 날 얼마나 받았나"는 **영구히 재구성 불가능**해진다.

`queue_daily_stats`는 스키마에 2026-04부터 있었으나 **0행 · 참조 코드 0건**이었고,
집계 SQL(`doc/schema.sql` Step 1)은 **실행하면 잘못된 값을 영구화하는 상태**였다.

### Decision

집계를 `BillingSnapshotJob`에 얹는다(새 잡·새 스케줄·새 ShedLock 없음). 컬럼은 아래로 확정한다.

| 컬럼 | 산식 | 이유 |
|---|---|---|
| `stat_date` | `DATE(issued_at)` (UTC) | 파티션 축·billing 축과 동일 |
| `total_enqueued` | `COUNT(*)` | 큐 단위 청구 근거. 이 표의 존재 이유 |
| `total_admit_issued` | `SUM(admitted_at IS NOT NULL)` | **`status`로 대체 불가** |
| `total_completed` / `total_expired` | `SUM(status = 2)` / `SUM(status = 4)` | 상태가 정본 |
| `sum_wait_sec` (BIGINT) | `SUM(TIMESTAMPDIFF(SECOND, issued_at, admitted_at))` | **AVG는 합산 불가** |
| `max_wait_sec` | `MAX(...)` | MAX(MAX)는 합산된다 |

**남기지 않는 것**: `avg_wait_sec`(비합산), `p50`/`p99`(비합산·재버킷 불가), 큐 생성/종료 시각·
정원(`queues` 행이 soft delete라 영속), `idx_queue_daily_tenant`(읽는 쿼리 0건),
만료 2분할(`total_admit_issued - total_completed`로 파생 — 실측 15,151로 정확히 일치).

### Rationale — 실측이 정한 것 다섯

**① `status`로는 입장권을 셀 수 없다.** `SUM(status = 1)`은 **0**이 나온다.
`ReconcileJob`이 complete 창(300초)을 넘긴 `ADMIT_ISSUED` 잔류를 `status = 4`로 정리하기
때문이다. 실측에서 **15,151건이 `status = 4` 아래 숨어 있었다.** `status`는 집합을 분할하고
(2 + 160,819 + 22 = 160,843, 정확히 맞는다) `admitted_at`은 그 분할을 **가로지른다** —
그래서 둘 다 필요하다. 취향이 아니라 구조다.

**② `ON DUPLICATE KEY UPDATE id = id`는 멱등이 아니라 불변이었다.** 원안의 그 구절은
늦게 도착한 admit을 **영원히** 반영하지 않는다(도커 실증). 그리고 늦게 admit되는 토큰이
곧 **가장 오래 기다린 토큰**이라, 하필 이 표가 남기려던 것만 골라서 버린다. 전 컬럼을 덮어쓴다.
같은 이유로 `schema.sql`의 "INSERT-only 불변 테이블" 선언도 폐기한다.

**③ 평균은 합산되지 않는다.** 일별 `AVG` 30개로 월 평균을 못 만든다(각 날의 표본 수를 모르면
가중을 못 준다). `SUM` + 분모(`total_admit_issued`, 어차피 저장한다)면 컬럼 수는 같고 정보는
우위다. 대가가 0인 순수 우위라 논의할 자리가 아니다. **`BIGINT`인 이유**: 설계 전제인 한 큐
30만 × `waitingTtl` 7200초 = **2.16e9 > INT_MAX**. 실측에서 14,747건만으로 이미 44,441,534다.

**④ 대기 기준은 `admitted_at`이다.** `completed_at`은 두 번 오염돼 있다 — 테넌트 내부 처리
시간이 섞이고(책임 경계 밖), 값 자체가 **컨슈머가 적용한 시각**이라 Kafka lag이 그대로 더해진다.
`issued_at → admitted_at`이 Platform이 단독으로 책임지는 유일한 구간이다. 실측 표본도
`completed_at` 22건 vs `admitted_at` 15,173건 — 전자로 돌리면 25개 큐 대부분이 **영구 NULL**이다.
§64가 `avgWaitingTime`을 기각한 근거가 정확히 이 오염이었다. **§64가 기각한 것은 틀린 지표였고,
옳은 지표는 검토된 적이 없다.**

**⑤ `PARTITION (pYYYY_MM)`은 프루닝이자 가드다.** §83대로 범위 조건은 프루닝이 안 된다
(25개 전부 스캔). 더 중요한 건 **이미 DROP된 달을 실수로 재집계하면 남은 행만 세어 통계를
조용히 0으로 깎는다**는 것이다. `PARTITION` 절이 있으면 그 자리에서 `ERROR 1735`로 죽는다.

### 소비자를 함께 만든다 (`countBillingMismatch`)

쓰는 코드만 있고 읽는 코드가 0건이면 미완성이다(`CLAUDE.md` §4-1). 소비자는 **롤업 대사**다 —
`queue_daily_stats`를 tenant×month로 접으면 `billing_snapshots`와 **정확히 같아야 한다**
(같은 파티션 · 같은 UTC 경계 · 상태 술어 없음 §82). 어긋날 구조적 이유가 없으므로
**어긋남 자체가 사고 신호**이고, 게이지 `queue_billing_mismatch`로 낸다.

이 소비자에는 §4를 통과하는 **독립적 존재 이유**가 있다: 두 표는 서로를 검증할 수 있는 유일한
짝이고, `tokens`가 사라진 뒤엔 어느 쪽도 원본과 대조할 수 없다. 그때 청구액과 그 근거가 다르면
**어느 쪽이 맞는지 판정할 방법이 영영 없다.** 원본이 살아 있는 동안만 잡을 수 있다.

🪤 `JOIN`이 아니라 `UNION ALL` + `GROUP BY`다. JOIN이면 **한쪽 표에만 있는 테넌트가 결과에서
통째로 빠져 "불일치 0"이 된다** — 한쪽이 아예 비는 것이 가장 큰 사고인데 그게 가장 조용해진다.

### Consequences

- **admit 요청 수는 이 레포 어디에도 남지 않는다.** §80이 "장기 이력은
  `queue_daily_stats.total_admit_count`"라고 위임했지만, `admit_requests` 폐기로 **소스 자체가
  사라졌다.** `tokens`에서 뽑히는 건 **발급된 입장권 수**뿐이고 호출 1회에 N명이 나간다.
  요청 수가 필요하면 Micrometer 카운터 소관이다 — 일별 테이블이 받을 수 없다.
- **대기 시간 분포는 남지 않는다.** `sum`/`max` 두 숫자뿐이라 DROP된 달의 p50/p99는 영구 소실이다.
  히스토그램 버킷(`le_60`~`le_3600`, 추가 스캔 0)이 유일한 합산 가능 형태로 검토됐으나,
  **읽는 사람이 0건이라 §4에 걸려 보류**했다. 대안인 `queue_admission_wait_seconds` 메트릭도
  현재 구현 0건이므로, **지금은 대기 시간 분포가 어디에도 안 남는다.**
- ~~만료 사유는 구분되지 않는다~~ → **해소했다(아래 별항)**.
- **`max_wait_sec`에 음수가 들어올 수 있다.** `issued_at`·`admitted_at` 둘 다 앱 시계라 API 서버
  N대의 스큐만큼 어긋난다(실측 -398초 1건, 1행짜리 큐라 그 값이 곧 MAX였다).
  `GREATEST(..., 0)`으로 가리지 않는다 — 스큐 신호를 숨기게 된다.
- **`stat_date`는 UTC 날짜다.** 같은 행의 `created_at`은 KST라 **한 행에 두 시간대가 공존**한다.
  월 경계에서만 어긋나는 billing과 달리 **매일 어긋난다.** 그래도 UTC를 유지한다 —
  KST로 자르면 파티션 프루닝과 롤업 대사가 둘 다 깨진다(§77 미해결의 일 단위 재발).
- ~~`created_at`은 KST라 한 행에 두 시간대가 공존한다~~ → **정정**: 배포 경로의 jdbc-url이
  전부 `forceConnectionTimeZoneToSession=true`라 `CURRENT_TIMESTAMP(3)`도 UTC다.
  내가 본 KST 값은 **`mysql` CLI로 손으로 돌린 흔적**이었다. 수동 실행 한정 현상이다.
- `doc/CONCURRENCY.md`의 "`queue_daily_stats`는 INSERT-only라 ShedLock 필요"는 **거짓이 됐다.**
  `uq_queue_daily_stat`이 중복 INSERT를 `ERROR 1062`로 막고 ODKU 덮어쓰기가 멱등을 만든다.
  §84와 완전히 같은 논거다. 함께 정정했다.

### 파티션 DROP을 같은 배치에 넣는다 — 순서가 곧 게이트다

**집계가 성공해야만 DROP이 가능하다.** 분리하면 순서를 사람이 지켜야 하고, 한 번 어긋나면
그 달 원본이 집계 없이 사라진다. 같은 메서드에 순서대로 두면 **앞이 던지면 뒤에 도달하지 못한다** —
별도 게이트도 플래그도 필요 없다. `architect`가 Q4에서 주장한 "순서 강제"가 이 형태다.

**🔴 DROP 직전에 그 달을 한 번 더 집계한다 — 안 그러면 미청구가 생긴다.**
DROP 대상 달은 **정기 집계 대상에 없다**. 6/5에 지우는 건 4월인데 그날 잡이 보는 건 5월·6월이다.
4월의 마지막 집계는 그 이전이고, **그 뒤에 도착한 4월 토큰은 아무도 세지 않은 채 파티션과 함께
사라진다.** 이 창은 §84의 "전월을 그 달 내내 다시 덮어쓴다"로도 안 닫힌다 — 대상 달이 다르다.

**관문 셋**: ① 파티션 존재 ② 집계가 예외 없이 완료 ③ 대사 0 **그리고** 원본이 비지 않았는데
집계가 0행은 아님. ③의 뒷절이 없으면 **두 집계가 나란히 실패해 둘 다 0행일 때 대사가 0을 돌려주고**,
그 상태로 원본이 통째로 사라진다.

**🔴 `lock_wait_timeout`이 DROP의 핵심이다 — 기본값이 365일이다.**
`DROP PARTITION`은 **테이블 전체에 배타적 MDL**을 잡는다. 대상 파티션이 비어 있어도 소용없다.
긴 트랜잭션이 `tokens`를 물고 있으면 DDL이 대기하는데, **그 뒤에 도착한 평범한 INSERT가 전부
그 뒤에 줄을 선다** — 실측: 5초 트랜잭션 뒤에서 DROP이 4.17초 대기, **그 사이 도착한 INSERT가
3.05초 블록**. MySQL 기본 `lock_wait_timeout`은 **31,536,000초 = 365일**이라 대기에 상한이 없다.

3초로 걸면 `ERROR 1205`로 즉시 포기하고 뒤에 막힌 요청도 그때 풀린다(실측: INSERT 블록
3.05초 → **1.04초**). 포기해도 되는 이유는 **DROP이 늦어서 잃는 것이 스토리지뿐**이기 때문이다.
유예가 한 달이라 다음 주기가 다시 하면 된다 — **지연은 공짜고 블로킹은 사고다.**

🪤 세션 변수라 커넥션 풀에 남는다. `SET SESSION`을 DDL과 한 문장으로 보내면 반납된 커넥션에
값이 남아 **다른 쿼리가 3초 만에 포기**한다. 원복까지 한 커넥션 안에서 한다.

> **시각을 옮기는 것은 답이 아니다.** B2B 멀티테넌트라 한가한 시각이 존재하지 않는다 —
> 테넌트마다 피크가 다르고 우리는 그걸 모른다. UTC 00:05는 KST 09:05라 수강신청·예매 오픈이
> 흔한 시간대지만, 옮기면 다른 테넌트를 때린다. **언제 돌든 안전하게** 만드는 쪽이 맞다.
> (집계 쪽 같은 위험은 §84가 `READ COMMITTED`로 이미 막았다 — 6초 블록 → 0.033초.)

**ShedLock을 쓰지 않는 근거가 이 잡의 다른 부분과 다르다.** 앞의 UPSERT들은 "멱등이라 누가 이겨도
정답"이었지만 DDL은 멱등하지 않다. 대신 **MySQL의 메타데이터 락이 직렬화한다** — 한 대만 성공하고
나머지는 `ERROR 1507`을 받는다. 결과가 같아 피해가 없으므로 삼킨다. `doc/CONCURRENCY.md`의
"파티션 DROP = 분산 락"을 함께 뒤집었다.

### 주기: 매일 00:30 UTC — 월 1회로 바꿨다가 되돌렸다

**마감 시각과 실행 주기는 다른 문제다.** 마감(= 한 달을 확정하는 순간)은 월 경계를 넘긴
**1일 첫 실행**에서 일어나고, 실행을 매일 하는 것은 그 마감을 **보호**하기 위해서다.

🪤 **월말 23:59에 마감하면 그 달 마지막 60초를 못 센다** — 다음 마감이 한 달 뒤라
청구서가 나간 뒤에야 잡힌다. **경계를 완전히 넘긴 뒤**에 마감해야 누락 창이 0이다.
이 통찰 때문에 한때 `0 5 0 1 * *`(매월 1일 00:05)로 바꿨는데, **커밋 전 검토에서 그 변경이
자기 방어 장치 셋을 동시에 무너뜨린다는 것이 드러나 되돌렸다.**

| 무너진 것 | 왜 |
|---|---|
| **DROP 재시도** | 대상이 `current-2`라 매 실행 이동한다. 8월 실행이 6월에 실패하면 **6월을 다시 보는 실행이 없다.** 게다가 `DROP_LOCK_WAIT_SECONDS = 3`은 **포기를 정상 경로로 설계한 값**이라 실패가 드물지 않다 |
| **늦은 admit 흡수** | "전월을 그 달 내내 다시 덮어쓴다"가 **월초 1회**가 된다. 그런데 덮어쓰기를 채택한 이유 **자체**가 그거였다 — 늦게 붙는 admit이 곧 **가장 오래 기다린 토큰**이다. 두 달간 빠진다 |
| **대사 게이지** | `mismatch`가 **한 달 내내** 낡은 값을 보고한다. batch 재기동 직후에는 `AtomicLong` 초기값 0, 즉 **"정상"으로 보인다** |

🔴 **그리고 00:05은 `ReconcileJob.SETTLE_SECONDS = 300`과 정확히 같아 여유가 0이었다.**
말일 23:59:59에 enqueue된 토큰의 행이 00:05:01에 적재되면 **두 표에서 똑같이 빠지고**,
`countBillingMismatch`는 양쪽이 같이 놓쳤으므로 **0(정상)**을 돌려준다 —
"두 표가 서로를 감시하는 유일한 등식"이 하필 이 사고 유형에서 성립하지 않는다.
**00:30은 그 6배 여유다.**

과금 기준이 **enqueue**이므로(§82) 월 경계에 걸친 큐는 토큰이 갈려 **앞뒤 달에 각각 청구**된다.
만료 여부는 보지 않으므로 이 분할에 상태가 개입하지 않는다.

> **교훈**: 마감 시각을 옮기는 결정이 **재시도·흡수·관측 셋에 동시에 걸려 있었다.**
> 셋 다 "매일 돈다"를 전제로 쓰인 장치인데, 주기를 바꾸면서 그 전제를 확인하지 않았다.
> `CLAUDE.md` §4-2(고치기 전에 기존 로직과 모순되지 않는지 확인한다)가 여기서 나왔다.

### 검토

`dba`·`architect`·`monitoring` 3인 병렬 검토 + `code-reviewer`·`tester` 커밋 전 검토. 제안 3건(①④ 및 "큐 시각 저장 안 함")은 전원 동의.
**내가 놓친 ②를 셋이 공통으로 찾았다.**

의견이 갈린 둘:
- **`p95` 컬럼**: `monitoring`이 권고했으나 **같은 보고서 안에서 "p95는 롤업이 안 된다"고 스스로
  적었다.** `dba`·`architect`가 기각. 기각 채택.
- **만드는 시점**: `dba`는 "`BillingSnapshotJob`에 얹어라", `architect`는 "DROP 잡과 같은 변경으로
  미뤄라". **같은 사실(DROP 코드 0건)에서 정반대 결론.** → **둘 다 채택했다.** 정기 집계는 잡에
  얹고(dba), DROP은 그 집계 뒤에 순서로 묶었다(architect). 두 주장이 배타적이지 않았다.

**커밋 전 검토(`code-reviewer`·`tester`)가 잡은 것 — 둘이 독립적으로 같은 🔴을 짚었다:**
- 🔴 **당월 대사는 상시 어긋난다.** 두 UPSERT가 별개 트랜잭션이라 그 사이 컨슈머 적재분이
  daily에만 잡힌다. 내가 "어긋날 구조적 이유가 없다"고 쓴 전제가 **당월에는 거짓**이었다.
  → 대사를 정착월로 한정.
- 🔴 **`countBillingMismatch`가 replica를 읽고 있었다.** `@Transactional(readOnly = true)`가
  라우팅을 replica로 보내는데, 방금 master에 쓴 두 표를 대조하는 조회다. 복제 지연이 곧 오탐이다.
  통합 테스트는 replica url이 master를 가리켜 **구조적으로 못 잡는 자리**였다. → 트랜잭션 제거.
- 🔴 **`sum_wait_sec`을 `AVG(...) * COUNT(*)`로 바꿔도 14건이 전부 통과**했다(`tester` 실측).
  admit 수 ≠ 행 수인 그룹에서 SUM을 단정하지 않았기 때문. 같은 이유로 `PARTITION` 절 제거,
  `GREATEST(...,0)`, ODKU 4컬럼 누락도 통과했다. → 변이 4종을 주입해 **전부 빨개지는 것을 확인**했다.
