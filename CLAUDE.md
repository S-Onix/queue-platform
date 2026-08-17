# Queue Platform — Project Context for Claude Code

> 이 파일은 Claude Code가 세션 시작 시 자동으로 읽는 컨텍스트 파일입니다.
> 자세한 내용은 `doc/` 폴더의 개별 문서를 참조하세요.

---

## 프로젝트 개요

**Queue Platform**: B2B Queue-as-a-Service SaaS  
대규모 트래픽 시 서버 부하를 제어하기 위해 대기열을 외부 플랫폼으로 분리한 서비스.

### 핵심 설계 원칙
1. **Platform**은 순서만 관리, **Tenant**가 슬롯·입장 제어
2. 유저가 Platform에 직접 Polling (`/status`의 `pacing` 구간표 기반 적응형 간격, §79)
   - ⚠️ 현행 **코드**는 아직 `nextPollAfterSec`를 응답에 담는다. §79는 설계 확정·구현 미착수
3. Backpressure Pull (Tenant가 admit으로 받을 수 있는 만큼만)
4. admitToken TTL 만료 → WAITING 복귀 (seq 기반 우선순위 보존)
5. Spring MVC + Virtual Thread (JPA blocking I/O를 OS Thread 고갈 없이)
6. **API 서버는 N대로 수평/수직 확장 가능 (Stateless 전제)**

---

## 기술 스택

```
Language: Java 21 (Virtual Thread, Record, Pattern Matching)
Framework: Spring Boot 3.3.4 + MVC + Tomcat (NOT WebFlux)
ORM: JPA (Hibernate) + JDBC
DB: MySQL 8.0 (Master 3306 + Replica 3307, GTID 복제)
Cache: Redis — 현재 구현 Sentinel (Master + Slave 2 + Sentinel 3)
       목표 확정: 독립 2 Cluster + 큐 단위 이중 라우팅 (DECISIONS §75, 전환 시점 미정)
       Sentinel은 폐기가 아니라 학습·로컬 자산으로 격하 (§75 D28)
Messaging: Spring Kafka (KRaft)
Build: Gradle 멀티모듈 (6개 — common/domain/infrastructure/api/batch/consumer)
Architecture: Hexagonal + DDD
Auth: JWT (Access 15분 + Refresh 7일) + Spring Security
패키지: com.sonix.queue
```

---

## 모듈 구조 (헥사고날)

```
queue-platform/
├── queue-common/          # ErrorCode, BusinessException, IdGenerator, AOP 어노테이션
├── queue-domain/          # Rich Domain Model + Port 인터페이스 (Spring 의존성 없음!)
├── queue-infrastructure/  # JPA Adapter, Redis Adapter, Kafka Adapter, AOP Aspect 구현
├── queue-api/             # REST Controller + Security + JWT
├── queue-batch/           # @Scheduled Jobs (현재 껍데기 — Sprint 7·9에서 채움)
└── queue-consumer/        # Kafka 소비 전담 독립 앱. token-lifecycle → tokens DB 적재
```

**`queue-consumer` (§73 D20 — 근거는 그쪽을 봐라, 여기 중복 서술 금지)**
- `TokenLifecycleConsumer` `@KafkaListener(topics = "${queue.consumer.topic:token-lifecycle}")` → `TokenPersistService` 배치 적재
- `queue-batch`와 **합치지 않는다**: 소비는 파티션 수만큼 늘리고, 스케줄 작업은 늘릴수록 중복 실행 방지가 필요하다 (확장 방향이 반대)
- `@EnableScheduling`을 붙이지 않는다 — 붙이면 infra의 `@Scheduled` 빈까지 돌아 이중 적재
- **actuator + micrometer-registry-prometheus 보유**. 레지스트리 빈이 없으면 `/actuator/prometheus` 엔드포인트 자체가 생기지 않아 **컨슈머 lag을 PromQL로 볼 수단이 사라진다**

### 의존성 방향 (절대 위반 금지)
```
queue-common ← (모든 모듈)
queue-domain ← queue-infrastructure, queue-api, queue-batch, queue-consumer
queue-domain은 Spring 의존성 절대 없음 (순수 Java)
queue-infrastructure는 queue-api/batch/consumer를 모름 (한방향)
queue-consumer는 아무도 참조하지 않는다 (최말단)
```

