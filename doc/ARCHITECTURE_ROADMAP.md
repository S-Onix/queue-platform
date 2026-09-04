# Queue Platform - Architecture Roadmap

> **문서 목적**: Queue Platform의 전체 아키텍처 진화 로드맵.
> Sprint별 성장 단계, 각 Phase의 상세 설계, 실제 구현 계획을 포함한다.
>
> **작성 시점**: Sprint 5-D 완료 후, Sprint 5-E 시작 전
> **최종 목표**: Line Pay Plus 시니어 백엔드 지원 자산 + 실무 완결성

---

> ## 🔴 이 문서를 읽기 전에 (2026-08-26 추가)
>
> **이 문서의 Sprint 번호는 옛 계획이고, 정본은 [`ROADMAP.md`](ROADMAP.md)다.** 실제 진행은
> 계획대로 가지 않았다 — 특히 **Kafka·Cluster가 여기 적힌 것보다 훨씬 먼저 들어왔다.**
>
> | 이 문서 | 실제 (ROADMAP.md 기준) |
> |---|---|
> | Sprint 6 = ApiKey 인증 | Token 도메인 + Enqueue + Polling |
> | Sprint 7 = WAS 확장 + DLock | admit · verify · complete (§80) |
> | Sprint 10 = Cluster 도입 | **이미 구현 완료** (§75, 독립 2 Cluster) |
> | Sprint 11-12 = Kafka 도입 | **Sprint 8에 완료** (`token-lifecycle`, 100만건 실측) |
> | Phase 3 = Kafka + SSE | Kafka는 끝났고 **SSE는 착수도 안 했다**(폴링 유지, §79) |
>
> **그래서 이 문서에서 지금도 유효한 것은 "무엇을 왜 하는가"이지 "언제 하는가"가 아니다.**
> 부록 C·D·E(Cluster 명령어·설정·실습)와 G·H·I(사이징 계산)는 **참조 자료라 그대로 유효**하다.
> 아래 §2 Phase 0은 현재 상태로 갱신했다.

---

## 목차

