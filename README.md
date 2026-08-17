# 🚀 Queue Platform

> 대규모 트래픽 상황에서 서버 부하를 제어하기 위해  
> 대기열을 외부 플랫폼으로 분리한 Queue-as-a-Service

[![Java](https://img.shields.io/badge/Java-21-007396?logo=java)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3.4-6DB33F?logo=springboot)](https://spring.io/projects/spring-boot)
[![Redis](https://img.shields.io/badge/Redis-Sentinel-DC382D?logo=redis)](https://redis.io/)
[![Kafka](https://img.shields.io/badge/Kafka-Spring_Kafka-231F20?logo=apachekafka)](https://kafka.apache.org/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql)](https://www.mysql.com/)

---

## 🔥 TL;DR

- 대기열을 서비스 서버에서 분리 → **트래픽 제어를 플랫폼화**
- **Platform(순서 관리)** vs **Tenant(슬롯·입장 제어)** 책임 분리
- **유저가 Platform에 직접 Polling** — `/status` 전광판 + `pacing` 구간표 (전원 동일 응답 → 캐시)
- **Backpressure Pull** — Tenant가 소화 가능한 인원만 admit 요청
- **admitToken TTL 60초** → verify(유효성 확인) → complete(COMPLETED+ZREM)
- **admitToken 만료 시 WAITING 복귀** — seq 유지로 우선순위 보존
- **Kafka 버퍼** — Enqueue는 순번 확정 → Kafka 발행(동기) → 200 응답. **DB INSERT만 비동기**
- **Virtual Thread** — Spring MVC + JPA blocking I/O를 OS Thread 고갈 없이 처리
- **SDK 제공** — JS SDK(폴링·대기 UI 전용)만. Tenant 서버는 REST 직접 호출 (DECISIONS §35 · §78)

---

## 📌 문제 정의

트래픽이 몰릴 때 서버가 대기열을 직접 관리하면:
- 동시 접속 폭증 → 서버 자원 고갈
- 대기열 로직과 비즈니스 로직 강결합 → 복잡도 증가
- 순서 꼬임, Race Condition

---

## 💡 핵심 설계 원칙

### 1. Platform은 순서만 관리한다
```
Tenant가 슬롯 여유 감지 → POST /admit 호출
Platform은 순번 관리만. 세션 관리는 Tenant 책임
```

### 2. 유저가 Platform에 직접 Polling (적응형 간격 — DECISIONS §79)
```
GET /queues/:queueId/status  → { lastAdmittedSeq, pacing }   ← 전원 동일. 캐시 가능
  rank = mySeq − lastAdmittedSeq        ← SDK가 뺄셈 (서버는 rank를 계산하지 않는다)

pacing 기본 구간표 (Redis 키로 오버라이드 가능):
  rank ≤ 50     → 2s (곧 입장)
  rank ≤ 1000   → 5s
  rank ≤ 5000   → 10s
  rank ≤ 10000  → 15s
  그 이상        → 20s (서버 부하 절약)

JS SDK: setTimeout(poll, 간격 × 1000 + ±20% 지터) 자동 적용
탭 비활성화 → Polling 자동 중단
rank ≤ 0 일 때만 개인 엔드포인트로 admitToken 확인
```

### 3. Backpressure Pull
```
Tenant가 소화 가능한 만큼만 admit { count: N }
Platform과 커플링 없음
```

### 4. admitToken TTL 만료 → WAITING 복귀 (우선순위 보존)
```
TTL 60초 초과 → WAITING 복귀
seq DB 저장 → Redis ZADD score 복원
→ 다음 admit 호출 시 앞순서이면 재발급

이유: 네트워크 지연 등 유저 귀책 아닐 수 있음
     EXPIRED 처리 시 유저가 맨 뒤로 → 불공평
```

### 5. Virtual Thread + Spring MVC
```
spring.threads.virtual.enabled=true 한 줄로 적용
Tomcat의 모든 요청이 Virtual Thread에서 처리
JPA blocking → OS Thread 점유 없이 대기
@Transactional + ThreadLocal → Virtual Thread 정상 동작
→ Polling 2,000 rps + JPA 동시에 가능
```

### 6. Kafka Enqueue 버퍼
```
Redis Lua 처리 → Kafka 발행(동기) → 200 응답 (seq·rank 확정)
Kafka token-lifecycle (key=tokenId) → DB INSERT (At-Least-Once)
→ Enqueue p99 50ms 이하 달성
→ redis_sync_needed: Redis 다운 중 INSERT 토큰 추적
```

---

## 🏗 아키텍처

```mermaid
flowchart TD
    User["👤 유저\n브라우저/앱"]
    Tenant["🖥 Tenant 서버"]
    API["⚡ Queue Platform API\nSpring MVC · Tomcat · Virtual Thread"]
    Batch["⏱ Batch Server\n@Scheduled Jobs"]
    Consumer["📥 queue-consumer\nKafka 소비 전담"]
    Kafka["📨 Kafka\ntoken-lifecycle (key=tokenId)"]
    Redis["🔴 Redis Sentinel\nMaster + Slave 2 + Sentinel 3"]
    DB_M["🗄 MySQL Master"]
    DB_R["🗄 MySQL Read Replica"]

    User -->|"④ Polling (적응형 간격)"| API
    User -->|"⑥ admitToken"| Tenant
    Tenant -->|"③ Enqueue → 200"| API
    Tenant -->|"⑤ admit"| API
    Tenant -->|"⑦ verify"| API
    Tenant -->|"⑨ complete"| API
    API -->|"Lua Script\n(ZADD, ZREM 등)"| Redis
    API -->|"이벤트 발행\n(produce)"| Kafka
    Kafka -->|"지속 구독\n(consume)"| Consumer
    Consumer -->|"DB INSERT (tokens)\n배치 · 멱등"| DB_M
    Batch -->|"@Scheduled\n(ZRANGEBYSCORE, ZREM\nbatch-lock)"| Redis
    Batch -->|"DB UPDATE (expire)\n@Transactional"| DB_M
    API -->|"SELECT\n@Transactional(readOnly)"| DB_R
    API -->|"UPDATE\n(complete, cancel)\n@Transactional"| DB_M
    DB_M -->|"복제"| DB_R
```

---

## 📦 모듈 구조

```mermaid
flowchart LR
    subgraph IN["Adapter In"]
        api["queue-api\nMVC Controller"]
        batch["queue-batch\n@Scheduled Jobs"]
        consumer["queue-consumer\nKafka 소비 전담 (독립 앱)\ntoken-lifecycle → DB 적재"]
    end

    subgraph DOMAIN["Domain"]
        domain["queue-domain\nEntity · UseCase · Port\nRich Domain Model"]
    end

    subgraph OUT["Adapter Out"]
        infra["queue-infrastructure\nQueueKeys · RedisKeyFactory\nJPA Repository\nKafka Producer"]
    end

    common["queue-common\nErrorCode · BusinessException\nIdGenerator · RawKeyGenerator"]

    api --> domain & infra & common
    batch --> domain & infra & common
    consumer --> domain & infra & common
    infra --> domain & common
    domain --> common
```

```
의존성 원칙:
  queue-common  ← 모든 모듈이 직접 의존 (명시적 선언)
  queue-domain  ← queue-api, queue-batch, queue-consumer, queue-infrastructure
  queue-domain은 Spring 의존 없음 (순수 Java)
  queue-infrastructure는 queue-api/batch/consumer를 절대 모름
  queue-consumer는 아무도 참조하지 않는다 (최말단)
```

> **`queue-consumer`를 `queue-batch`와 합치지 않는 이유**: 확장 방향이 반대다. 소비는 파티션 수만큼
> 늘려야 하고, 스케줄 작업은 늘릴수록 중복 실행 방지가 필요해진다 ([DECISIONS §73](doc/DECISIONS.md) D20).
> actuator + micrometer-prometheus를 갖는다 — 없으면 `/actuator/prometheus`가 아예 생기지 않아
> **컨슈머 lag을 PromQL로 볼 수단이 사라진다**.

---

## 🧠 Token 상태 머신

```mermaid
stateDiagram-v2
    [*] --> WAITING : POST /tokens\nEnqueue
    WAITING --> ADMIT_ISSUED : POST /admit\nadmitToken TTL 60초
    ADMIT_ISSUED --> COMPLETED : POST /complete\nKafka 발행
    ADMIT_ISSUED --> WAITING : admitToken TTL 60초 초과\nseq 유지 → 우선순위 보존
    WAITING --> CANCELLED : DELETE /token
    WAITING --> EXPIRED : Batch TTL 만료
    COMPLETED --> [*]
    CANCELLED --> [*]
    EXPIRED --> [*]
```

---

## 🔄 전체 흐름 (9단계)

```mermaid
flowchart TD
    A["① Queue 생성"]
    --> B["② 유저 접속\nTenant 슬롯 확인"]
    --> C["③ Enqueue\n순번 확정 → Kafka 발행(동기)\n→ 200 응답. DB INSERT만 비동기"]
    --> D["④ Polling\n유저 → Platform 직접\n/status 전광판 + pacing 구간표"]

    D --> E{"status?"}
    E -- "WAITING" --> D
    E -- "ADMIT_ISSUED\nadmitToken 포함" --> F

    F["⑥ 유저 → Tenant\nadmitToken 전달"]
    --> G["⑦ verify\n유효성 확인만"]
    --> H["⑧ Tenant → 유저 입장 허용"]
    --> I["⑨ complete\nCOMPLETED + ZREM + Kafka"]

    J["Tenant 슬롯 여유"]
    --> K["⑤ admit\nadmitToken TTL 60초"]
    --> D
```

---

## 🗂 Redis Key 구조

```
큐 상태 키: queue-infrastructure/.../queue/QueueKeys.java   ← 해시태그 {queueId} 필수
캐시성 키: queue-infrastructure/.../cache/RedisKeyFactory.java (static 메서드, Enum X)
```

| Key | 자료구조 | TTL | 역할 |
|-----|-----|-----|------|
| `queue:{queueId}:waiting` | ZSet | 없음 | 대기열. member=`identifier`, score=`seq` |
| `queue:{queueId}:seq` | String | 없음 | `INCR`로 score 발급 |
| `queue:{queueId}:tokens` | Hash | 없음 | `identifier` → `tokenId\|issuedAt` (중복 방지 + 소유권 대조) |
| `queue:{queueId}:last-active` | ZSet | 없음 | keepalive. member=`seq`, score=ms |
| `queue-meta:{t}:{q}` | Hash | 없음 | 큐 설정 |
| `queue-stats:{t}:{q}` | Hash | 없음 | avgWaitingTime |
| `token-info:{tokenId}` | String | 폴링 간격+2s | Polling 캐시 (§79 이후 존치 여부 후속 검토) |
| `queue:{queueId}:admit-by-token:{tokenId}` | 60s | Polling 응답용 |
| `queue:{queueId}:admit-by-admit:{admitToken}` | 60s | verify/complete용 |
| `queue:{queueId}:admit-watermark` | 없음 | 마지막 admit seq (`/status` 전광판) |
| `queue:{queueId}:pacing` | 없음 | 폴링 간격 구간표 오버라이드 (없으면 코드 상수) |
| `queue:{queueId}:admit-idem:{requestId}` | 300s | admit 멱등성 (requestId는 Tenant 지정값 → 큐 스코프) |
| `verified-token:{tokenId}` | 60s | 중복 입장 방지 |
| `batch-lock:{t}:{q}` | 15s | Batch 서버 분산 |
| `apikey:{keyHash}` | 60s | API Key 캐시 |

---

## 🗄 MySQL R/W 분리 + 파티셔닝

```
Write → Master / Read → Replica
@Transactional(readOnly) → Replica 자동 라우팅

tokens 테이블:
  Range 파티션 (issued_at 월별)
  월말 배치: queue_daily_stats 집계 → DROP PARTITION
  → 파티션 DROP 후에도 과금 근거 영구 보존

status: TINYINT (0~4) — VARCHAR 대비 저장공간·비교 성능 최적화
redis_sync_needed: Redis 다운 중 미반영 토큰 추적
admit_token: DB 저장 → Redis 미스 시 Fallback
```

---

## 🔴 Redis Sentinel

> 아래는 **현재 구현** 상태다. 목표 구성은 **독립 2 Cluster + 큐 단위 이중 라우팅**으로 확정
> (cluster1 master 50% 초과 시 신규 큐를 cluster2로). 전환 시점은 미정. → [DECISIONS §75](doc/DECISIONS.md)
> Sentinel은 폐기가 아니라 학습·로컬 자산으로 남긴다.

```
Master 1 + Slave 2 + Sentinel 3
쿼럼 = 2 | min-replicas-to-write 1 (Split Brain 방지)
모든 연산 → Master (Lua 원자성)
Slave: Failover + 백업
Failover: 5~10초 | Circuit Breaker → 503
```

---

## 📨 Kafka

| 토픽 | 파티션 키 | 생산 | 소비 |
|------|------|------|------|
| `token-lifecycle` (18 파티션) | **`tokenId`** | Enqueue API (+ Sprint 7 상태 전이) | `queue-consumer`의 `TokenLifecycleConsumer` → tokens INSERT |

> **단일 토픽 + `tokenId` 키**인 이유: 순서 보장은 같은 토픽의 같은 파티션 안에서만 성립하고,
> `queueId`로 잡으면 한 큐 30만 명이 통째로 한 파티션에 몰린다 ([DECISIONS §73](doc/DECISIONS.md) D16·D18).
> admit 요청 전달 수단은 **미판정**(Sprint 7).

---

## 🔧 SDK + API

### REST API (Tenant 서버용)

> Java SDK 제거 — Tenant 서버 언어가 다양해 SDK 커스터마이징이 비현실적.
> REST API 명세 (OpenAPI 3.0) 제공으로 대체.

```
관리 API (JWT 인증):
  POST   /api/v1/tenants/signup          → 회원가입
  POST   /api/v1/tenants/login           → 로그인 (JWT 발급)
  POST   /api/v1/tenants/refresh         → 토큰 갱신
  POST   /api/v1/tenants/me/api-keys     → API Key 발급
  DELETE /api/v1/tenants/me/api-keys/:id → API Key 폐기
  POST   /api/v1/queues                  → 대기열 생성
  GET    /api/v1/queues/:queueId         → 대기열 조회
  PATCH  /api/v1/queues/:queueId         → 대기열 이름 변경
  POST   /api/v1/queues/:queueId/pause   → 대기열 정지
  POST   /api/v1/queues/:queueId/resume  → 대기열 재개
  DELETE /api/v1/queues/:queueId         → 대기열 삭제

Queue Engine API (API Key 인증, Sprint 6~7):
  POST   /api/v1/queues/:queueId/tokens  → Enqueue (200, 순번 확정 후 응답)
  GET    /api/v1/queues/:queueId/status  → Polling ① 전광판 (인증 없음, 전원 동일)
  GET    /api/v1/queues/:queueId/tokens/:tokenId?seq=&ka=  → Polling ② 개인 (tokenId 소유)
  POST   /api/v1/queues/:queueId/admit   → Admit
  POST   /api/v1/queues/:queueId/admit-tokens/:admitToken/verify → Verify
  POST   /api/v1/queues/:queueId/tokens/:tokenId/complete → Complete
```

### JS SDK (브라우저용)

```javascript
const queue = QueueSDK.init({
    baseUrl: 'https://api.queue-platform.com',
    queueId: queueId,  // Tenant 서버에서 받은 값
    tokenId: tokenId,  // Tenant 서버에서 받은 값
    seq: seq           // Tenant 서버에서 받은 값
});

queue.startPolling({
    onWaiting: ({ rank }) => {
        updateUI(rank);
        // rank = seq − lastAdmittedSeq, 간격은 pacing 표로 SDK가 계산 (§79)
    },
    onReady: ({ admitToken }) => {
        sendToTenantServer(admitToken);
    },
    onExpired: () => showExpiredMessage()
});
// 탭 비활성화 → 자동 중단 / 복귀 → 즉시 재개
// 네트워크 offline/online 자동 처리
```

### 클라이언트 전체 흐름

```
유저 → Tenant 서버      : 서비스 접속
Tenant (REST API)       : POST /tokens → 대기토큰 발급
Tenant → 유저           : token, queueId 전달
유저 (JS SDK)           : startPolling() → Platform 직접 Polling
JS SDK → onReady        : admitToken 수신
유저 → Tenant 서버      : admitToken 전달
Tenant (REST API)       : POST /verify → POST /complete
```

---

## ⚡ 성능

| API | p99 | TPS |
|-----|-----|-----|
| Enqueue | < 50ms | 200 rps (급증 → Kafka) |
| Polling | < 50ms | 2,000 rps |
| admit/complete | < 100ms | - |

---

## ⚖️ 트레이드오프

| 선택 | 장점 | 단점 | 근거 |
|------|------|------|------|
| Spring MVC + Virtual Thread | 친숙한 생태계, 코드 단순 | blocking → VT 필요 | spring.threads.virtual.enabled=true 한 줄 적용 |
| JPA + Virtual Thread | @Transactional 자연스러움 | blocking I/O | VT가 OS Thread 고갈 없이 처리 |
| admitToken 만료 → WAITING 복귀 | 우선순위 보존. 유저 불이익 없음 | 슬롯 일시 점유 | seq DB 저장으로 score 복원 |
| Kafka Enqueue 버퍼 | DB 적재를 비동기로 흡수 | Eventually Consistent | At-Least-Once 보장 |
| Kafka admit 처리 | admit 요청 영속성 | Consumer 처리 지연 | DB PENDING → 멱등성 보장 |
| status TINYINT | 저장공간·비교 성능 | 가독성 (상수로 보완) | 대량 tokens 테이블 최적화 |
| redis_sync_needed | Redis 다운 중 INSERT 복구 | 컬럼 추가 | 데이터 정합성 보장 |
| admit_token 컬럼 | Redis 미스 시 DB Fallback | 컬럼 추가 | verify 안정성 향상 |
| queue_daily_stats | 파티션 DROP 후 과금 근거 보존 | 배치 필요 | 감사/청구 불변 기록 |
| billing_snapshots 직접 집계 | tokens 원본 → 중복 방지 불필요 | 집계 쿼리 필요 | billing_events 테이블 제거 |
| avgWaitingTime 직접 갱신 | 별도 통계 Consumer 불필요 → 단순화 | Kafka 재처리 중복 허용 | ETA는 보조 정보 → 허용 범위 |
| 파티션 1달 유예 DROP | 월말 걸친 토큰 과금 누락 방지 | 스토리지 2배 | B2B 과금 정확도 우선 |
| ZCARD Pipeline | queue-count 관리 불필요 | N번 ZCARD | 카운터 불일치 위험 제거 |
| `pacing` 구간표 | 서버 부하 절약 + 장애 시 서버가 전원 간격 조정 | SDK 구현 필요 | 순위 높을수록 Polling 드물게 (§79) |
| Redis R/W 분리 미적용 | 설계 단순 | - | Lua 원자성. In-Memory 충분 |
| MySQL R/W 분리 | SELECT 2,000 rps 분산 | Replica lag | token-info 캐시로 lag 최소화 |
| tokens 파티셔닝 | 월별 DROP 빠른 정리 | PK에 파티션 키 | Partition Pruning 효과 |
| RedisKeyFactory | 컴파일 타임 검사 | - | Enum: 가변인수 타입 안전성 없음 |

---

## 🛠 기술 스택

| 영역 | 기술 | 근거 |
|------|------|------|
| Language | Java 21 | Virtual Thread, Record, LTS |
| API Server | Spring MVC + Tomcat | Virtual Thread로 2,000 rps 달성 |
| ORM | JPA (Hibernate) | @Transactional 자연스러움, 풍부한 생태계 |
| DB 연결 | JDBC + Virtual Thread | blocking → spring.threads.virtual.enabled=true |
| Messaging | Spring Kafka | Enqueue 버퍼 + 상태 이벤트 |
| Batch | Spring MVC + Tomcat | @Scheduled + Spring Kafka Consumer |
| Cache | Redis Sentinel (현재) → 독립 2 Cluster (확정, 시점 미정) | FIFO Sorted Set + Lua 원자 · 큐 단위 이중 라우팅 ([DECISIONS §75](doc/DECISIONS.md)) |
| DB | MySQL 8.0 | Range 파티셔닝 + Replica |
| Architecture | Hexagonal + DDD | 도메인 단위 테스트 |
| Build | Gradle 멀티모듈 6개 | 의존성 명확 분리 |

---

## 📎 문서

| 문서 | 설명 |
|------|------|
| [FRS v1.12](doc/FRS_final.md) | API · Redis · Kafka · SDK · Batch |
| [STATE](doc/STATE.md) | Token · Queue · ApiKey 상태 머신 |
| [FLOW](doc/FLOW.md) | Enqueue · Polling · Admit · Complete · Batch |
| [DECISIONS](doc/DECISIONS.md) | 79개 설계 결정 + 근거 + 면접 포인트 |
| [ROADMAP](doc/ROADMAP.md) | 11개 Sprint DoD + 진행 현황 |
| [CONCURRENCY](doc/CONCURRENCY.md) | 동시성 제어 우선순위 · `@DistributedLock` |

---

## 📊 프로젝트 진행 현황

```
✅ Sprint 1:  멀티모듈 스켈레톤 + Virtual Thread
✅ Sprint 2:  JPA + MySQL Master/Replica R/W 분리
✅ Sprint 3:  관리 도메인 (Tenant + ApiKey + Queue) 헥사고날 구현
✅ Sprint 4:  JWT 인증 + 관리 API 12개 + Service/Controller 테스트
🔄 Sprint 5:  Redis + Lua Script + Sentinel + Rate Limit
🔄 Sprint 6:  Token 도메인 + Queue Engine API  (Enqueue·Polling 구현, Cancel 미구현)
⬜ Sprint 7:  Admit → Verify → Complete
🔄 Sprint 8:  Kafka KRaft 연동  (token-lifecycle 적재 경로 구현)
⬜ Sprint 9:  Batch 모듈
⬜ Sprint 10: 통합 테스트 + k6 + Grafana + JS SDK + OpenAPI
⬜ Sprint 11: Docker + AWS 배포 + 대용량 실측
```

> 일정·DoD의 정본은 [ROADMAP](doc/ROADMAP.md)이다.
> ⚠️ 다만 ROADMAP은 2026-06-10 최신화라 **위 블록보다 낡았다**(Sprint 6·8을 ⬜로 두고 폐기된
> 3토픽 체계를 현재형으로 서술 중). 갱신 전까지는 위 "코드로 확인되는 상태"가 더 정확하다.

---

<p align="center">
  <sub>Queue Platform · Java 21 · Spring Boot 3.3.4 · Redis · Kafka · MySQL 8.0</sub>
</p>