---

## Sprint 진행 현황

> **일정의 정본은 `doc/ROADMAP.md`다.** 여기엔 "지금 어디인지"만 둔다 — 두 곳에 적으면 갈라진다.
> ⚠️ 단 `ROADMAP.md`는 2026-06-10 최신화라 **이 블록보다 낡았다** (Sprint 6·8을 ⬜로 두고, §73이
> 폐기한 3토픽 체계를 현재형으로 서술 중). 갱신 전까지는 아래 "코드로 확인되는 상태"가 더 정확하다.

```
현재 위치: Sprint 5 마무리 ~ Sprint 6·8 병행.  다음 큰 덩어리 = Sprint 7 (Admit)

코드로 확인되는 상태 (2026-08-17):
  구현됨  Redis Sentinel · Rate Limiter(Lua 2종) · ApiKey 캐시            (Sprint 5)
  구현됨  Token 도메인 + Enqueue(POST /tokens) + Polling(GET /tokens/:id)  (Sprint 6 일부)
  구현됨  Kafka 적재 경로 — token-lifecycle + queue-consumer 모듈          (Sprint 8 일부)
  미착수  Cancel(DELETE /tokens/:id)                                       (Sprint 6 잔여)
  미착수  admit · verify · complete — 컨트롤러 0건                          (Sprint 7)
  미착수  queue-batch는 Application 클래스만 (껍데기)                       (Sprint 9)
  설계만  /status 분할 · admitWatermark · pacing — 코드 0줄                 (§79)
```

### Sprint 5 현재 상태 (2026-07-08)

**5-A 완료**: Redis Sentinel 인프라
- Master 6379 + Slave 6380, 6381 + Sentinel 26379, 26380, 26381
- Failover 실증 완료 (5~10초 내)
- CONFIG REWRITE 자동 동작 확인
- LettuceConnectionFactory + StringRedisTemplate
- application*.yml Sentinel 연결 정보

**5-B 완료**: 모니터링 시스템
- Prometheus 3.0.1 + Grafana (WSL2 직접 설치)
- /actuator/prometheus 노출
- MONITORING_DESIGN.md 4개 카테고리

**5-C 완료**: Rate Limiter (Token Bucket + Fixed Window)
- 알고리즘 분리 적용 (DECISIONS §60, §61)
    - Tenant SLA (인증 후) → Token Bucket (`rl:tenant:{id}`)
    - 인증 전 (signup/login/refresh) → Fixed Window (`rl:{action}:ip:{ip}`)
- RateLimiter / FixedWindowRateLimiter 도메인 포트
- Redis Lua Script 2개 (token-bucket.lua, fixed-window.lua)
- Tenant Plan 도입 (FREE/STARTER/PRO/ENTERPRISE, DECISIONS §62)
- RateLimitFilter HTTP 통합 (JwtAuthFilter 후)
- PublicEndpointRateLimit (SIGNUP 5/분, LOGIN 10/분, REFRESH 30/분)
- ErrorCode RL_001 (HTTP 429 + Retry-After 헤더)
- 동시성 검증 (1,000 동시 요청 → 정확히 capacity개)

**5-D 완료** (2026-07-08): Redis 캐시 적용
- ApiKey Redis 캐시 (`apikey:{keyHash}`, TTL 60s)
- 캐시 히트율 로그
- Facade 도입 → Anti-pattern 인식 후 롤백 (중요 학습 자산)
- Step 1 롤백 완료, Step 2-5 캐시 인프라 완료
- dev 브랜치 merge 완료

**5-E 진행 중** (Phase A~E 완료, 2026-07-15):
- Phase A ✅: QueueEngine Port + EnqueueResult Value Object
- Phase B ✅: enqueue_bulk.lua + Bean 등록 (**하이브리드 폐기 → Bulk 단독**)
- Phase C ✅: PendingEnqueue + RedisQueueEngine(Global Queue) + BatchProcessor
- Phase D ✅: QueueEngineService + QueueEngineController + ApiKeyAuthenticationFilter
- Phase E ✅: 검증 완료 — 전체 160건 통과
  - 1,000 동시 Enqueue 순번 0~999 유일 (실제 Redis)
  - WAS 3대 분산 10,000건 → 5개 큐에 2,000씩, 순번 중복 0
  - 로컬 Cluster A에서 `enqueue_bulk.lua` 실행 검증 (해시태그)
