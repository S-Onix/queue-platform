# Queue Platform — Project Context for Claude Code

> 이 파일은 Claude Code가 세션 시작 시 자동으로 읽는 컨텍스트 파일입니다.
> 자세한 내용은 `docs/` 폴더의 개별 문서를 참조하세요.

---

## 프로젝트 개요

**Queue Platform**: B2B Queue-as-a-Service SaaS  
대규모 트래픽 시 서버 부하를 제어하기 위해 대기열을 외부 플랫폼으로 분리한 서비스.

### 핵심 설계 원칙
1. **Platform**은 순서만 관리, **Tenant**가 슬롯·입장 제어
2. 유저가 Platform에 직접 Polling (적응형 간격 nextPollAfterSec)
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
Cache: Redis Sentinel (Master + Slave 2 + Sentinel 3)
Messaging: Spring Kafka (KRaft)
Build: Gradle 멀티모듈 (5개)
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
└── queue-batch/           # @Scheduled + Spring Kafka Consumer
```

### 의존성 방향 (절대 위반 금지)
```
queue-common ← (모든 모듈)
queue-domain ← queue-infrastructure, queue-api, queue-batch
queue-domain은 Spring 의존성 절대 없음 (순수 Java)
queue-infrastructure는 queue-api/batch를 모름 (한방향)
```

---

## Sprint 진행 현황

```
✅ Sprint 1 (완료): 멀티모듈 스켈레톤 + MVC + Virtual Thread
✅ Sprint 2 (완료): JPA + MySQL Master/Replica R/W 분리
✅ Sprint 3 (완료): 관리 도메인 (Tenant + ApiKey + Queue) 헥사고날
✅ Sprint 4 (완료): JWT 인증 + 관리 API 12개 + 테스트
🔄 Sprint 5 (진행 중): Redis Sentinel + Lua Script + Rate Limit
⬜ Sprint 6: Token 도메인 + Queue Engine API
⬜ Sprint 7: Admit → Verify → Complete
⬜ Sprint 8: Kafka KRaft 연동
⬜ Sprint 9: Batch 모듈
⬜ Sprint 10: 통합 테스트 + k6 + Grafana + JS SDK
⬜ Sprint 11: Docker + AWS 배포
```

### Sprint 5 현재 상태 (2026-05)
**Phase 1 완료**: Redis Sentinel 인프라 구성 (WSL2)
- Master 6379 + Slave 6380, 6381 + Sentinel 26379, 26380, 26381
- Failover 실증 완료 (5~10초 내)
- CONFIG REWRITE 자동 동작 확인

**Phase 2 진행 예정**: Spring Redis 통합
- autoconfigure.exclude에서 Redis 제거
- LettuceConnectionFactory + Sentinel 설정
- RedisKeyFactory (static 메서드)
- Lua Script 3종 (Ranking, Enqueue Bulk, Admit Dequeue)
- RedisPort (queue-domain) + RedisAdapter (queue-infrastructure)

**Phase 3~5 예정**: API Key 캐시, Refresh Token Redis 이중 저장, Rate Limit

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
- 상세: `docs/CONCURRENCY.md`

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
   - Enqueue: Redis Lua → 202 응답 → Kafka → DB INSERT (At-Least-Once)
   - 상태 변경: Kafka token-status-changed 이벤트

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

### Sprint 4 빈틈 (Sprint 5에서 보강 예정)
- **Refresh Token 저장 로직 미구현**: queue-domain/auth 디렉토리에 RefreshToken 도메인, Repository Port, JpaAdapter 추가 필요
- ~~**ApiResponse 위치**: queue-common에 있으나 queue-api로 이동 검토 중~~ → Sprint 5 진입 시 `com.sonix.queue.api.common.response`로 이동 완료 (Batch가 사용 안 함)

### Sprint 5에서 함께 구현할 것
- Refresh Token 도메인 모델 + DB 저장 + Redis 캐시
- Lua Script 3종 (Ranking, Enqueue Bulk, Admit Dequeue 골격)
- Rate Limit (per-key 100 rps)
- API Key 캐시 (apikey-cache:{sha256}, TTL 60s)

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
| `doc/DECISIONS.md` | 56+ 설계 결정 + 근거 + 면접 포인트 |
| `doc/FLOW.md` | Enqueue, Polling, Admit, Complete, Batch 흐름도 |
| `doc/STATE.md` | Token, Queue, ApiKey 상태 머신 |
| `doc/schema.sql` | MySQL DDL + 파티션 운영 쿼리 |
| `doc/CONCURRENCY.md` | 동시성 제어 전략, `@DistributedLock` 사용법, 확장성 전제 |
| `doc/sprint-5/REDIS_SENTINEL.md` | Sprint 5 Phase 1 학습 노트 (Sentinel) |
| `doc/sprint-5/LUA_SCRIPTS.md` | Sprint 5 Phase 2 학습 노트 (Lua 3종) |

---

## 작업 시작 시 권장 프로세스

1. **현재 Sprint 상태 확인**: 위 "Sprint 진행 현황" 섹션 참고
2. **관련 문서 읽기**: 작업 영역에 따라 `docs/` 참조
3. **헥사고날 위반 가능성 자가 진단**: 새 클래스가 어느 모듈에 속하는지 명확히
4. **확장성 자가 진단**: 단일 JVM 가정에 기댄 코드가 아닌지 (특히 동시성/상태)
5. **테스트 먼저 또는 동시**: TDD 강제 아니지만, 테스트 누락 금지