1. [전체 진화 개괄](#1-전체-진화-개괄)
2. [Phase 0 - 현재 상태](#2-phase-0---현재-상태)
3. [Phase 1 - Sprint 5-E ~ 7](#3-phase-1---sprint-5-e--7)
4. [Phase 2 - Sprint 8-10 (Cluster + Caffeine)](#4-phase-2---sprint-8-10-cluster--caffeine)
5. [Phase 3 - Sprint 11-15 (Kafka + SSE)](#5-phase-3---sprint-11-15-kafka--sse)
6. [Phase 4 - Sprint 15+ (Multi-Region + Hybrid)](#6-phase-4---sprint-15-multi-region--hybrid)
7. [기술 결정 근거](#7-기술-결정-근거)
8. [학습 자산 축적](#8-학습-자산-축적)
9. [Sprint 계획 요약표](#9-sprint-계획-요약표)
10. [결정 사항 요약](#10-결정-사항-요약)
11. [참고 자료](#11-참고-자료)

### 부록

- [부록 A: Enqueue vs Polling 부하 특성 분리](#부록-a-enqueue-vs-polling-부하-특성-분리)
- [부록 B: 애플리케이션 라우팅 vs Cluster 대안 분석](#부록-b-애플리케이션-라우팅-vs-cluster-대안-분석)
- [부록 C: Redis Cluster 실전 명령어 가이드](#부록-c-redis-cluster-실전-명령어-가이드)
- [부록 D: Redis Cluster 설정 파일 상세](#부록-d-redis-cluster-설정-파일-상세)
- [부록 E: Cluster 학습 실습 절차](#부록-e-cluster-학습-실습-절차)
- [부록 F: 이중 라우팅 — Layer 1만 채택, Layer 2는 기각](#부록-f-이중-라우팅-아키텍처--layer-1만-채택-layer-2는-기각)
- [부록 G: 1억 대기 처리 인프라 실측 계산](#부록-g-1억-대기-처리-인프라-실측-계산)
- [부록 H: Master 크기 최적화 원리 (Single Thread 병목 해결)](#부록-h-master-크기-최적화-원리)
- [부록 I: 극대 분산 아키텍처 (4x4x4GB 최종 설계)](#부록-i-극대-분산-아키텍처)

---

## 1. 전체 진화 개괄

### 1.1 성장 예측

| Phase | Sprint | Tenant | Queue | 총 대기 | 총 메모리 |
|-------|--------|--------|-------|---------|-----------|
| Phase 0 | 완료 | 1-5 | 1-10 | 1만-10만 | <1GB |
| Phase 1 | 5-E~7 | 5-30 | 10-50 | 10만-100만 | 1-5GB |
| Phase 2 | 8-10 | 30-100 | 50-200 | 100만-1000만 | 5-30GB |
| Phase 3 | 11-15 | 100-500 | 200-1000 | 1000만-1억 | 30-200GB |
| Phase 4 | 15+ | 500+ | 1000+ | 1억+ | 200GB+ |

### 1.2 Phase별 핵심 기술 도입

```
Phase 0: Sentinel + 학습 완료
   ↓
Phase 1: WAS 확장 + Enqueue Bulk 단독 (§70)
   ↓
Phase 2: Redis Cluster + Caffeine + Rank Query
   ↓
Phase 3: Kafka + SSE + Backpressure
   ↓
Phase 4: Multi-Region + Hybrid Redis
```

### 1.3 설계 원칙

1. **단계적 진화** - 실제 필요에 따라 확장, 과도한 초기 투자 회피
2. **실측 기반 결정** - 부하 테스트 결과에 따른 튜닝
3. **학습 완결성** - 각 기술 정확 이해 후 도입
4. **도메인 우선** - Queue Platform의 존재 이유 (부하 완충) 실현
5. **Anti-pattern 회피** - 관성적 결정 회피, 실무 관행 검증

---

## 2. Phase 0 - 현재 상태

> ✏️ **이 절은 원래 Sprint 5-D 시점에 얼어 있었다**(앱 1대 · "MySQL Sentinel" · Redis Sentinel ·
> Kafka 없음). 2026-08-26에 코드와 대조해 갱신했다.

### 2.1 아키텍처 다이어그램

```
Client (브라우저)
  │  GET /status · GET /tokens/{tokenId}   ← 인증 없음. 유저가 Platform을 직접 폴링
  ↓
[queue-api  N대]  ←── Tenant 서버 (X-API-Key: enqueue · admit · verify · complete)
  ├─→ MySQL 8.0 (GTID 복제)
  │   ├─ Master  (3306)   쓰기 · readOnly 아닌 트랜잭션
  │   └─ Replica (3307)   @Transactional(readOnly = true) 라우팅
  │
  ├─→ Redis Cluster A (7001-7008)  ┐ 큐 단위로 둘 중 하나에 배정 (§75)
  ├─→ Redis Cluster B (8001-8008)  ┘ 한 큐의 키 4종은 같은 클러스터 (해시태그는 경계를 못 넘는다)
  │
  └─→ Kafka KRaft (token-lifecycle, key = tokenId)
        ↓
      [queue-consumer  N대]  → tokens 배치 적재 (At-Least-Once, 가드 UPSERT)

[queue-batch  N대]  잡 3개 — TokenReclaimJob(10초) · ReconcileJob(5분) · BillingSnapshotJob(매일)
                    ShedLock 없음. 근거는 CONCURRENCY.md 적용 매트릭스

[모니터링] Prometheus + Grafana        [학습 자산] Redis Sentinel (앱은 안 붙는다)
```

### 2.2 완료된 것

- **Sprint 1-4**: 도메인 설계, 헥사고날, JWT + Refresh Rotation
- **Sprint 5**: Rate Limiter(2종) · Redis 캐시 · Enqueue Bulk Lua + Hash Tag
- **Sprint 6**: Token 도메인 · Enqueue · Polling (Cancel API는 §82로 폐기)
- **Sprint 7**: admit · verify · complete + `/status` 분할 · admitWatermark · pacing (§79 · §80)
- **Sprint 8**: Kafka `token-lifecycle` + `queue-consumer` (100만건 실측)
- **Sprint 9(진행 중)**: 회수 3경로 · Redis↔DB 대사 · 과금 스냅샷(§84). `RedisSyncJob`은 폐기(2026-08-27)
- **인프라 전환**: Sentinel → **독립 2 Cluster + 큐 단위 라우팅** (§75, 코드에서 Sentinel 분기 제거)
- **배포**: Dockerfile 3종 + compose + GitHub Actions CI(단위 · 통합 · 이미지 3종)

### 2.3 도구 스택

| Layer | Technology |
|-------|-----------|
| Language | Java 21 (Virtual Thread) |
| Framework | Spring Boot 3.3.4 (MVC — **WebFlux 아님**) |
| WAS | Apache Tomcat (`spring.threads.virtual.enabled=true`) |
| Persistence | JPA + MySQL 8.0 Master/Replica (GTID 복제 — *"MySQL Sentinel"이라는 것은 없다*) |
| Cache · Queue 상태 | **Redis 독립 2 Cluster** (Sentinel 폐기, §75) |
| Messaging | Kafka KRaft (단일 토픽 `token-lifecycle`, key = `tokenId`) |
| Build | Gradle Multi-Module (6개) |
| Architecture | Hexagonal (Ports & Adapters) |

### 2.4 학습 자산

- 통찰 54개 축적
- Cache Aside, Negative Caching
- Mixin 패턴, Registrar 패턴
- Anti-pattern 인식 (Facade 도입 → 롤백)

---

## 3. Phase 1 - Sprint 5-E ~ 7

### 3.1 아키텍처 다이어그램

```
Client
  ↓
[Nginx / Load Balancer]
  ↓
[Spring Boot 2-3대]
  ├─→ MySQL Master/Replica (동일)   ※ 구 서술 "MySQL Sentinel"은 잘못된 표현 — GTID 복제다
  │
  └─→ Redis Sentinel (동일)
      + Bulk 단독 Enqueue Engine (Global Queue + BatchProcessor)

[모니터링]
Prometheus + Grafana
```

### 3.2 Sprint 5-E: Enqueue Bulk 단독 (하이브리드 폐기 — §70)

**목표**: Queue Platform의 도메인 목적 (부하 완충) 구현

**핵심 결정** (2026-07-15 개정 — §70):
- ~~일반 Lua Script + Bulk Lua Script 하이브리드~~ → **Bulk Lua 단독**
- ~~부하 기반 자동 전환 (Adaptive Batching)~~ → 폐기 (임계값 분기 없음)
- ~~Enqueue 전용 SlidingWindowCounter~~ → 폐기 (전환할 경로가 없으니 부하 측정 불필요)
- Hash Tag 필수 (2-key Lua)

**구현 요소**:

```
queue-domain/queue/
├── QueueEngine.java (Port)
└── EnqueueResult.java (Value Object)

queue-infrastructure/queue/
├── RedisQueueEngine.java (Adapter - Global Queue Producer)
├── BatchProcessor.java (@Scheduled Consumer - Bulk Lua 실행)
└── QueueKeys.java (Hash Tag 키 관리)
queue-domain/queue/
└── PendingEnqueue.java (배치 항목 + CompletableFuture)

queue-infrastructure/src/main/resources/lua/
└── enqueue_bulk.lua (단독 — enqueue.lua는 폐기, §70)

queue-api/queue/
├── QueueController.java (수정)
└── QueueService.java (수정)
```

**Bulk 단독 정책** (2026-07-15 개정 — §70. 개정 전엔 임계값 1000 req/s 기준 하이브리드였다):

```
모든 요청 (부하 무관):
  Client → QueueEngineService → RedisQueueEngine.enqueue()
  → PendingEnqueue 생성 → globalQueue.offer()
  → future.get(30s) 블로킹 대기
        ↕
  BatchProcessor @Scheduled drain-interval=20ms
  → drain(최대 5000) → queueId별 groupBy → 500씩 청크
  → enqueue_bulk.lua 실행 (Hash Tag 2-key)
  → 위치(index)로 매칭하여 future.complete()
  → CompletableFuture.complete() → 응답 (10-20ms)
```

**임계값 결정**:
- 부하 임계값: 초당 1000 요청
- 배치 크기: 100
- 배치 간격: 10ms
- 타임아웃: 1초

### 3.3 Sprint 6: ApiKey 인증

**목표**: Tenant Server용 ApiKey 인증 도입

**구현 요소**:
- ApiKeyAuthenticationFilter (Sprint 5-D 캐시 활용)
- Tenant 소유권 검증
- Rate Limit per ApiKey

### 3.4 Sprint 7: WAS 수평 확장 + Distributed Lock

**목표**: 다중 서버 환경 대응

**구현 요소**:
- @DistributedLock AOP (queue-common)
- DistributedLockAspect (queue-infrastructure)
- Redis 기반 분산 락
- 로드 밸런서 도입 (Nginx)

**핵심 학습**:
- 다중 WAS에서 Race Condition 방지
- @Transactional 밖에서 락 획득
- 락 획득 실패 시 재시도 전략

### 3.5 Phase 1 완료 시점

- Tenant 5-30개 대응
- Queue 10-50개
- 총 메모리 1-5GB
- 통찰 65-70개

---

## 4. Phase 2 - Sprint 8-10 (Cluster + Caffeine)

### 4.1 아키텍처 다이어그램

```
Client
  ↓
[CDN / Load Balancer]
  ↓
[Spring Boot 3-5대]
  ├─→ MySQL Master/Replica (Read Replica 활용)   ※ "MySQL Sentinel"은 잘못된 표현 — GTID 복제다
  │
  ├─→ Redis Cluster (신규) ⭐
  │   ├─ Master 1 (Slot 0-5461)
  │   ├─ Master 2 (Slot 5462-10922)
  │   ├─ Master 3 (Slot 10923-16383)
  │   ├─ Replica 1 (Master 1의 복제)
  │   ├─ Replica 2 (Master 2의 복제)
  │   └─ Replica 3 (Master 3의 복제)
  │
  └─→ Caffeine Cache (JVM 내부) ⭐
      ├─ Tenant Cache (기존)
      ├─ ApiKey Cache (기존)
      └─ Rank Cache (신규 - Polling 대응)

[모니터링]
Prometheus + Grafana (Cluster 대시보드 추가)
```

### 4.2 Sprint 8: Redis Cluster 학습

**목표**: Cluster 개념 이해 + 로컬 실습

**학습 순서**:

**Step 1**: 개념 이해
- Standalone vs Sentinel vs Cluster
- Hash Slot (16,384개)
- CRC16 알고리즘
- Master-Replica 관계
- Gossip Protocol

**Step 2**: 로컬 6 노드 구성 (WSL 네이티브)

```bash
# 폴더 생성
mkdir -p ~/redis-cluster/{7001,7002,7003,7004,7005,7006}

# 각 노드 설정 파일 생성 (7001 예시)
cat > ~/redis-cluster/7001/redis.conf << EOF
port 7001
cluster-enabled yes
cluster-config-file nodes-7001.conf
cluster-node-timeout 5000
appendonly yes
dir /home/sonix/redis-cluster/7001
EOF

# systemd 서비스 등록 (본인 관행)
# 6개 노드 실행

# Cluster 초기화
redis-cli --cluster create \
  127.0.0.1:7001 127.0.0.1:7002 127.0.0.1:7003 \
  127.0.0.1:7004 127.0.0.1:7005 127.0.0.1:7006 \
  --cluster-replicas 1
```

**Step 3**: Cluster 명령 학습

```bash
# 상태 확인
redis-cli -c -p 7001 cluster info
redis-cli -c -p 7001 cluster nodes
redis-cli -c -p 7001 cluster slots

# Key의 slot 확인
redis-cli -c -p 7001 cluster keyslot "queue:q_bts_001:waiting"

# Cluster 상태 종합 체크
redis-cli --cluster check 127.0.0.1:7001
```

**Step 4**: Lua Script Cluster 테스트 (2026-07-15 개정 — §70)
- ~~enqueue.lua는 단일 key (KEYS[1])만 사용 → CROSSSLOT 에러 없음~~
- `enqueue_bulk.lua`는 **2-key** (KEYS[1]=waiting, KEYS[2]=seq)
  → **Hash Tag 없으면 CROSSSLOT** (slot 7911 vs 11273)
  → `queue/QueueKeys.java`에서 `queue:{queueId}:...`로 감싸 동일 slot 보장
- 로컬 Cluster A(7001)에서 실제 스크립트 실행 검증 완료

**Step 5**: Failover 테스트
- Master 강제 종료
- Replica 자동 승격 (5-15초)
- Cluster 상태 자동 회복

### 4.3 Sprint 9: Rank Query + Caffeine

**목표**: Polling 대응 (Redis 부담 감소)

**핵심 원리**:

```
[Polling 부담 분석]
사용자 100만명 × 5초 폴링 = 초당 200,000 요청
Admit 발생 시에만 rank 변화 (초당 100번 예상)
낭비율: 99.75%

[Caffeine 도입 효과]
1-2초 TTL 캐싱
캐시 히트율: 80-95%
Redis 요청: 200,000 → 10,000-40,000 (5-20배 감소)
```

**구현 요소**:

```
queue-domain/queue/
└── RankQueryEngine.java (Port)

queue-infrastructure/queue/
├── RedisRankQueryEngine.java (Adapter)
└── CaffeineRankCache.java

queue-api/queue/
├── QueueController.java (수정 - rank endpoint)
└── QueueService.java (수정 - getRank)

queue-api/queue/dto/
└── RankResponse.java
```

**Caffeine 설정**:

```java
Cache<String, RankResponse> rankCache = Caffeine.newBuilder()
    .maximumSize(1_000_000)              // Queue별 100만 대기 대응
    .expireAfterWrite(1, TimeUnit.SECONDS)  // 1초 TTL
    .recordStats()                        // 통계 기록
    .build();
```

**API 흐름**:

```
GET /api/v1/queues/{queueId}/rank/{identifier}
  ↓
QueueController.getRank()
  ↓
QueueService.getRank()
  ↓
CaffeineRankCache 조회
  ├─ HIT: 즉시 반환 (0.0001ms)
  └─ MISS: 
      ├─→ RankQueryEngine.getRank() (Redis 조회)
      ├─→ Caffeine에 저장
      └─→ 반환 (1-2ms)
```

### 4.4 Sprint 10: Production Cluster 도입

> ⚠️ **개정 (§75, 2026-08-11)**: Cluster 전환은 **확정**, **시점은 미정**(아래 "Sprint 10"은 확정 아님).
> 목표 형태도 아래의 **단일 Cluster 3 Master + 3 Replica**가 아니라
> **독립 2 Cluster + 큐 단위 이중 라우팅**(cluster1 master 50% 초과 시 신규 큐를 cluster2로)이다.
> 아래 구성·마이그레이션 계획은 **2026-07-08 시점의 검토안**으로 보존한다. → DECISIONS §75

**목표**: Sentinel → Cluster 마이그레이션

**Cluster 구성** (Tenant 100개 대응):
- 3 Master + 3 Replica
- 각 Node 8-16GB
- 총 저장: 24-48GB
- Multi-AZ 배치

**마이그레이션 방식**: Fresh Start (권장)

```
[대기열은 휘발성 (TTL 7200초)]
1. Cluster 신규 구축 (Stage 검증 완료)
2. 저트래픽 시간 선택 (새벽 4시)
3. 애플리케이션 재배포 (Cluster 접속)
4. 기존 대기 사용자 재접속 (자동)
5. 검증 및 완료

[장점]
- 간단
- 안전
- 무중단 불필요 (대기열 특성상)
```

**마이그레이션 계획서**:

```
[T-1주] Stage 환경 검증
- 6 노드 Cluster 배포
- 부하 테스트
- Failover 시나리오

[T-1일] Production 준비
- 6 노드 Cluster 배포
- application.yml 변경 준비
- 롤백 계획 확정

[T-0] 실제 전환 (새벽 4시)
- 애플리케이션 재배포
- Cluster 접속
- 모니터링 강화

[T+1일] 안정화
- 성능 지표 검증
- ~~Sentinel 폐기~~ → §75 D28: Sentinel은 **학습·로컬 자산으로 격하(존치)**. 폐기 여부 미정
- 문서화
```

### 4.5 Phase 2 완료 시점

- Tenant 30-100개 대응
- Queue 50-200개
- 총 메모리 5-30GB
- 통찰 75-80개

---

## 5. Phase 3 - Sprint 11-15 (Kafka + SSE)

### 5.1 아키텍처 다이어그램

```
Client
  ↓
[CDN + WAF]
  ↓
[Load Balancer]
  ↓
[Spring Boot 5-10대]
  │
  ├─→ MySQL Master-Replica
  │
  ├─→ Redis Cluster (5-7 Master)
  │   각 Master 16-32GB
  │   총 80-224GB
  │
  ├─→ Kafka Cluster (신규) ⭐
  │   ├─ Broker 1-3
  │   ├─ Topic: enqueue-requests
  │   ├─ Topic: admit-events
  │   └─ Topic: rank-updates
  │
  ├─→ Caffeine Cache (기존)
  │
  └─→ SSE Backend (신규) ⭐
      ├─ SseEmitter Registry
      └─ Backpressure API

[모니터링]
Prometheus + Grafana (Kafka + SSE 대시보드)
```

### 5.2 Sprint 11-12: Kafka 도입

**목표**: Enqueue 비동기 처리로 요청 폭증 흡수

**핵심 원리**:

```
[동기 처리 (현재)]
Client → Server → Redis Lua Script
- 모든 요청이 Redis에 즉시 도달
- Redis 부담

[비동기 처리 (Kafka 도입)]
Client → Server → Kafka Producer → 응답 (즉시)
                     ↓
              Kafka Consumer → Redis Lua Script
- Kafka가 부담 흡수
- 처리량 대폭 증가
- 지연 미미 (10-50ms)
```

**구현 요소**:

```
queue-infrastructure/kafka/
├── KafkaEnqueueProducer.java
├── KafkaEnqueueConsumer.java
└── KafkaAdmitPublisher.java

queue-api/queue/
└── QueueService.java (수정 - 비동기 Enqueue)
```

**Kafka 로컬 구성 (KRaft mode)**:

```yaml
# docker-compose-kafka.yml
version: '3'
services:
  kafka:
    image: apache/kafka:latest
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: "broker,controller"
      KAFKA_LISTENERS: "PLAINTEXT://kafka:9092,CONTROLLER://kafka:9093"
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka:9093"
      # ... 기타 설정
```

**흐름 재설계**:

```
[Enqueue 흐름 (Kafka 도입 후)]

Step 1: Client → Server
POST /api/v1/queues/{queueId}/tokens
Body: { "identifier": "user_a" }

Step 2: Server → Kafka
KafkaEnqueueProducer.send("enqueue-requests", event)
{
  "queueId": "q_bts_001",
  "identifier": "user_a",
  "timestamp": 1720000000000
}

Step 3: Server → Client (즉시)
{
  "status": "ACCEPTED",
  "requestId": "req_xxx",
  "message": "대기열 진입 요청 접수됨"
}

Step 4: Kafka Consumer 처리
KafkaEnqueueConsumer.consume(event)
→ RedisQueueEngine.enqueue()
→ Lua Script 실행

Step 5: 결과 전달 (SSE Push - Sprint 13-14)
→ Client에 rank 통지
```

### 5.3 Sprint 13-14: SSE + Backpressure

**목표**: Polling 완전 대체, Push 기반 rank 업데이트

**아키텍처**:

```
[SSE + Backpressure 흐름]

[Client Side]
1. Enqueue 요청 (Kafka로 접수)
2. SSE 연결 (rank 이벤트 구독)
3. Rank 업데이트 수신 (실시간)

[Tenant Server Side]
1. Backpressure 신호 (초당 처리 능력)
2. 사용자 처리 완료 통지
3. 다음 Admit 트리거

[Queue Platform Side]
1. Enqueue 처리 (Kafka Consumer)
2. Admit 관리 (Backpressure 반영)
3. Rank 재계산 후 SSE Push
```

**구현 요소**:

```
queue-api/queue/
├── QueueSubscriptionController.java (SSE)
├── BackpressureController.java (Tenant 신호)
└── AdmitController.java (Tenant 완료 통지)

queue-infrastructure/subscription/
├── SseEmitterRegistry.java (구독자 관리)
└── SseEmitterPublisher.java (Push)

queue-infrastructure/admit/
├── AdmitEngine.java (Backpressure 반영)
└── RankRecalculator.java

queue-common/annotation/
├── @SseConnection
└── @Backpressure
```

**Tomcat + Virtual Thread + SSE 조합**:

```java
@GetMapping(value = "/api/v1/queues/{queueId}/subscribe/{identifier}",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter subscribe(@PathVariable String queueId,
                            @PathVariable String identifier) {
    // 30분 타임아웃 (자동 재연결로 대응)
    SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
    
    subscriptionRegistry.register(queueId, identifier, emitter);
    
    emitter.onCompletion(() -> subscriptionRegistry.unregister(queueId, identifier));
    emitter.onTimeout(() -> subscriptionRegistry.unregister(queueId, identifier));
    emitter.onError(e -> subscriptionRegistry.unregister(queueId, identifier));
    
    return emitter;
}
```

**Backpressure API**:

```
POST /api/v1/queues/{queueId}/backpressure
Body: { "capacity": 100 }
→ Tenant Server가 초당 처리 능력 신호

POST /api/v1/queues/{queueId}/admit/complete
Body: { "identifier": "user_a" }
→ Tenant Server가 처리 완료 통지
→ Queue Platform이 다음 사용자 Admit
```

**SDK 재설계**:

```
[Client SDK (JavaScript)]
// ⚠️ 아래 enqueue()는 이 검토안(2026-07-08) 시점의 형태다. **채택하지 않는다.**
//    DECISIONS §78: JS SDK 범위는 폴링 + 대기 UI 전용이고, enqueue는 SDK에 넣지 않는다.
//    enqueue는 "줄 설 자격이 있나"를 판정하는 지점이라 Tenant 서버가 X-API-Key로 호출한다.
//    SSE로 재설계하더라도 이 경계는 그대로다 — 바뀌는 건 "순번을 어떻게 받나"뿐이다.
class QueueClientSDK {
    async enqueue(queueId, identifier) {     // ← §78에서 기각. 구독 시작만 남는다
        // 1. Enqueue 요청 (Kafka)
        const response = await fetch('/enqueue', ...);
        
        // 2. SSE 연결 시작
        this.eventSource = new EventSource(
            `/queues/${queueId}/subscribe/${identifier}`
        );
        
        // 3. Rank 이벤트 구독
        this.eventSource.addEventListener('rank-update', (e) => {
            const data = JSON.parse(e.data);
            this.onRankUpdate(data.rank);
        });
        
        // 4. Admit 이벤트 구독
        this.eventSource.addEventListener('admit', (e) => {
            this.onAdmit();
        });
    }
}

[Tenant Server — SDK 없음. REST API 직접 호출]
// ⚠️ 아래는 SSE 재설계 시의 호출 형태를 보이기 위한 의사코드다.
//    특정 언어의 SDK를 제공한다는 뜻이 아니다 — Tenant 서버용 SDK는 만들지 않는다.
//    이유: DECISIONS §35
{
    // 초기 capacity 설정
    async setCapacity(queueId, capacity) {
        await fetch(`/queues/${queueId}/backpressure`, {
            method: 'POST',
            body: JSON.stringify({ capacity })
        });
    }
    
    // 사용자 처리 완료 통지
    async completeAdmit(queueId, identifier) {
        await fetch(`/queues/${queueId}/admit/complete`, {
            method: 'POST',
            body: JSON.stringify({ identifier })
        });
    }
}
```

### 5.4 Sprint 15: 통합 최적화

**목표**: 전체 시스템 튜닝

**작업 항목**:
- 부하 테스트 (실제 시나리오)
- Redis Cluster 확장 (5-7 Master)
- Kafka 파티션 조정
- Caffeine 튜닝
- SSE 연결 관리 최적화
- Grafana 대시보드 확장

### 5.5 Phase 3 완료 시점

- Tenant 100-500개 대응
- Queue 200-1000개
- 총 메모리 30-200GB
- 통찰 85-95개

---

## 6. Phase 4 - Sprint 15+ (Multi-Region + Hybrid)

### 6.1 아키텍처 다이어그램

```
Global Client
  ↓
[Multi-Region CDN]
  ↓
[Global Load Balancer (DNS 기반)]
  ↓
[Multi-Region Deployment]

Region A (Asia)      Region B (US)       Region C (EU)
   ↓                    ↓                    ↓
[Load Balancer]      [Load Balancer]      [Load Balancer]
   ↓                    ↓                    ↓
[Spring Boot 10대]   [Spring Boot 10대]   [Spring Boot 10대]
   ├─→ MySQL         ├─→ MySQL           ├─→ MySQL
   ├─→ Redis Cluster ├─→ Redis Cluster   ├─→ Redis Cluster
   ├─→ Kafka         ├─→ Kafka           ├─→ Kafka
   └─→ SSE Backend   └─→ SSE Backend     └─→ SSE Backend

[Hybrid Redis (Tier)]
- Tier 1 (일반): 공유 Cluster
- Tier 2 (대형): Dedicated Cluster
- Tier 3 (VIP): 완전 격리 Instance
```

### 6.2 Multi-Region 전략

**Region 결정**:
- 사용자 지리적 분포에 따라
- 각 Region 독립 운영
- Region 간 동기화 (필요 시)

**Cross-Region Failover**:
- Region 장애 감지
- DNS 라우팅 변경
- 다른 Region으로 자동 전환

### 6.3 Hybrid Redis

**Tier 분리**:

```
[Tier 1 - 일반 Tenant (80%)]
- 공유 Redis Cluster
- 표준 SLA
- 비용 효율

[Tier 2 - 대형 Tenant (18%)]
- Dedicated Redis Cluster
- 우선 SLA
- 성능 보장

[Tier 3 - VIP Tenant (2%)]
- 완전 격리 Redis Instance
- 최고 SLA
- 규정 준수 (금융, 공공)
```

### 6.4 Phase 4 완료 시점

- Tenant 500+ 대응
- Queue 1000+
- 총 메모리 200GB+
- 통찰 100+개

---

## 7. 기술 결정 근거

### 7.1 왜 Tomcat + Virtual Thread (WebFlux 미도입)?

**결정**: SSE 도입 시에도 Tomcat + Virtual Thread 유지

**근거**:
1. **Java 21 Virtual Thread**
   - 수십만 동시 연결 지원
   - Blocking 코드 유지 가능
   - JPA/JDBC 자연스러움

2. **학습 부담 최소**
   - Reactor 학습 불필요
   - 기존 코드 재작성 X
   - Spring MVC 표준 유지

3. **실무 관행**
   - SseEmitter로 SSE 완벽 구현
   - 대부분의 실무 시나리오 커버

**참고**: 통찰 66번 - "Tomcat + Virtual Thread로 SSE 가능"

### 7.2 왜 Redis Cluster (Sentinel 유지 아님)?

**결정**: Sprint 10에 Cluster 도입

**근거**:
1. **수평 확장 필요**
   - Sentinel: Master 1대 메모리 한계
   - Cluster: N개 Node로 자연스러운 확장

2. **자동 샤딩**
   - CRC16으로 Slot 매핑
   - Lettuce 자동 라우팅
   - 개발자 부담 X

3. **본인 프로젝트 적합**
   - Lua Script는 2-key지만 **Hash Tag로 동일 slot 보장** → CROSSSLOT 문제 없음 (§70)
   - Multi-tenant 자연스러운 분산

**참고**: 통찰 70-71번

### 7.3 왜 Kafka (즉시 Redis 처리 아님)?

**결정**: Sprint 11에 Kafka 도입

**근거**:
1. **요청 폭증 흡수**
   - 티켓팅 오픈 순간 폭증
   - Redis 즉시 처리 한계
   - Kafka가 흡수

2. **비동기 처리**
   - Client 응답 즉시
   - 실제 처리 백그라운드
   - 처리량 대폭 증가

3. **이벤트 소싱 준비**
   - Kafka Topic으로 이벤트 저장
   - 재처리 가능
   - 감사 로그

### 7.4 왜 SSE (WebSocket 아님)?

**결정**: Sprint 13에 SSE 도입

**근거**:
1. **단방향 통신 충분**
   - 서버 → 클라이언트만
   - Rank 업데이트
   - Admit 통지

2. **인프라 간단**
   - HTTP 기반
   - 방화벽 통과
   - 브라우저 표준 (EventSource)

3. **자동 재연결**
   - 브라우저 기본 기능
   - 개발자 부담 없음

4. **양방향 불필요**
   - Client → Server: 초기 Enqueue만 (HTTP)
   - 그 후 Push만 필요

**참고**: 통찰 65번 - "SSE는 SDK 계약을 근본적으로 바꾼다"

### 7.5 왜 Caffeine (Redis Read Replica 아님)?

**결정**: Sprint 9에 Caffeine 도입 (Polling 대응)

**근거**:
1. **초고속**
   - Redis: 1-2ms (네트워크)
   - Caffeine: 100-500ns (in-memory)
   - 10,000배 차이

2. **Polling 특성 적합**
   - 5초 폴링 → 1초 TTL 캐싱 허용
   - 정확도 미세 저하 (허용 범위)
   - 부담 대폭 감소

3. **Redis 부담 감소**
   - Polling 요청 흡수
   - Enqueue 여유 확보

**참고**: 통찰 63번 - "부하 특성별 최적 도구 선택"

### 7.6 왜 하이브리드 Enqueue를 **폐기**했나? (2026-07-15 개정 — §70)

> ⚠️ 이 절은 원래 "왜 하이브리드 Enqueue인가"였다. 구현하며 판단이 뒤집혔고,
> **뒤집힌 이유 자체가 학습 자산**이므로 원래 근거와 반박을 함께 남긴다.

**최초 결정**: Sprint 5-E에 하이브리드 도입 (임계값 1000 req/s로 일반 Lua ↔ Bulk Lua 자동 전환)

**최초 근거와 그에 대한 반박**:

| 최초 근거 | 구현 후 반박 |
|---|---|
| 도메인 목적 완결 — 단일 Lua로는 부하 완충 미스매치 | **Bulk 단독도 부하 완충이다.** 완충은 배치가 하는 일이지 "경로가 2개"라서 되는 게 아님 |
| 부하 편차 대응 — 평상시 즉시 응답 | 배치 주기를 짧게(10ms) 두면 저부하에서도 즉시에 가까움. **경로를 나눌 필요가 없음** |
| 실무 관행 (Adaptive Batching) | Netflix/Uber의 Adaptive Batching은 **배치 크기·주기**를 조절하는 것이지 **경로를 분기**하는 게 아님. 관행을 잘못 읽었음 |

**폐기의 결정적 이유 — 검증 비용**:
- 경로가 2개면 **"두 경로가 같은 순번 체계를 공유한다"를 따로 증명**해야 한다
- 일반 경로는 `ZADD score=timestamp`, Bulk 경로는 `INCR seq` → **score 체계가 달라 순번이 뒤섞일 위험**
- 경로가 하나면 이 증명이 통째로 사라진다. 실제로 5-E 검증은 "1,000 동시 → 순번 0~999 유일" 한 번으로 끝났다

**남은 대가 (정직하게)**:
- ~~저부하 요청도 배치 주기를 전액 부담 (`fixedRate=1000ms` → 평균 500ms)~~
- 하이브리드의 단건 경로는 이 비용을 회피하는 장치였음 → **폐기로 잃은 것이 실재함**
- ✅ **재조정 완료 (2026-08-27): 20ms.** 평균 10ms로 잃은 것이 사실상 사라졌다

**학습 포인트**: "실무가 X를 한다"는 근거는 **X가 정확히 무엇인지** 확인하고 써야 한다.
Adaptive Batching을 "경로 분기"로 오독한 것이 최초 설계의 뿌리 오류였다.

---

## 8. 학습 자산 축적

### 8.1 통찰 축적 예측

| Sprint | 통찰 수 | 주요 학습 |
|--------|---------|----------|
| 5-D 완료 | 54개 | 캐시 인프라, Anti-pattern 인식 |
| Cluster 학습 세션 (2026-07-08) | **88개** ⭐ | Cluster 완전 이해, 이중 라우팅, 극대 분산 설계 |
| 5-E 완료 | 95-100개 | Lua Script 심화, Bulk 처리, **Hash Tag/CROSSSLOT**, 하이브리드 폐기 판단 |
| 6-7 완료 (예상) | 105-110개 | ApiKey 인증, Distributed Lock |
| 8-10 완료 (예상) | 115-120개 | Redis Cluster 프로덕션 도입 |
| 11-14 완료 (예상) | 130-135개 | Kafka, Polling+Jitter, 성장 대응 |
| 15+ (예상) | 145+개 | Multi-Region, Hybrid, 4x4x4GB 극대 분산 |

### 8.1.1 오늘 세션 (2026-07-08) 통찰 55-88번 상세

**Sprint 5-E 논의 (55-73)**:
- 55: 방어 층위 분리 (Rate Limiter ≠ Enqueue)
- 56: Redis Single Thread 성능 한계
- 57: 성능 최적화 트레이드오프
- 58: Adaptive Strategy 개념
- 59: 도메인 목적에 맞는 구현 결정
- 60: 플랫폼 서비스 데이터 책임 소재
- 61: 작업 특성별 부하 측정 분리
- 62: Single Thread 병목의 진짜 해결
- 63: 부하 특성별 최적 도구 선택
- 64: 데이터 변경 패턴 인식이 아키텍처 결정 기준
- 65: SSE는 SDK 계약 근본 변경 (Backpressure 필수)
- 66: Tomcat + Virtual Thread로 SSE 가능
- 67: 인프라 vs 애플리케이션 변경 구분
- 68: Lua Script 원자성 확보
- 69: 확장 전략 다양성
- 70: Redis 배포 방식 근본 차이 (Standalone/Sentinel/Cluster)
- 71: 규모별 인프라 계획의 단계적 확장
- 72: Cluster 학습 우선 접근
- 73: 전체 아키텍처 진화 로드맵 사고

**Cluster 실습 및 대규모 설계 (74-88)**:
- 74: 학습/설계 문서의 도메인 배치와 이력 보존
- 75: Cluster 실습 완료 (Sentinel 병행 가능)
- 76: Slot 자동 분배 확인 (CRC16 알고리즘)
- 77: Cluster 자동 라우팅 (Lettuce)
- 78: Failover 실전 성공 (5-10초 감지)
- 79: anti-affinity 경고 인지
- 80: Cluster의 실질적 이점
- 81: Cluster 메모리 용량 정확 계산 (128 bytes/항목)
- 82: Cluster의 Queue 격리 원리
- 83: Cluster 부하 격리는 Master 단위
- 84: Cluster의 애플리케이션 제어 방식 (Hash Tag + Cluster Routing)
- 85: 이중 라우팅의 완전한 제어 아키텍처
- 86: 대규모 인프라 실측 기반 계산 (1억 대기 22 GB)
- 87: Redis Single Thread 병목을 Master 수 증가로 해결
- 88: 극대 분산 아키텍처의 완전한 설계 (4x4x4GB)

### 8.2 면접 자산 매트릭스

#### Junior 백엔드 개발자 수준
- Cache Aside 패턴
- Rate Limiting (Fixed Window, Token Bucket)
- JWT 인증 (Refresh Token Rotation)
- JPA 최적화
- 트랜잭션 관리

#### Mid 백엔드 개발자 수준
- 헥사고날 아키텍처
- Distributed Lock
- Redis Cluster 운영
- Kafka 이벤트 소싱
- SSE 실시간 통신
- Adaptive Batching

#### 시니어 백엔드 개발자 수준
- 전체 아키텍처 진화 로드맵
- Anti-pattern 인식 (Facade 도입 → 롤백)
- 확장 전략 트레이드오프
- 실무 관행 근거 있는 결정
- 시스템 균형 사고 (Backpressure)

#### 백엔드 아키텍트 수준
- Multi-Region 배포
- Hybrid Redis 설계
- SLA 관리
- 팀 리딩 관점

### 8.3 Line Pay Plus 지원 관점

**본인 목표 매핑**:

**결제 도메인 요구사항**:
- 정확성 (원자성) → Lua Script 학습
- 재시도 안전 (Idempotency) → Sprint 5-E enqueue 중복 방지
- 대량 처리 → Adaptive Batching, Kafka
- 실시간 → SSE + Backpressure

**시니어 사고 검증**:
- Anti-pattern 인식
- 도메인 목적 이해
- 트레이드오프 명확화
- 실무 관행 근거

---

## 9. Sprint 계획 요약표

> ⚠️ **아래 표는 옛 계획이다.** Sprint 번호·순서 모두 실제와 다르다 — 문서 맨 위의 대조표를 보라.
> 정본은 [`ROADMAP.md`](ROADMAP.md)다. 남겨 두는 이유는 **당시 무엇을 어느 크기로 봤는지**가
> 기록으로서 의미가 있어서다.

| Sprint | 목표 | 기술 | 예상 시간 |
|--------|------|------|-----------|
| 5-E | Enqueue Bulk 단독 | Bulk Lua + Hash Tag | 6-8h |
| 6 | ApiKey 인증 | Cache 활용 | 4-6h |
| 7 | WAS 확장 + DLock | Nginx + Redis Lock | 8-10h |
| 8 | Cluster 학습 | 6 노드 로컬 | 8-12h |
| 9 | Rank + Caffeine | Polling 대응 | 6-8h |
| 10 | Cluster 도입 | 마이그레이션 | 8-12h |
| 11-12 | Kafka 도입 | Producer + Consumer | 16-20h |
| 13-14 | SSE + Backpressure | Push 기반 | 16-20h |
| 15 | 통합 최적화 | 부하 테스트 | 8-12h |
| 15+ | Multi-Region + Hybrid | Global 확장 | 40h+ |

---

## 10. 결정 사항 요약

### 10.1 확정된 결정

- **Sprint 5-E**: Bulk 단독 Enqueue + Hash Tag (§70 — 하이브리드 폐기)
- **Sprint 5-E 이후 Polling**: 캐싱 우선 (Sprint 9 Caffeine)
- **SSE 도입**: Sprint 13-14
- **Cluster 도입**: Sprint 10 (Tenant 100+ 대응)
- **WAS**: Tomcat 유지 (WebFlux 미도입)
- **Virtual Thread 활용**: 전 Sprint 지속

### 10.2 Cluster 구성 계획

**Sprint 10 (초기 도입)**:
- 3 Master + 3 Replica
- 각 Node 8-16GB
- 총 저장 24-48GB

**Sprint 12 (확장)**:
- 5 Master + 5 Replica
- 각 Node 16-32GB
- 총 저장 80-160GB

**Sprint 15+ (대규모)**:
- 7-10 Master + Replica
- 각 Node 32GB
- 총 저장 224-320GB
- Multi-AZ 배치

### 10.3 통찰 자산 (Sprint 5-E 시작 시점)

**신규 통찰 (55-73번)**:

- **55**: 방어 층위 분리 원칙
- **56**: Redis Single Thread 성능 한계
- **57**: 성능 최적화 기법 트레이드오프
- **58**: Adaptive Strategy 개념
- **59**: 도메인 목적에 맞는 구현 결정
- **60**: 플랫폼 서비스 데이터 책임 소재
- **61**: 작업 특성별 부하 측정 분리
- **62**: Single Thread 병목의 진짜 해결
- **63**: 부하 특성별 최적 도구 선택
- **64**: 데이터 변경 패턴 인식이 아키텍처 결정 기준
- **65**: SSE는 SDK 계약을 근본적으로 바꾼다
- **66**: Tomcat + Virtual Thread로 SSE 가능
- **67**: 인프라 vs 애플리케이션 변경 구분
- **68**: Lua Script를 활용한 원자성 확보
- **69**: 확장 전략의 다양성과 단계적 도입
- **70**: Redis 배포 방식의 근본 차이 이해
- **71**: 규모별 인프라 계획의 단계적 확장
- **72**: Cluster 학습 우선 접근
- **73**: 전체 아키텍처 진화 로드맵 사고

---

## 11. 참고 자료

### 실무 관행 참고
- **Ticketmaster**: Queue 서비스 아키텍처
- **Netflix**: EVCache + Caffeine 2-Tier 캐싱
- **Uber**: Adaptive Batching (Ride Matching)
- **Discord**: Queue + Rate Limiter
- **Google Ads**: Dynamic Batching

### 기술 문서
- Redis Cluster: https://redis.io/docs/reference/cluster-spec/
- Kafka: https://kafka.apache.org/documentation/
- SSE: https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events
- Java Virtual Thread (JEP 444): https://openjdk.org/jeps/444
- Caffeine: https://github.com/ben-manes/caffeine

---

**작성일**: Sprint 5-D 완료 시점
**다음 업데이트**: Sprint 5-E 완료 후 (통찰 65-70개 반영)
**최종 목표**: Line Pay Plus 시니어 백엔드 지원 + 실무 완결성

---

# 부록

## 부록 A: Enqueue vs Polling 부하 특성 분리

### A.1 왜 통합 부하 측정이 문제인가?

**초기 접근 (Anti-pattern)**:
```
전체 Redis 부하 통합 측정
- Enqueue 요청 + Polling 요청 + 기타
- 임계값 초과 시 모두 Bulk 모드
```

**문제점**:
- Enqueue는 즉시성 필요 (티켓팅 UX)
- Polling은 지연 허용 (5초 갱신)
- 통합 측정 시 Enqueue도 Bulk 지연 발생
- 도메인 요구 미충족

### A.2 두 작업의 근본 차이

| 특성 | Enqueue | Polling (Rank 조회) |
|------|---------|-------------------|
| 빈도 | 1회 진입 | 지속 (5초마다) |
| 즉시성 | 필수 (순번 즉시) | 허용 (5초 지연 OK) |
| 실패 영향 | 큼 (진입 실패) | 작음 (다음 폴링) |
| 부담 | Enqueue 시점만 | 사용자 수만큼 지속 |
| 최적화 | Bulk Lua | 캐싱 |

### A.3 부하 특성 예상

```
[티켓팅 순간]
100만 사용자 접근:

[Enqueue]
- 초당 10,000-50,000 요청 (단시간)
- 순번 즉시 필요
- Bulk Lua로 처리량 확보

[Polling]
- 초당 200,000 요청 (지속)
- 대부분 낭비 (99.75% - Admit 시에만 rank 변화)
- Caffeine 캐싱 필수
- 미래 SSE 대체
```

### A.4 결정 사항

**Sprint 5-E** (2026-07-15 개정 — §70):
- ~~SlidingWindowCounter는 **Enqueue 요청만** 카운트~~ → 폐기 (하이브리드 폐기로 불필요)
- ~~Enqueue 하이브리드 (일반 + Bulk Lua)~~ → **Bulk Lua 단독**
- Polling은 Sprint 5-E 범위 밖

**Sprint 9**:
- Rank Query 전용 서비스 신설
- Caffeine 캐싱 (1-2초 TTL)
- 별도 부하 관리

**Sprint 13-14**:
- SSE 도입으로 Polling 자체 소멸
- Push 기반 rank 업데이트
- 요청 초당 200,000 → 0

### A.5 핵심 통찰

- **통찰 61**: 작업 특성별 부하 측정 분리
- **통찰 62**: Single Thread 병목의 진짜 해결 (Bulk만으로 X)
- **통찰 63**: 부하 특성별 최적 도구 선택 (Redis vs Caffeine)
- **통찰 64**: 데이터 변경 패턴 인식이 아키텍처 결정 기준

### A.6 아키텍처 다이어그램

```
[Sprint 9 이후]

Client (Polling)
   ↓
QueueController.getRank()
   ↓
QueueService.getRank()
   ↓
CaffeineRankCache 조회
   ├─ HIT (80-95%): 즉시 반환 (0.0001ms)
   └─ MISS (5-20%):
       ├─→ RankQueryEngine (Redis 조회, 1-2ms)
       └─→ Caffeine에 저장 (TTL 1초)

Client (Enqueue)
   ↓
QueueController.enqueue()
   ↓
QueueService.enqueue()
   ↓
RedisQueueEngine (Global Queue Producer)
   ↓ PendingEnqueue.offer() / future.get()
BatchProcessor (@Scheduled Consumer)
   └─ enqueue_bulk.lua 단독 (Hash Tag 2-key)
```

---

## 부록 B: 애플리케이션 라우팅 vs Cluster 대안 분석

### B.1 두 접근 방식 개요

**방식 1: Redis Cluster (자동 샤딩)**
- Redis 자체가 여러 Master로 데이터 분산
- Lettuce가 자동 라우팅
- Slot 기반 (16,384개)

**방식 2: 애플리케이션 라우팅 (수동)**
- 여러 개의 독립 Redis (Sentinel or Standalone)
- 애플리케이션 코드가 Queue별 Redis 결정
- 완전 커스텀 라우팅 로직

### B.2 구조 비교

**Redis Cluster**:
```
Client
  ↓
[Spring Boot + Lettuce]
  ↓ (자동 라우팅)
[Redis Cluster - 하나의 논리적 시스템]
  ├─ Master 1 (Slot 0-5461)
  ├─ Master 2 (Slot 5462-10922)
  └─ Master 3 (Slot 10923-16383)
```

**애플리케이션 라우팅**:
```
Client
  ↓
[Spring Boot + RedisRouter (커스텀)]
  ↓ (수동 결정)
  ├─→ Redis Sentinel A (독립)
  ├─→ Redis Sentinel B (독립)
  └─→ Redis Sentinel C (독립)
```

### B.3 상세 비교표

| 항목 | Redis Cluster | 애플리케이션 라우팅 |
|------|---------------|--------------------|
| 학습 부담 | 중간 (Cluster 개념) | 낮음 (Sentinel 유지) |
| 코드 복잡성 | 낮음 (application.yml만) | 높음 (Router 클래스) |
| Rebalancing | 자동 | 수동 (코드 수정) |
| Node 추가 | Cluster 명령 | 코드 배포 필요 |
| 장애 처리 | Cluster 자동 | 직접 구현 |
| Multi-key 명령 | 제한 (같은 slot만) | 자유 (같은 인스턴스만) |
| 실무 관행 | 표준 | 특수 상황 |
| 유지보수 | 편함 | 복잡 |

### B.4 애플리케이션 라우팅 구현 예시

```java
@Component
public class RedisRouter {
    private final Map<String, StringRedisTemplate> instances;
    
    // Queue별 Redis 인스턴스 결정
    public StringRedisTemplate getForQueue(String queueId) {
        int hash = queueId.hashCode();
        int index = Math.abs(hash) % instances.size();
        return instances.get("redis_" + index);
    }
    
    // Tenant별 Redis 인스턴스 결정 (대안)
    public StringRedisTemplate getForTenant(Long tenantId) {
        int hash = tenantId.hashCode();
        int index = Math.abs(hash) % instances.size();
        return instances.get("redis_" + index);
    }
}

// 사용
@Service
public class QueueService {
    private final RedisRouter router;
    
    public void enqueue(String queueId, String identifier) {
        StringRedisTemplate redis = router.getForQueue(queueId);
        redis.execute(enqueueScript, ...);
    }
}
```

### B.5 애플리케이션 라우팅의 함정

**함정 1: Node 추가 시 Rebalancing 지옥**
```
[상황]
Redis 3대 → 4대 추가

[Cluster]
Cluster 명령으로 자동 slot 재분배
데이터 이동 백그라운드
서비스 무중단

[애플리케이션 라우팅]
hash 함수 변경 (mod 3 → mod 4)
기존 key의 위치 계산 다름
모든 key 재배치 필요
직접 마이그레이션 코드 작성
```

**함정 2: 장애 처리 복잡**
```
[상황]
Redis Sentinel B의 Master 장애

[Cluster]
Cluster가 감지하여 Replica 승격
자동 Failover

[애플리케이션 라우팅]
각 Sentinel 독립 감지
애플리케이션이 알아야 함
Health Check 구현 필요
Fallback 로직 필요
```

**함정 3: 관리 부담**
```
[Cluster]
6-10개 노드 = 하나의 Cluster
통합 모니터링

[애플리케이션 라우팅]
3-5개 독립 Sentinel × 6 노드 = 총 15-30개 노드
각각 별도 관리
별도 모니터링
```

### B.6 실무 결정 기준

**Cluster를 선택하는 경우** ✓ (본인 프로젝트 권장):
- 표준 관행 따르고 싶음
- Rebalancing 자동 원함
- Node 추가/제거 유연
- Lua Script가 Hash Tag로 slot 고정됨 (본인 프로젝트, §70)
- 팀에서 Cluster 학습 가능

**애플리케이션 라우팅을 선택하는 경우**:
- Tenant별 완전 격리 필수 (규정)
- 특수 정책 (예: VIP Tenant 별도 Redis)
- Cluster 학습 부담 회피 (초기)
- Multi-key 명령 자유 필요

### B.7 본인 프로젝트 결정

**Sprint 10**: Redis Cluster 선택

**근거**:
1. Queue Platform의 Lua Script는 **2-key지만 Hash Tag로 동일 slot 보장** (2026-07-15 개정, §70)
   - `enqueue_bulk.lua`: KEYS[1] = `queue:{queueId}:waiting`, KEYS[2] = `queue:{queueId}:seq`
   - 중괄호가 Hash Tag → 슬롯 계산에 `queueId`만 사용 → 두 키가 항상 같은 slot
   - CROSSSLOT 문제 없음 (Hash Tag 덕분이지, 단일 key라서가 아님)

2. Tenant 격리 요구 없음 (일반 SaaS)

3. 자동 확장 필요 (Sprint 12에서 5 Master로)

4. 실무 관행 준수 (면접 자산)

5. Lettuce 자동 라우팅 (Java 코드 무변경)

### B.8 핵심 통찰

- **통찰 69**: 확장 전략의 다양성과 단계적 도입
- **통찰 70**: Redis 배포 방식의 근본 차이 이해
- **통찰 71**: 규모별 인프라 계획의 단계적 확장
- **통찰 72**: Cluster 학습 우선 접근

---

## 부록 C: Redis Cluster 실전 명령어 가이드

### C.1 Cluster 초기화

**최소 구성 (3 Master + 3 Replica)**:
```bash
# 초기화 명령
redis-cli --cluster create \
  127.0.0.1:7001 127.0.0.1:7002 127.0.0.1:7003 \
  127.0.0.1:7004 127.0.0.1:7005 127.0.0.1:7006 \
  --cluster-replicas 1

# 프롬프트 응답 후 초기화 완료
# yes 입력
```

**옵션 설명**:
- `--cluster-replicas 1`: 각 Master마다 Replica 1개
- 처음 3개: Master 후보
- 나머지 3개: 자동으로 Replica 할당

### C.2 상태 확인 명령

**전체 상태 개요**:
```bash
# Cluster 정보
redis-cli -c -p 7001 cluster info

# 출력 예시:
# cluster_enabled:1
# cluster_state:ok
# cluster_slots_assigned:16384
# cluster_slots_ok:16384
# cluster_slots_pfail:0
# cluster_slots_fail:0
# cluster_known_nodes:6
# cluster_size:3
# cluster_current_epoch:6
# cluster_my_epoch:1
```

**노드 목록**:
```bash
redis-cli -c -p 7001 cluster nodes

# 출력 형식:
# <id> <ip:port@cport> <flags> <master_id> ...
# 
# 예:
# a1b2c3... 127.0.0.1:7001@17001 myself,master - 0 1720000000000 1 connected 0-5461
# d4e5f6... 127.0.0.1:7002@17002 master - 0 1720000000000 2 connected 5462-10922
```

**Slot 정보**:
```bash
redis-cli -c -p 7001 cluster slots

# 출력:
# 1) 1) (integer) 0        # Slot 시작
#    2) (integer) 5461     # Slot 끝
#    3) 1) "127.0.0.1"     # Master IP
#       2) (integer) 7001  # Master 포트
#       3) "a1b2c3..."     # Master ID
#    4) 1) "127.0.0.1"     # Replica IP
#       2) (integer) 7004  # Replica 포트
#       3) "x9y8z7..."     # Replica ID
```

**종합 상태 체크**:
```bash
redis-cli --cluster check 127.0.0.1:7001

# 문제 감지 시 상세 리포트
```

### C.3 Key 조작 명령

**Key의 담당 노드 확인**:
```bash
# key가 어느 slot인지
redis-cli -c -p 7001 cluster keyslot "queue:q_bts_001:waiting"
# (integer) 12345

# slot이 어느 node인지 (nodes 명령 조합)
```

**Key 개수 확인 (per node)**:
```bash
# 각 node의 key 수
redis-cli -c -p 7001 dbsize
redis-cli -c -p 7002 dbsize
redis-cli -c -p 7003 dbsize
```

**Key 저장 테스트**:
```bash
# -c 옵션: Cluster 모드 (자동 redirect)
redis-cli -c -p 7001

127.0.0.1:7001> SET queue:test:waiting "hello"
-> Redirected to slot [15495] located at 127.0.0.1:7003
OK
127.0.0.1:7003> GET queue:test:waiting
"hello"
```

### C.4 Slot 관리 명령

**Slot 재분배 (수동)**:
```bash
# 특정 노드로 slot 이동
redis-cli --cluster reshard 127.0.0.1:7001

# 프롬프트:
# How many slots do you want to move (from 1 to 16384)?
# What is the receiving node ID?
# Source node #1: (all or specific IDs)
```

**Slot 자동 분배**:
```bash
# 노드 간 균형 자동 조정
redis-cli --cluster rebalance 127.0.0.1:7001
```

### C.5 노드 추가/제거

**노드 추가 (Master)**:
```bash
# 1. 새 노드 실행 (7007)
redis-server --port 7007 --cluster-enabled yes ...

# 2. Cluster에 Master로 추가
redis-cli --cluster add-node \
  127.0.0.1:7007 \
  127.0.0.1:7001

# 3. Slot 재분배 (수동)
redis-cli --cluster reshard 127.0.0.1:7001
```

**노드 추가 (Replica)**:
```bash
redis-cli --cluster add-node \
  --cluster-slave \
  --cluster-master-id <master_id> \
  127.0.0.1:7008 \
  127.0.0.1:7001
```

**노드 제거**:
```bash
# 1. Slot 다른 노드로 이동 (Master의 경우)
redis-cli --cluster reshard 127.0.0.1:7001

# 2. 노드 제거
redis-cli --cluster del-node 127.0.0.1:7001 <node_id>
```

### C.6 Failover 명령

**수동 Failover (테스트용)**:
```bash
# Replica에서 실행 (자기 자신을 Master로 승격)
redis-cli -c -p 7004 cluster failover

# 옵션:
# FORCE: Master 응답 없어도 강제
# TAKEOVER: 전체 Cluster 동의 없이 강제
```

### C.7 데이터 조작 명령

**모든 Key 조회 (주의)**:
```bash
# Cluster의 모든 노드 스캔 (프로덕션에서 조심)
for port in 7001 7002 7003; do
  redis-cli -c -p $port --scan --pattern "queue:*"
done
```

**Key 개수 (per pattern)**:
```bash
# 특정 패턴 개수
redis-cli -c -p 7001 --scan --pattern "queue:*" | wc -l
```

### C.8 문제 상황 대응

**클러스터 상태 이상 시**:
```bash
# 상태 상세 확인
redis-cli --cluster check 127.0.0.1:7001

# 자동 수정 시도
redis-cli --cluster fix 127.0.0.1:7001
```

**Slot 오류 (일부 slot 미할당)**:
```bash
# 미할당 slot 확인
redis-cli --cluster check 127.0.0.1:7001

# 수정
redis-cli --cluster fix 127.0.0.1:7001
```

---

## 부록 D: Redis Cluster 설정 파일 상세

### D.1 기본 설정 파일 (redis.conf)

**7001 노드 예시**:
```conf
# 기본 포트
port 7001

# Cluster 활성화
cluster-enabled yes

# Cluster 설정 파일 (자동 생성)
cluster-config-file nodes-7001.conf

# Cluster 노드 타임아웃 (ms)
cluster-node-timeout 5000

# AOF (Append Only File) 영속성
appendonly yes
appendfilename "appendonly-7001.aof"

# 데이터 저장 위치
dir /home/sonix/redis-cluster/7001

# 프로세스 ID 파일
pidfile /home/sonix/redis-cluster/7001/redis.pid

# 로그
logfile /home/sonix/redis-cluster/7001/redis.log
loglevel notice

# 최대 메모리 (Node 크기)
maxmemory 8gb
maxmemory-policy noeviction

# 네트워크
bind 127.0.0.1
protected-mode no  # 개발 환경만

# 인증 (프로덕션 필수)
# requirepass "your-strong-password"
# masterauth "your-strong-password"
```

### D.2 각 노드별 설정 (7001-7006)

**7001 (Master 후보)**:
```conf
port 7001
cluster-enabled yes
cluster-config-file nodes-7001.conf
cluster-node-timeout 5000
appendonly yes
appendfilename "appendonly-7001.aof"
dir /home/sonix/redis-cluster/7001
```

**7002-7006**: 포트와 파일명만 변경 (동일 패턴)

### D.3 시스템 서비스 등록 (systemd)

**/etc/systemd/system/redis-cluster-7001.service**:
```ini
[Unit]
Description=Redis Cluster Node 7001
After=network.target

[Service]
Type=simple
User=sonix
Group=sonix
ExecStart=/usr/bin/redis-server /home/sonix/redis-cluster/7001/redis.conf
ExecStop=/usr/bin/redis-cli -p 7001 shutdown
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

**등록 명령**:
```bash
# 서비스 등록
sudo systemctl daemon-reload
sudo systemctl enable redis-cluster-7001
sudo systemctl enable redis-cluster-7002
sudo systemctl enable redis-cluster-7003
sudo systemctl enable redis-cluster-7004
sudo systemctl enable redis-cluster-7005
sudo systemctl enable redis-cluster-7006

# 시작
sudo systemctl start redis-cluster-7001
sudo systemctl start redis-cluster-7002
sudo systemctl start redis-cluster-7003
sudo systemctl start redis-cluster-7004
sudo systemctl start redis-cluster-7005
sudo systemctl start redis-cluster-7006

# 상태 확인
sudo systemctl status redis-cluster-7001
```

### D.4 Spring Boot application.yml

**개발 환경 (application-dev.yml)**:
```yaml
spring:
  data:
    redis:
      cluster:
        nodes:
          - localhost:7001
          - localhost:7002
          - localhost:7003
          - localhost:7004
          - localhost:7005
          - localhost:7006
        max-redirects: 3
      # password: (개발 환경 인증 없음)
      lettuce:
        cluster:
          refresh:
            adaptive: true         # 자동 topology 갱신
            period: 30s            # 갱신 주기
        pool:
          max-active: 100
          max-idle: 20
          min-idle: 10
```

**프로덕션 환경 (application-prod.yml)**:
```yaml
spring:
  data:
    redis:
      cluster:
        nodes:
          - redis-cluster-node-1.internal:6379
          - redis-cluster-node-2.internal:6379
          - redis-cluster-node-3.internal:6379
          - redis-cluster-node-4.internal:6379
          - redis-cluster-node-5.internal:6379
          - redis-cluster-node-6.internal:6379
        max-redirects: 3
      password: ${REDIS_PASSWORD}
      lettuce:
        cluster:
          refresh:
            adaptive: true
            period: 30s
        pool:
          max-active: 200
          max-idle: 50
          min-idle: 20
```

### D.5 Lettuce Client 설정 (선택적 커스텀)

**LettuceClientConfigurationBuilder**:
```java
@Configuration
public class RedisClusterConfig {
    
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration()
            .clusterNode("localhost", 7001)
            .clusterNode("localhost", 7002)
            .clusterNode("localhost", 7003)
            .clusterNode("localhost", 7004)
            .clusterNode("localhost", 7005)
            .clusterNode("localhost", 7006);
        
        clusterConfig.setMaxRedirects(3);
        
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(2))
            .shutdownTimeout(Duration.ofMillis(100))
            .clientOptions(ClusterClientOptions.builder()
                .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                    .enableAllAdaptiveRefreshTriggers()
                    .refreshPeriod(Duration.ofSeconds(30))
                    .build())
                .build())
            .build();
        
        return new LettuceConnectionFactory(clusterConfig, clientConfig);
    }
}
```

---

## 부록 E: Cluster 학습 실습 절차

### E.1 Sprint 8 학습 로드맵

**Week 1: 개념 이해 (4-6시간)**
- [ ] Sentinel vs Cluster 차이
- [ ] Hash Slot 원리
- [ ] Master-Replica 관계
- [ ] Failover 메커니즘
- [ ] Client 라우팅 (Lettuce)

**Week 2: 로컬 실습 (8-12시간)**
- [ ] 6 노드 구성 (WSL 네이티브)
- [ ] Cluster 초기화
- [ ] 기본 명령 학습
- [ ] Key 저장/조회
- [ ] Failover 테스트

**Week 3: Spring Boot 통합 (4-6시간)**
- [ ] application.yml 변경
- [ ] 기존 코드 무변경 확인
- [ ] Lua Script 테스트
- [ ] 성능 비교 (Sentinel vs Cluster)

### E.2 Step-by-Step 실습

#### Step 1: 폴더 준비

```bash
# 홈 디렉토리에 Cluster 폴더 생성
mkdir -p ~/redis-cluster/{7001,7002,7003,7004,7005,7006}
cd ~/redis-cluster
```

#### Step 2: 설정 파일 생성 (6개)

```bash
# 7001 노드 (예시, 7002-7006도 동일 패턴)
cat > ~/redis-cluster/7001/redis.conf << 'EOF'
port 7001
cluster-enabled yes
cluster-config-file nodes-7001.conf
cluster-node-timeout 5000
appendonly yes
appendfilename "appendonly-7001.aof"
dir /home/sonix/redis-cluster/7001
pidfile /home/sonix/redis-cluster/7001/redis.pid
logfile /home/sonix/redis-cluster/7001/redis.log
loglevel notice
maxmemory 1gb
maxmemory-policy noeviction
bind 127.0.0.1
protected-mode no
EOF

# 스크립트로 6개 한 번에 생성
for port in 7001 7002 7003 7004 7005 7006; do
    mkdir -p ~/redis-cluster/$port
    cat > ~/redis-cluster/$port/redis.conf << EOF
port $port
cluster-enabled yes
cluster-config-file nodes-$port.conf
cluster-node-timeout 5000
appendonly yes
appendfilename "appendonly-$port.aof"
dir /home/sonix/redis-cluster/$port
pidfile /home/sonix/redis-cluster/$port/redis.pid
logfile /home/sonix/redis-cluster/$port/redis.log
loglevel notice
maxmemory 1gb
maxmemory-policy noeviction
bind 127.0.0.1
protected-mode no
EOF
done
```

#### Step 3: systemd 서비스 등록 (6개)

```bash
# 서비스 파일 생성 스크립트
for port in 7001 7002 7003 7004 7005 7006; do
    sudo tee /etc/systemd/system/redis-cluster-$port.service > /dev/null << EOF
[Unit]
Description=Redis Cluster Node $port
After=network.target

[Service]
Type=simple
User=sonix
Group=sonix
ExecStart=/usr/bin/redis-server /home/sonix/redis-cluster/$port/redis.conf
ExecStop=/usr/bin/redis-cli -p $port shutdown
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
done

# 서비스 활성화 및 시작
sudo systemctl daemon-reload
for port in 7001 7002 7003 7004 7005 7006; do
    sudo systemctl enable redis-cluster-$port
    sudo systemctl start redis-cluster-$port
done

# 상태 확인
for port in 7001 7002 7003 7004 7005 7006; do
    echo "=== $port ==="
    sudo systemctl status redis-cluster-$port --no-pager | head -5
done
```

#### Step 4: Cluster 초기화

```bash
redis-cli --cluster create \
  127.0.0.1:7001 127.0.0.1:7002 127.0.0.1:7003 \
  127.0.0.1:7004 127.0.0.1:7005 127.0.0.1:7006 \
  --cluster-replicas 1

# 프롬프트 나오면 yes 입력
# "Can I set the above configuration? (type 'yes' to accept):"
```

#### Step 5: 초기 확인

```bash
# 상태 확인
redis-cli -c -p 7001 cluster info

# 노드 확인
redis-cli -c -p 7001 cluster nodes

# 데이터 저장 테스트
redis-cli -c -p 7001

127.0.0.1:7001> SET queue:test:001 "user_a"
-> Redirected to slot [4521] located at 127.0.0.1:7001
OK

127.0.0.1:7001> SET queue:test:002 "user_b"
-> Redirected to slot [12345] located at 127.0.0.1:7003
OK

127.0.0.1:7001> GET queue:test:001
"user_a"

127.0.0.1:7001> GET queue:test:002
-> Redirected to slot [12345] located at 127.0.0.1:7003
"user_b"
```

#### Step 6: Lua Script 테스트

```bash
# 단일 key Lua Script (본인 프로젝트 스타일)
redis-cli -c -p 7001 EVAL "
local key = KEYS[1]
local value = ARGV[1]
redis.call('SET', key, value)
return redis.call('GET', key)
" 1 "queue:q_test_001:waiting" "user_x"

# 결과: "user_x"
# 자동으로 담당 노드에서 처리됨
```

#### Step 7: Failover 테스트

```bash
# 현재 Master 확인
redis-cli -c -p 7001 cluster nodes | grep master

# Master 강제 종료 (예: 7001)
sudo systemctl stop redis-cluster-7001

# 대기 (5-10초)
sleep 10

# Replica가 Master로 승격 확인
redis-cli -c -p 7002 cluster nodes | grep master
# 7004가 새 Master 되었는지 확인

# 원래 노드 재시작
sudo systemctl start redis-cluster-7001

# 원래 Master가 Replica로 강등 확인
redis-cli -c -p 7001 cluster nodes
```

#### Step 8: Spring Boot 통합 테스트

```yaml
# application-cluster-test.yml
spring:
  data:
    redis:
      cluster:
        nodes:
          - localhost:7001
          - localhost:7002
          - localhost:7003
          - localhost:7004
          - localhost:7005
          - localhost:7006
```

```java
// 테스트 코드
@SpringBootTest
class ClusterIntegrationTest {
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    @Test
    void testClusterConnection() {
        // 여러 key 저장 (다른 slot으로 분산됨)
        for (int i = 0; i < 100; i++) {
            String key = "queue:test:" + i;
            redisTemplate.opsForValue().set(key, "value_" + i);
        }
        
        // 확인
        for (int i = 0; i < 100; i++) {
            String key = "queue:test:" + i;
            String value = redisTemplate.opsForValue().get(key);
            assertEquals("value_" + i, value);
        }
    }
}
```

### E.3 학습 체크리스트

**개념 이해** (Week 1):
- [ ] Sentinel과 Cluster의 근본 차이 설명 가능
- [ ] Hash Slot 계산 원리 이해
- [ ] Master-Replica 승격 시나리오 이해
- [ ] Gossip Protocol 개념 이해

**로컬 실습** (Week 2):
- [ ] 6 노드 구성 완료
- [ ] Cluster 초기화 성공
- [ ] 기본 CRUD 작동 확인
- [ ] Lua Script 실행 확인
- [ ] Failover 테스트 완료
- [ ] Slot 이동 실습 완료

**Spring Boot 통합** (Week 3):
- [ ] application.yml 변경
- [ ] 기존 코드 무변경 확인
- [ ] Enqueue Lua Script Cluster에서 작동
- [ ] 성능 비교 완료

### E.4 문제 해결 가이드

**Cluster 초기화 실패**:
```bash
# 이전 상태 초기화
for port in 7001 7002 7003 7004 7005 7006; do
    sudo systemctl stop redis-cluster-$port
    rm -f ~/redis-cluster/$port/nodes-$port.conf
    rm -f ~/redis-cluster/$port/appendonly-$port.aof
    rm -f ~/redis-cluster/$port/dump.rdb
    sudo systemctl start redis-cluster-$port
done

# 재초기화
redis-cli --cluster create ...
```

**CROSSSLOT 에러**:
```
[에러 메시지]
(error) CROSSSLOT Keys in request don't hash to the same slot

[원인]
Lua Script에서 여러 key 사용, 다른 slot

[해결]
Hash Tag 활용: {tag}
- key1 = "queue:{q_bts_001}:waiting"
- key2 = "queue:{q_bts_001}:count"
- {q_bts_001} 부분만으로 slot 계산
```

**연결 실패**:
```bash
# 노드 상태 확인
for port in 7001 7002 7003 7004 7005 7006; do
    echo "=== $port ==="
    redis-cli -p $port ping
done

# 문제 노드 재시작
sudo systemctl restart redis-cluster-<port>
```

---

**부록 마지막 업데이트**: Sprint 5-D 완료 시점
**부록 목적**: 실제 구현 시 참조 가이드, 학습 자산 보존

---

## 부록 F: 이중 라우팅 아키텍처 — **Layer 1만 채택, Layer 2는 기각**

> ✏️ **2026-09-04 정정.** 이 부록의 원안(Layer 1 Cluster Router + Layer 2 Shard Resolver)
> 중 **Layer 2(부하 기반 `{shard_N}` 태그 배정)는 기각됐다.** 아래는 실제로 구현된 것이다.

**Layer 1 — Cluster 선택 (구현 완료, §75)**
- `RedisClusterAssigner`가 큐 생성 시 A/B 중 하나를 고른다.
- 기준은 `used_memory ÷ maxmemory` **0.5 규칙**이다. Tenant Tier가 아니다 —
  등급제는 §88에서 통째로 걷어냈다.
- 한 큐의 키 4종(`waiting`·`seq`·`tokens`·`last-active`)은 같은 클러스터에 놓인다 (§75 D26).
- 매핑은 `queues` 행에 남고, 기존 항목에 대해 불변이다 (§75 D27-2).

**Layer 2 — Shard 태그 (⛔ 기각)**
- 해시태그의 용도는 **부하 분산이 아니라 CROSSSLOT 방지**다. 다중 키 Lua
  (`enqueue_bulk.lua` 3키 · `poll_verify.lua` 3키)가 한 슬롯에 모이게 하는 것이 전부다.
- 그래서 태그는 `queueId` 하나뿐이다: `queue:{q_bts_002}:waiting`.
  슬롯 배치는 Redis가 CRC16으로 정하고, 애플리케이션은 Master를 고르지 않는다.
- 부하 기반 재배정을 하려면 태그를 바꿔야 하는데, 태그를 바꾸면 **키가 바뀐다** —
  운영 중인 큐의 대기열을 옮기는 일이 된다. 얻는 것(균등 배치)보다 잃는 것이 크다.
- 키 생성은 전부 `queue/QueueKeys.java`를 거친다. 태그 없는 키를 다중 키 Lua의 KEYS에
  끼우면 **Cluster에서만** `CROSSSLOT`으로 깨지고, 로컬 Sentinel 테스트로는 안 잡힌다.

🪤 **다른 슬롯이라도 같은 노드에 떨어지면 CROSSSLOT이 안 난다**(실측 25%).
Cluster에서 초록은 태그가 맞다는 증거가 아니다.

---

## 부록 G: 1억 대기 처리 인프라 실측 계산

### G.1 데이터 크기 정확 계산

**Redis ZSet 항목당 메모리 (UUID identifier 기준)**:

```
[Skip List Node]
- member 포인터: 8 bytes
- score (double): 8 bytes
- backward 포인터: 8 bytes
- level 배열 (평균 4 levels × 16 bytes): 64 bytes
- Skip List 오버헤드: 약 88 bytes

[Hash Table Entry]
- member 문자열 (UUID 32 bytes)
- dict entry 오버헤드: 16 bytes
- 소계: 48 bytes

[Redis 문자열 오버헤드 (SDS)]
- header: 5 bytes
- null terminator: 1 byte
- allocator overhead: 8-16 bytes

[Total per member]
88 + 48 + 32 + 24 = 약 190-200 bytes
```

**단순 계산 (128 bytes/항목 기준)**:
- 1억 항목 = 12.8 GB

**Redis 자체 오버헤드 (20%)**:
- 12.8 GB × 1.2 = 15.4 GB

**안전 마진 30% 반영 (프로덕션 관행)**:
- 15.4 GB / 0.7 = **약 22 GB** (실사용 15.4GB, 30% 여유)

### G.2 Master 수 결정 기준

**최소 조건**:
- Redis Cluster: 3 Master 최소

**부하 분산 관점**:
- 각 Master 최대 40,000 ops/초 (Single Thread 한계)
- 3 Master: 120,000 ops/초
- 5 Master: 200,000 ops/초
- 10 Master: 400,000 ops/초
- 16 Master: 640,000 ops/초

**Failover 안정성**:
- 3 Master: 1대 다운 시 33% 손실
- 5 Master: 20% 손실
- 10 Master: 10% 손실
- 16 Master: 6.25% 손실

**관리 부담**:
- Master 3개: 6 노드
- Master 5개: 10 노드
- Master 10개: 20 노드
- Master 16개: 32 노드

### G.3 SLA 균등 조건 시 최적 구성

**5 Master + 5 Replica × 16 GB**:
- 초당 처리: 200,000 ops
- 총 저장: 80 GB
- 실사용 22 GB (28%)
- 여유: 72%
- 노드 수: 10개

**비용 (AWS ElastiCache 기준)**:
- cache.r6g.large (16GB): $109/월/노드
- 10 노드 × $109 = $1,094/월
- 총 인프라: $1,700-2,300/월
- 연간: $20,000-28,000
- 사용자당: $0.00025 (0.25원)

### G.4 확장 시나리오

**5억 대기 시**:
- 필요 저장: 110 GB (5배)
- 대응 옵션 A: Node 추가 (10 → 20 Master)
- 대응 옵션 B: Node 크기 증가 (16GB → 32GB)
- 대응 옵션 C: Hybrid

**10억 대기 시**:
- 다중 Cluster 도입
- 지역 분산
- 완전한 이중 라우팅

### G.5 핵심 통찰

- **통찰 81**: Cluster 메모리 용량 정확한 계산 (128 bytes/항목)
- **통찰 86**: 대규모 인프라 실측 기반 계산

---

## 부록 H: Master 크기 최적화 원리

### H.1 Redis Single Thread의 본질

**핵심**:
- 하나의 Redis 프로세스 = CPU 코어 1개만 사용
- 명령어 순차 실행
- Lua Script 실행 중 lock

**결과**:
- 초당 30,000-50,000 ops 한계 (Master 하나)
- CPU 성능에 관계없이 코어 1개
- 8코어 CPU여도 코어 1개만 사용

**Master 크기가 커도**:
- 코어 1개 사용은 동일
- 메모리만 커짐 (128GB의 큰 Master ≠ 16GB × 8 코어)
- Single Thread 병목 그대로

### H.2 해결 원리 - Master 늘리기

**Before (5 Master × 16GB)**:
- 5개 Redis 프로세스 = CPU 코어 5개 활용
- 초당 처리: 5 × 40,000 = 200,000 ops

**After (10 Master × 8GB)**:
- 10개 Redis 프로세스 = CPU 코어 10개 활용
- 초당 처리: 10 × 40,000 = 400,000 ops

**성능 향상**: 2배 처리량 증가

### H.3 물리 서버 배치 관점

**Case A - 각 Master 별도 서버**:
- 완전 격리
- 각 서버 8-16 CPU 코어 사용
- 그러나 Redis는 CPU 1개만 사용 = **낭비**
- 비용 많음

**Case B - 여러 Master 같은 서버 (권장)**:
- Server 1: Master 1 + Master 2 + Master 3
- Server 2: Master 4 + Master 5 + Master 6
- CPU 코어 완전 활용
- 비용 효율
- **실무 관행**

### H.4 Master 크기 결정 트레이드오프

**장점 (Master 늘리기)**:
- 처리량 대폭 증가
- CPU 활용도 극대화
- Failover 시 손실 최소
- 부하 격리 세밀

**단점 (Master 늘리기)**:
- 관리 복잡성 증가
- Cluster 통신 오버헤드
- Failover 감지 시간 증가
- Rebalancing 오래 걸림

**최적점**: Master 5-10개
- 성능 충분
- 관리 부담 감당
- 실무 관행

### H.5 실무 관행

**Netflix EVCache**: Master 10-50개, 각 8-16 GB
**Twitter**: Master 100+, 물리 서버당 여러 Master
**LinkedIn**: Master 100+, CPU 활용 극대화

**공통 패턴**:
1. Master는 작게, 많이 (8-16 GB)
2. 물리 서버당 여러 Master 배치
3. Cluster 하나에 여러 Master

### H.6 핵심 통찰

- **통찰 87**: Redis Single Thread 병목을 Master 수 증가로 해결

---

## 부록 I: 극대 분산 아키텍처

### I.1 최종 목표 구성

**4 Cluster × 4 Master × 4 Replica × 4GB**:
- Cluster 수: 4개
- 각 Cluster: Master 4 + Replica 4
- 총 Master: 16개
- 총 Replica: 16개
- 총 노드: 32개
- 총 저장 (Master): 64 GB
- 총 저장 (Master + Replica): 128 GB

### I.2 처리 능력

**각 Cluster**:
- Master 4개 × 40,000 ops/초 = 160,000 ops/초

**전체 4 Cluster**:
- 4 × 160,000 = **640,000 ops/초**

**비교**:
- Sentinel: 40,000 ops/초 (1 Master)
- 3 Master Cluster: 120,000 ops/초 (3배)
- 5 Master Cluster: 200,000 ops/초 (5배)
- 10 Master Cluster: 400,000 ops/초 (10배)
- **극대 분산 (16 Master): 640,000 ops/초 (16배)** ⭐

### I.3 저장 용량 여유

**1억 대기 데이터 요구**: 22 GB

**4 Cluster 분산**:
- Cluster별: 22 / 4 = 5.5 GB
- 각 Cluster 저장 용량: 16 GB
- 사용률: 34%
- 여유: 66% (매우 안전)

**각 Master 저장**:
- 5.5 GB / 4 = 1.4 GB per Master
- Master 크기: 4 GB
- 사용률: 35%
- 여유: 65%

### I.4 물리 서버 배치

**최적 구성 - 4대 물리 서버**:

```
[Server 1 - AZ-1]
Cluster 1: Master 1 (4GB, CPU 1)
Cluster 2: Master 2 (4GB, CPU 1)
Cluster 3: Master 3 (4GB, CPU 1)
Cluster 4: Master 4 (4GB, CPU 1)
+ 다른 Cluster의 Replica들

[Server 2 - AZ-2]
Cluster 1: Master 2 (4GB, CPU 1)
Cluster 2: Master 3 (4GB, CPU 1)
Cluster 3: Master 4 (4GB, CPU 1)
Cluster 4: Master 1 (4GB, CPU 1)
+ 다른 Cluster의 Replica들
```

**서버 사양 - r6g.2xlarge (8 vCPU, 64 GB)**:
- 8 Redis 프로세스 동시 실행 (Master 4 + Replica 4)
- CPU 완전 활용
- 메모리 32 GB 사용 (64 GB 여유)

### I.5 비용 상세

**EC2 자체 관리 (r6g.2xlarge × 4대)**:
- 각 서버 시간당 $0.4032
- 각 서버 월 $290.30
- 4대 총: $1,161/월

**총 인프라 비용**:
- 컴퓨팅: $1,161/월
- 네트워크: $500-1,000/월
- 관리 (CloudWatch): $200-300/월
- 백업: $100/월
- **총: $1,961-2,561/월**

**연간**: $23,532-30,732

**사용자당**: $0.00025 (0.25원)

**ElastiCache와 비교**:
- ElastiCache: $3,502/월 (32 노드 개별)
- EC2 관리: $1,161/월
- **약 66% 비용 절감**

### I.6 Failover 안정성

**손실 계산**:
- Master 1대 다운: 1/16 = **6.25%만 손실**
- Cluster 하나 전체 다운: 25% 손실
- 2개 AZ 다운: 50% 손실

**비교**:
- Sentinel: 100% 영향
- 5 Master Cluster: 20% 손실
- 10 Master Cluster: 10% 손실
- **극대 분산 (16 Master): 6.25% 손실** ⭐

### I.7 이중 라우팅 완전 구현

**Cluster Router (Layer 1)**:
```java
public ClusterAssignment selectCluster() {
    Map<String, Double> loads = new HashMap<>();
    loads.put("cluster1", metricsCollector.getLoad(cluster1));
    loads.put("cluster2", metricsCollector.getLoad(cluster2));
    loads.put("cluster3", metricsCollector.getLoad(cluster3));
    loads.put("cluster4", metricsCollector.getLoad(cluster4));
    
    // Least Load 선택
    String selected = loads.entrySet().stream()
        .min(Map.Entry.comparingByValue())
        .map(Map.Entry::getKey)
        .orElseThrow();
    
    return new ClusterAssignment(selected, ...);
}
```

**Shard Router (Layer 2 - Cluster 내 Master 선택)**:
```java
public String selectShard(RedisTemplate<String, String> cluster) {
    List<ShardLoad> loads = metricsCollector.getClusterLoads(cluster);
    
    return loads.stream()
        .min(Comparator.comparing(ShardLoad::getLoadPercent))
        .map(ShardLoad::getShardName)
        .orElseThrow();
}
```

**최종 Key 예시**:
- `queue:{shard_A1}:q_bts_002:waiting`
- Cluster: A (Application 결정)
- Master: 1 (shard_A1 Hash Tag)
- Queue: q_bts_002

### I.8 로컬 실습 (2 Cluster) → 프로덕션 (4 Cluster) 확장

**로컬 실습 (Sprint 8, 완료)**:
- 2 Cluster × 4 Master × 1GB
- 총 8 GB Master
- 처리량 (이론): 320,000 ops/초
- 프로덕션 축소판 (2배 확장 가능)

**프로덕션 목표 (Sprint 15+)**:
- 4 Cluster × 4 Master × 4GB
- 총 64 GB Master
- 처리량 (이론): 640,000 ops/초
- 로컬 대비 8배 저장 (크기 4배 × Cluster 2배)

**확장 자연스러움**:
- 로컬에서 배운 구조 그대로 적용
- 리스크 없이 확장
- Sprint 12에서 Hash Tag 도입
- Sprint 15에서 4 Cluster 완성

### I.9 핵심 통찰

- **통찰 88**: 극대 분산 아키텍처의 완전한 설계 (4x4x4GB)

---

**부록 F-I 작성일**: 2026-07-08 (Sprint 5-D 완료, Cluster 로컬 실습 완료 후)
**부록 F-I 목적**: 오늘 세션 학습 내용 완전 보존, 프로덕션 확장 시 참조 가이드