- Phase F ✅: 커밋 완료

**5-E 확정 결정** (DECISIONS §66-70 참조):
- D1: 자유 identifier (Tenant 제공)
- D2: ZSet 하나 (`queue:{queueId}:waiting`)
- D3: ZRANK + ZCARD
- D4: Java + Lua 분리
- D5: Lua ZRANK 중복 방지
- D6: Lua ZCARD Capacity
- ~~D7: enqueue.lua + enqueue_bulk.lua~~ → **`enqueue_bulk.lua` 단독** (§70)
- ~~D8: 하이브리드 (임계값 1000 req/s, 배치 100, 간격 10ms, 타임아웃 1s)~~ → **하이브리드 폐기** (§70)
  - 현재 상수: `MAX_DRAIN=5000`, `CHUNK_SIZE=500`, `fixedRate=1000ms`, 타임아웃 30s
  - ⚠️ 원안(10ms/1s) 대비 100배/30배 이탈 → **재조정 후속 과제** (단건 경로가 사라져 저부하 요청도 평균 500ms 부담)
- **D9: score = `INCR queue:{queueId}:seq`** (신설) — ZCARD+1/타임스탬프는 충돌·동점 → INCR만 단조증가·유일
- **D10: Hash Tag 필수** (신설) — `enqueue_bulk.lua` 3키(waiting/seq/tokens) · `poll_verify.lua` 3키(waiting/tokens/last-active). 해시태그 없으면 Cluster에서 CROSSSLOT. `queue/QueueKeys.java`에서 관리

**Cluster 로컬 학습 완료** (2026-07-08 - Sprint 8 병행):
- Sentinel 유지 + Cluster A (7001-7008) + Cluster B (8001-8008)
- 4 Master + 4 Replica × 1GB × 2 Cluster = 16 노드
- Failover 실전 검증 완료
- 완전 독립성 확인
- 프로덕션 축소판 (Sprint 15+ 목표 4x4x4GB의 절반)
- 상세: `doc/INFRA_SETUP.md` §6.5

**5-F 진행 예정**: Sprint 5 마무리
- DECISIONS.md 갱신 완료 (§66-69 신규)
- ROADMAP.md 갱신 완료
- CLAUDE.md 갱신 완료 (진행 상황 반영)
- `doc/ARCHITECTURE_ROADMAP.md` 신규 (2588 라인)
---

## 코드 작성 규칙 (반드시 준수)

### 헥사고날 원칙
- ❌ `queue-domain`에 Spring 의존성 추가 금지 (`@Component`, `@Service` 등)
- ❌ `queue-domain`에서 JPA Entity 사용 금지 (Domain Model만)
- ✅ Port는 `queue-domain` 인터페이스, Adapter는 `queue-infrastructure`
- ✅ JPA Entity는 `toDomain()`, `fromDomain()` 팩토리 메서드로 변환
- ✅ AOP 어노테이션은 `queue-common` (인터페이스만), Aspect 구현은 `queue-infrastructure`

### 도메인 모델 (Rich)
- 비즈니스 메서드는 도메인 객체 안에 (예: `Token.expire()`, `Queue.pause()`)
- Setter 지양, 불변 객체 지향
- 정적 팩토리 (`create()`, `reconstruct()`)로 생성 제어

### 테스트 필수
- Domain 단위 테스트 (Spring 없음, JUnit 5만)
- Service 단위 테스트 (Mockito)
- Controller 통합 테스트 (MockMvc)
- Repository는 Adapter 통합 테스트
- **동시성 테스트의 스레드는 Virtual Thread 사용** (`Executors.newVirtualThreadPerTaskExecutor()`)
   - 고정 크기 풀(`newFixedThreadPool(N)`)은 작업 수 > N이고 각 작업이 출발 신호(`start.await()`)에서
     블록되면, N개만 점유되고 나머지는 큐에서 굶어 `ready.await()`가 0에 못 닿아 **교착**된다.
   - 요청 1건당 가상 스레드 1개면 OS 스레드 고갈 없이 전부 동시에 출발 → 진짜 경쟁 상황 재현.
   - 운영 코드도 Virtual Thread 전제(`spring.threads.virtual.enabled=true`)이므로 테스트도 동일 모델 유지.

### 의존성 주입
- 생성자 주입 (Lombok `@RequiredArgsConstructor`)
- 필드 주입 금지
- Setter 주입 금지

### 동시성 제어
- **단일 JVM 한정 도구 금지** (동시성 제어 목적):
   - ❌ `synchronized`, `ReentrantLock`을 락 대용으로 사용
   - ❌ `ConcurrentHashMap`을 분산 상태 저장 용도로 사용
   - ❌ `static` 필드에 상태 저장
- **동시성 문제 발생 시 우선순위 (위에서부터 시도)**:
   1. DB 제약조건 (UNIQUE, FK, CHECK)
   2. Redis 단일 키 원자 연산 (`SETNX`, `INCR`, Lua Script)
   3. Kafka partition 순서 보장 (같은 key는 같은 partition)
   4. DB 비관적 락 (`SELECT ... FOR UPDATE`) — 짧은 트랜잭션 한정
   5. `@DistributedLock` (Redisson 기반) — 위로 안 될 때만
- **`@DistributedLock` 사용 시**:
   - Key 형식: `lock:{domain}:{id}:{action}` (예: `lock:tenant:{tenantId}:queue-create`)
   - 전역 락 금지, 항상 tenant/queue 단위 이하로 좁힘
   - 락은 **반드시 `@Transactional`보다 바깥** (Aspect `@Order(HIGHEST_PRECEDENCE)`)
   - 부수 작업(Redis 초기화, Kafka 발행)은 `@TransactionalEventListener(AFTER_COMMIT)`
- **스케줄러**: `@Scheduled` 단독 금지, leader election 필요 (ShedLock 또는 분산 락)
- **세션/상태**: 메모리 저장 금지, Redis 또는 stateless JWT
- 상세: `doc/CONCURRENCY.md`

### 트랜잭션
- `@Transactional`은 Service 계층에만
- 읽기 전용은 `@Transactional(readOnly = true)` → Replica 자동 라우팅
- Domain Model에 트랜잭션 노출 금지

### Git
- 브랜치: `feat/범위` (예: `feat/redis-sentinel`)
- 커밋: Conventional Commits (한국어 제목 OK, subject-case rule disabled)
- feature → dev → master (PR 기반)

---

## 핵심 설계 결정 (자주 잊지 말 것)

1. **WebFlux X, Spring MVC + Virtual Thread O**
   - JPA + ThreadLocal + Reactor scheduler 충돌 회피
   - `spring.threads.virtual.enabled=true` 한 줄로 적용

2. **R2DBC 폐기, JPA 채택**
   - JPA blocking I/O는 Virtual Thread가 OS Thread 점유 없이 처리

3. **admitToken TTL 60s + DB Fallback**
   - Redis 만료 시 DB에서 admit_token 컬럼으로 복구
   - WAITING 복귀 시 seq 컬럼으로 score 복원 (EXPIRED 아님)

4. **Status는 TINYINT (0~4)**
   - VARCHAR 대비 저장공간·비교 성능 최적화
   - 0=WAITING, 1=ADMIT_ISSUED, 2=COMPLETED, 3=CANCELLED, 4=EXPIRED

5. **Kafka 비동기 처리**
   - Enqueue: Redis Lua(순번 확정) → **Kafka 발행(동기, ack 대기)** → **200 응답** → Consumer가 DB INSERT (At-Least-Once)
     - ⚠️ 발행이 응답보다 **먼저**다. 실패하면 QE001(503)이고 200이 안 나간다 — "200 먼저, Kafka는 뒤에서"가 아니다
     - 비동기인 것은 **DB 적재뿐**. 발행 대기는 응답 지연에 포함된다
   - 토픽은 **`token-lifecycle` 하나**, 파티션 키는 **`tokenId`** (§73 D16·D18)
     - `queueId` 키는 기각 — 한 큐 30만이면 99%가 한 파티션

6. **tokens 파티셔닝 (Range, 월별)**
   - `YEAR(issued_at) * 100 + MONTH(issued_at)`
   - 파티션 1달 유예 DROP (월말 걸친 토큰 과금 누락 방지)

7. **RedisKeyFactory: static 메서드 방식**
   - Enum 아님 (가변인수 타입 안전성 위해)

8. **JWT 분리**
   - Access (15분, type=ACCESS, stateless)
   - Refresh (7일, type=REFRESH, DB 저장 + Redis 캐시)
   - Token Rotation + 재사용 감지

9. **동시성 제어 우선순위: DB 제약 > Redis 원자연산 > Kafka 순서 > DB 비관적 락 > 분산 락**
   - 핫패스(enqueue/admit): Redis Lua Script로 락 회피
   - 콜드패스(createQueue 등 관리성): DB 비관적 락 또는 `@DistributedLock`
   - 표준 분산 락 어노테이션은 없음 → 사내 `@DistributedLock` (Redisson + AOP)
   - 어노테이션은 `queue-common`, Aspect는 `queue-infrastructure`

10. **Redis 목표 구성: 독립 2 Cluster + 큐 단위 이중 라우팅** (DECISIONS §75, 시점 미정)
   - 한 큐의 키 4종(`waiting`/`seq`/`tokens`/`last-active`)은 **같은 클러스터**에 놓인다 (§75 D26)
   - 새 큐 상태 키는 반드시 `QueueKeys`를 거칠 것 — 태그 없는 키를 다중 키 Lua의 KEYS에 끼우면
     Cluster에서만 `CROSSSLOT`으로 깨진다. **로컬 Sentinel 테스트로는 안 잡힌다**
   - Sentinel은 학습·로컬 자산으로 격하 (폐기 아님, §75 D28)

---

---

## Claude Code 점검 가이드

새 클래스 작성 후 다음 항목들로 점검 요청:

### 헥사고날 점검
- 모듈 위치 (queue-domain은 Spring 의존성 없는지)
- Port-Adapter 관계 명확한지

### Spring/JPA 점검
- 어노테이션 적절성
- 메서드명 규칙 (Spring Data JPA)
- @Query JPQL 문법

### 컨벤션 일치
- 기존 코드와 스타일 일관성
- 패키지 위치 패턴 일치
- 네이밍 일관성

---

## 진행 중인 작업 / 알려진 이슈

### Sprint 4 빈틈 (Sprint 5에서 보강 완료 / 진행 중)
- **Refresh Token 저장 로직 미구현**: Sprint 5-D 이후 결정 (Redis 캐시 완료 후 Sprint 6 이전 검토)
- ~~**ApiResponse 위치**: queue-common에 있으나 queue-api로 이동 검토 중~~ → Sprint 5 진입 시 `com.sonix.queue.api.common.response`로 이동 완료 (Batch가 사용 안 함)

### Sprint 5에서 함께 구현할 것
- ✅ Rate Limiter (Token Bucket + Fixed Window 분리 적용)
- ✅ Tenant Plan 도입 (SaaS 등급)
- ✅ HTTP Filter 통합 (429 + Retry-After)
- ✅ API Key 캐시 (`apikey:{keyHash}`, TTL 60s) (5-D)
- ✅ Queue Engine Lua Script (enqueue_bulk.lua 단독 + Hash Tag, 5-E / DECISIONS §70)
- ⬜ Refresh Token 도메인 모델 + DB 저장 + Redis 캐시 (5-E 이후)

### Cluster 로컬 학습 완료 자산 (2026-07-08)
- Sentinel + Cluster A + Cluster B 병행 실행 (WSL2)
- 총 22 Redis 프로세스
- Failover 실전 검증
- 프로덕션 확장 시 자연스러운 upgrade path
- 상세: `doc/INFRA_SETUP.md` §6.5
---

## 에이전트 협업 규칙 (2026-08-11 확정)

### 1. 최종 커밋 권한은 `lead`에게 있다
어떤 에이전트도 **단독으로 커밋을 확정하지 않는다.** 작업이 끝나면 반드시 `lead`(총괄책임자)의
판정을 받아야 하며, 판정은 셋 중 하나다.

| 판정 | 의미 |
|---|---|
| **진행 가능** | 그대로 커밋 |
| **조건부 진행** | 명시된 조건을 먼저 해소한 뒤 커밋 |
| **보류** | 사유가 해소되기 전까지 커밋하지 않는다 |

`lead`는 보고서를 그대로 믿지 않고 **`git diff`로 직접 대조**한다. 보고와 diff가 다르면 그 사실을
가장 먼저 보고한다. (실제로 tester가 "207 tests"라고 보고했으나 실측 202였던 전례가 있다)

### 2. 다른 에이전트의 동의 없이는 진행하지 않는다
`teacher`를 제외한 모든 에이전트는 **자기 판단만으로 결론을 확정할 수 없다.**
최소 한 명 이상의 다른 에이전트가 검토하고 동의해야 다음 단계로 넘어간다.

- **구현(backend/dba/frontend/infra)** → `code-reviewer` + `tester`의 동의
- **판정·설계(security/architect/planner/monitoring)** → 이해관계가 있는 다른 에이전트의 동의
- **동의는 형식이 아니다.** 반대하면 근거를 대고 반대하라. 앞선 작업에서 `security`가
  코디네이터의 가설을 실증으로 뒤집은 것, `code-reviewer`가 `poll_verify.lua` 주석의 근거 오류를
  잡아낸 것이 이 규칙이 노리는 바다
- 동의를 못 얻으면 **양쪽 입장을 함께 `lead`에게 올린다.** 어느 쪽이 맞는지는 `lead`가 판정한다

**`teacher`만 예외**다. 산출물이 `doc/blog/`(로컬 전용, 커밋 대상 아님)이라 다른 작업에 영향을 주지
않는다. 다만 `teacher`도 **사실 인용은 코드·문서 원문을 확인**해야 하며, 미검증은 "미검증"으로 표기한다.

### 3. 보고에 반드시 포함할 것
어느 에이전트든 보고에는 **목적 → 대안 → 선택 이유 → 검증 결과**가 있어야 한다.
검증하지 않은 것은 "미검증"이라고 정확히 쓴다. **안 돌린 명령을 돌렸다고 쓰지 않는다.**

### 4. 과설계를 하지 않는다 (2026-08-17 확정)

방어 장치·추상화·새 키·새 엔드포인트·새 문서를 **늘리는** 제안은 "안 만들면 무엇이 깨지는가"를
반드시 함께 낸다. **안 깨지면 만들지 않는다.** 검토는 결함을 **보고**하는 자리지 대응책을
**확정**하는 자리가 아니다.

**심각한 문제가 발생할 수 있는 경우에만 확대한다.** 그때는 **최소 3개 에이전트와 상의**하고,
필요 여부의 **최종 판단은 사용자가 한다.** 어떤 에이전트도 대응책을 단독으로 확정하지 않으며,
`lead`도 예외가 아니다 — **lead의 판정도 사용자 승인 전까지는 제안이다.**

> 계기: PR #26 검토에서 `lead`가 확정한 `/status` IP Rate Limit은, 캐시 키에서 쿼리스트링만
> 제외하면 오리진 부하가 `1÷TTL`로 상수가 되어 **애초에 필요 없는** 장치였다.

이 규칙은 `.claude/agents/*.md` 14개 전부에도 "절대 규칙 (모든 에이전트 공통)" 절로 들어가 있다.
다만 `.claude/`는 `.gitignore` 대상이라 **에이전트 정의는 로컬 전용**이다 — 이 문서가 정본이고,
새 머신·새 참여자는 여기를 보고 에이전트 정의에 같은 절을 넣어야 한다.

---

## Claude Code 사용 지침

### 코드 작성은 사용자가 직접 수행
**Claude의 역할은 검토·분석·제안.** 자동 코드 생성은 명시적 요청 시에만.

### 검토 시 확인할 것
- 헥사고날 의존성 방향 위반 여부
- 도메인 모델 Rich vs Anemic
- 테스트 누락
- 트랜잭션 경계
- Spring 의존성이 queue-domain에 들어갔는지
- **단일 JVM 한정 동시성 도구가 분산 환경 가정을 깨지는 않는지**

### 학습 가이드 모드
- 본인이 어떤 개념을 학습 중이면 (예: Lua Script, Redis Sentinel)
- 힌트 우선, 정답 후 (직접 시도 → 비교)
- 한국어 응답 선호

### 답변 스타일
- 한국어로 응답
- 트레이드오프 분석 포함
- 면접 답변 형식 제공 시 환영
- 비유 (특히 유치원생 수준 비유) 환영

---

## 자주 쓰는 명령

```bash
# Redis Sentinel 클러스터 관리 (~/.bashrc 함수)
redis_start    # 6개 프로세스 일괄 기동
redis_stop     # 종료
redis_status   # 상태 확인
redis_logs master         # Master 로그 실시간
redis_logs sentinel-1     # Sentinel 로그 실시간

# Redis Cluster A/B (Sprint 8+ 학습 환경, doc/INFRA_SETUP.md §6.5)
sudo systemctl start redis-cluster-a-{1..8}    # Cluster A 8 노드 시작
sudo systemctl start redis-cluster-b-{1..8}    # Cluster B 8 노드 시작
redis-cli -c -p 7001 cluster info              # Cluster A 상태
redis-cli -c -p 8001 cluster info              # Cluster B 상태
redis-cli -c -p 7001 cluster nodes             # Cluster A 노드 목록

# Gradle
./gradlew build
./gradlew :queue-api:bootRun
./gradlew test

# MySQL
mysql -u root -p -P 3306  # Master
mysql -u root -p -P 3307  # Replica
```

---

## 참조 문서

상세 정보는 다음 문서를 직접 읽으세요:

| 문서 | 내용 |
|------|------|
| `doc/ROADMAP.md` | 11개 Sprint 상세 일정 + DoD |
| `doc/FRS_final.md` | 기능 요구사항, API 명세, Redis Key, Kafka 토픽 |
| `doc/DECISIONS.md` | 79개 설계 결정 + 근거 + 면접 포인트 (최신 §79 — 폴링 응답 계약) |
| `doc/monitoring/` | 운영 런북 + PromQL 쿼리 (⚠️ **현행 코드 동작** 서술 — §79 구현 전까지 고치지 말 것) |
| `doc/reviews/` | 에이전트 교차 검토 기록 (후속 과제 목록 포함) |
| `doc/FLOW.md` | Enqueue, Polling, Admit, Complete, Batch 흐름도 |
| `doc/STATE.md` | Token, Queue, ApiKey 상태 머신 |
| `doc/schema.sql` | MySQL DDL + 파티션 운영 쿼리 |
| `doc/CONCURRENCY.md` | 동시성 제어 전략, `@DistributedLock` 사용법, 확장성 전제 |
| `doc/INFRA_SETUP.md` | WSL2 인프라 설치 가이드 (Sentinel + Cluster 로컬 실습 포함, §6.5) |
| `doc/ARCHITECTURE_ROADMAP.md` | ⭐ **아키텍처 진화 로드맵 (Phase 0-4 + 부록 A-I)** |
| `doc/sprint-5/REDIS_SENTINEL.md` | Sprint 5 Phase 1 학습 노트 (Sentinel) |
| `doc/sprint-5/LUA_SCRIPTS.md` | Sprint 5 Phase 2 학습 노트 (Lua 3종) |
| `doc/sprint-5/RATE_LIMITER.md` | Rate Limiter 설계 통합 |

---

## 작업 시작 시 권장 프로세스

1. **현재 Sprint 상태 확인**: 위 "Sprint 진행 현황" 섹션 참고
2. **관련 문서 읽기**: 작업 영역에 따라 `doc/` 참조
3. **헥사고날 위반 가능성 자가 진단**: 새 클래스가 어느 모듈에 속하는지 명확히
4. **확장성 자가 진단**: 단일 JVM 가정에 기댄 코드가 아닌지 (특히 동시성/상태)
5. **테스트 먼저 또는 동시**: TDD 강제 아니지만, 테스트 누락 금지
# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.
