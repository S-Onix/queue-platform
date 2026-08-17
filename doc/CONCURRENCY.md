# 동시성 제어 (Concurrency Control)

> Queue Platform의 동시성 제어 전략과 `@DistributedLock` 사용 가이드.
> CLAUDE.md "동시성 제어" 섹션의 상세 문서.
> **최종 업데이트**: 2026-07-08 (Cluster 학습 반영, Sprint 8+ 대비)

---

## 1. 확장성 전제

본 프로젝트의 API 서버는 다음을 만족하도록 설계한다.

- **수평 확장 (Horizontal Scaling)**: 인스턴스 N개를 추가하는 것만으로 처리량 증가
- **수직 확장 (Vertical Scaling)**: 인스턴스 spec 증가로 처리량 증가
- **Stateless**: 어떤 요청도 특정 인스턴스에 묶이지 않음

### Redis 배포 방식별 확장성 (Sprint 5-D → Sprint 10+)

| 배포 방식 | 저장 확장 | 처리량 확장 | 도입 Sprint |
|-----------|-----------|-------------|-------------|
| Sentinel | Scale-Up만 (Master 메모리) | Single Thread 한계 (40k ops/s) | Sprint 5-A (완료) |
| Cluster (3 Master) | Scale-Out (Master 추가) | 3배 (120k ops/s) | ~~Sprint 10~~ **미정** |
| Cluster (5-7 Master) | 유연한 확장 | 5-7배 (200-280k ops/s) | ~~Sprint 12~~ **미정** |
| 4x4x4GB 극대 분산 | 최대 유연성 | 16배 (640k ops/s) | ~~Sprint 15+~~ **미정** |

**핵심**: Redis Cluster 도입 시 Redis 원자 연산의 원리는 동일 (각 Master가 Single Thread), 단 여러 Master가 병렬 처리.

> ⚠️ **개정 (§75, 2026-08-11)**: Cluster 전환은 **확정**되었으나 **시점은 미정**이다(위 Sprint 번호는 확정 아님).
> 또한 목표는 **단일 Cluster의 Master 추가 확장이 아니라 독립 2 Cluster + 큐 단위 이중 라우팅**이다.
> §70 D10의 해시태그 때문에 한 큐는 Master 한 대에 고정되므로, 위 "3배/16배"는 **큐가 여러 개일 때만** 성립한다.
> 최악 케이스인 **단일 큐 30만 대기**에는 적용되지 않는다. → §75

### 이 전제가 의미하는 것

| 항목 | 허용 | 금지 |
|------|------|------|
| 세션 저장 | Redis, JWT(stateless) | JVM 메모리, HttpSession |
| 동시성 제어 | DB, Redis, Kafka | `synchronized`, `ReentrantLock` (락 대용) |
| 캐시 | Redis | JVM 로컬 캐시 (읽기 전용 정적 데이터 제외) |
| 스케줄러 | Leader election 필수 | `@Scheduled` 단독 사용 |
| ID 생성 | DB AUTO_INCREMENT, Snowflake | JVM 카운터 |
| 파일 저장 | S3 등 오브젝트 스토리지 | 로컬 디스크 |

### 단일 JVM 도구의 허용 범위

`synchronized`, `ReentrantLock`, `ConcurrentHashMap` 자체를 금지하는 건 아니다.  
**"동시성 제어 목적의 락 대용"**으로 쓰는 것만 금지한다.

```java
// ✅ OK — JVM 내부 자료구조 보호
private final ConcurrentHashMap<String, ScriptDigest> scriptCache = new ConcurrentHashMap<>();

// ❌ Not OK — 분산 환경에서 동시성 제어 시도
private static final Object createQueueLock = new Object();
public synchronized Queue createQueue(...) { ... }  // 인스턴스 #2가 무력화
```

---

## 2. 동시성 문제 의사결정 트리

동시성 문제가 보이면 **위에서부터 차례로** 검토한다. 위 단계로 해결되면 아래 단계는 도입하지 않는다.

### 1단계: DB 제약조건

`UNIQUE`, `FK`, `CHECK` 제약으로 풀 수 있는가?

```sql
-- 동일 tenant 내 queueName 중복 방지
ALTER TABLE queue ADD CONSTRAINT uk_tenant_queue_name UNIQUE (tenant_id, queue_name);
```

```java
try {
    queueRepository.save(queue);
} catch (DataIntegrityViolationException e) {
    throw new QueueAlreadyExistsException(tenantId, queueName);
}
```

**장점**: 락 없음, 가장 단순, DB가 자연스럽게 직렬화  
**언제 한계**: 단일 row 제약으로 표현 불가능한 규칙 (예: "tenant당 queue 최대 5개")

### 2단계: Redis 단일 키 원자 연산

`SETNX`, `INCR`, `ZADD NX`, Lua Script로 풀 수 있는가?

```lua
-- 예: enqueue (Sorted Set에 추가, 중복 방지)
local exists = redis.call('ZSCORE', KEYS[1], ARGV[1])
if exists then return 0 end
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
return 1
```

**장점**: Redis 단일 스레드 모델 → Lua 안의 명령은 모두 원자적, 처리량 매우 높음  
**언제 한계**: DB 트랜잭션과 결합된 검증이 필요할 때

### 3단계: Kafka partition 순서 보장

**순서를 지켜야 하는 그 entity 자신**을 key로 잡는다. 이 프로젝트에서는 `tokenId`다.

```java
kafkaTemplate.send(topic, event.tokenId(), event);
//                        ↑ partition key — 순서 단위와 같아야 한다
```

같은 tokenId의 이벤트는 같은 partition → 그룹 안에서 한 consumer가 독점 → `WAITING → ADMIT_ISSUED → COMPLETED` 순서 보장.

> ⚠️ **key를 상위 묶음(`queueId`)으로 잡지 마라.** 순서는 지켜지지만 **분산이 죽는다** —
> 큐 카디널리티가 낮고 "한 큐에 30만 명"이 정상 시나리오라 트래픽 99%가 한 파티션에 몰리고,
> 파티션을 늘려도 해결되지 않는다. 실제로 이 프로젝트는 `queueId` 키를 **기각**했다 (DECISIONS §73 D16).
> key 선택의 기준은 "묶고 싶은 단위"가 아니라 **"순서가 필요한 최소 단위"**다.
>
> 토픽도 마찬가지다. 순서 보장은 **같은 토픽의 같은 파티션** 안에서만 성립하므로,
> 한 entity의 생명주기를 여러 토픽으로 쪼개면 key가 같아도 순서가 깨진다 (§73 D18 — 단일 `token-lifecycle`).

**장점**: 비동기 흐름 안에서 동시성 자연 해결  
**언제 한계**: 동기 응답이 필요한 경우 / 리밸런스 재처리·파티션 증설에서는 순서가 깨진다 (§73)

### 4단계: DB 비관적 락

`SELECT ... FOR UPDATE`로 row를 잠근다.

```java
@Transactional
public Queue createQueue(Long tenantId, CreateQueueCommand cmd) {
    Tenant tenant = tenantRepository.findByIdForUpdate(tenantId).orElseThrow();
    long count = queueRepository.countByTenantId(tenantId);
    if (count >= tenant.getPlan().maxQueues()) {
        throw new QuotaExceededException();
    }
    return queueRepository.save(Queue.create(tenantId, cmd));
}
```

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT t FROM Tenant t WHERE t.id = :id")
Optional<Tenant> findByIdForUpdate(@Param("id") Long id);
```

**장점**:
- DB만 있으면 됨, 추가 인프라 불필요
- 트랜잭션 커밋/롤백과 락 해제가 자동 결합
- API 서버 장애 시 DB 커넥션 종료 → 락 자동 해제

**언제 한계**:
- 트랜잭션이 길어지면 락 점유 시간 증가 → 처리량 저하
- DB row가 없는 자원(외부 시스템, 추상 작업)은 보호 불가
- DB 부하 집중

**적용 기준**: 짧은 트랜잭션, DB row만 보호하면 충분한 경우

### 5단계: 분산 락 (`@DistributedLock`)

위 1~4로 해결 안 될 때만 도입한다.

**적용 기준**:
- 외부 시스템(Redis, Kafka, 외부 API) 호출 포함 → 트랜잭션 안에 두기 부적절
- 보호 대상이 DB row가 아닌 추상적 작업 (예: "캐시 갱신 단일 실행")
- 여러 도메인 entity에 걸친 검증 (단일 row 락으로 불충분)

---

## 3. 비관적 락 vs 분산 락 비교

| 항목 | 비관적 락 | 분산 락 |
|------|---------|--------|
| **락 저장소** | DB (보호 대상과 동일) | 외부 시스템 (Redis 등) |
| **락 단위** | DB row (PK 기반) | 임의의 문자열 키 |
| **트랜잭션 결합** | 강결합 (커밋/롤백으로 해제) | 분리 (수동 해제 또는 TTL) |
| **장애 시 해제** | DB 커넥션 종료 → 자동 해제 | TTL 만료까지 점유 |
| **인프라 의존성** | DB만 있으면 됨 | Redis 추가 필요 |
| **성능 부하** | DB에 집중 | DB와 분리 |
| **외부 자원 보호** | 불가 (DB row만) | 가능 (DB+Redis+Kafka) |
| **fencing** | 트랜잭션이 자연스럽게 보장 | 별도 메커니즘 필요 |

### 비유

**비관적 락 = 도서관 책에 자물쇠**: 책 자체에 직접. 책 외 자원은 보호 못 함.  
**분산 락 = 도서관 입구에 자물쇠**: 입구 통과 후 도서관 내부는 자유롭게.

### Queue Platform 적용 매트릭스

| 시나리오 | 권장 방식 | 이유 |
|---------|---------|------|
| createQueue (quota 검증) | 비관적 락 | DB만 건드림, 짧은 트랜잭션 |
| Queue 생성 + Redis/Kafka 초기화 | 분산 락 | 외부 시스템 포함 |
| ApiKey rotate | 분산 락 | 캐시 무효화 등 외부 영향 |
| 일별 통계 집계 (스케줄러) | ShedLock | leader election 성격 |
| enqueue (핫패스) | 락 없음 (Lua Script) | 처리량 우선 |
| admit (핫패스) | 락 없음 (Lua Script) | 처리량 우선 |
| 파티션 DROP | 분산 락 | 관리 작업, 단일 실행 보장 |

---

## 4. `@DistributedLock` 사양

### 4.1 모듈 배치

- **`queue-common`**: `@DistributedLock` 어노테이션 정의 (pure Java, Redisson 의존성 없음)
- **`queue-infrastructure`**: `DistributedLockAspect` 구현 (Redisson 의존)
- 도메인/애플리케이션 레이어는 어노테이션만 import

### 4.2 어노테이션 정의 (queue-common)

```java
package com.sonix.queue.common.lock;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {
    /** SpEL 표현식. 메서드 파라미터 참조 가능 (예: "#tenantId") */
    String key();
    
    /** 락 획득 대기 시간 (기본 3초) */
    long waitTime() default 3L;
    
    /** 락 보유 시간 (기본 10초). Redisson watchdog 미사용 전제 */
    long leaseTime() default 10L;
    
    /** 시간 단위 */
    TimeUnit timeUnit() default TimeUnit.SECONDS;
}
```

### 4.3 Aspect 구현 (queue-infrastructure)

```java
package com.sonix.queue.infrastructure.lock;

@Aspect
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)  // ★ @Transactional보다 바깥
public class DistributedLockAspect {
    private final RedissonClient redissonClient;
    private final SpelExpressionParser parser = new SpelExpressionParser();
    
    @Around("@annotation(distributedLock)")
    public Object lock(ProceedingJoinPoint pjp, DistributedLock distributedLock) throws Throwable {
        String key = parseKey(pjp, distributedLock.key());
        RLock lock = redissonClient.getLock(key);
        
        boolean acquired = false;
        try {
            acquired = lock.tryLock(
                distributedLock.waitTime(),
                distributedLock.leaseTime(),
                distributedLock.timeUnit()
            );
            if (!acquired) {
                throw new BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED, key);
            }
            return pjp.proceed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.LOCK_INTERRUPTED, e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
    
    private String parseKey(ProceedingJoinPoint pjp, String expression) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String[] paramNames = sig.getParameterNames();
        Object[] args = pjp.getArgs();
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        for (int i = 0; i < paramNames.length; i++) {
            ctx.setVariable(paramNames[i], args[i]);
        }
        return parser.parseExpression(expression).getValue(ctx, String.class);
    }
}
```

### 4.4 키 명명 규칙

**형식**: `lock:{domain}:{identifier}:{action}`

예시:
- `lock:tenant:{tenantId}:queue-create`
- `lock:tenant:{tenantId}:apikey-rotate`
- `lock:queue:{queueId}:partition-drop`
- `lock:tenant:{tenantId}:plan-change`

**규칙**:
- Tenant 단위 이하로 좁힘 (전역 락 금지)
- 다른 tenant 작업이 서로 영향 주지 않도록 격리
- 콜론 구분, 소문자 + 하이픈

### 4.5 타이밍 파라미터 기본값

| 파라미터 | 기본값 | 가이드 |
|---------|-------|--------|
| `waitTime` | 3초 | UX와 빠른 실패의 균형. 0이면 fail-fast |
| `leaseTime` | 10초 | 트랜잭션 최대 예상 시간의 2~3배 |
| `timeUnit` | SECONDS | 명시적으로 표기 |

**외부 시스템 호출 포함 시**: leaseTime은 호출 timeout의 2배 이상으로 설정.

### 4.6 트랜잭션과의 순서 (★중요)

**락은 반드시 `@Transactional`보다 바깥에 있어야 한다.**

#### 잘못된 순서가 만드는 문제

```
잘못된 순서:
1. 트랜잭션 시작
2. 락 획득
3. INSERT
4. 락 해제   ← 이 시점에 다음 요청 진입
5. 트랜잭션 커밋  ← INSERT가 아직 다른 트랜잭션에서 안 보임

→ 다음 요청이 락을 잡았을 때 이전 INSERT를 못 봐서 중복 생성
```

#### 보장하는 방법 2가지

**방법 1**: Aspect에 `@Order(Ordered.HIGHEST_PRECEDENCE)` 명시 (위 코드 참고)

**방법 2**: 락 메서드와 트랜잭션 메서드를 별도 빈으로 분리

```java
@Service
@RequiredArgsConstructor
public class QueueCreationService {
    private final QueueTransactionalService txService;
    
    @DistributedLock(key = "'lock:tenant:' + #tenantId + ':queue-create'")
    public Queue createQueue(Long tenantId, CreateQueueCommand cmd) {
        return txService.doCreate(tenantId, cmd);  // 별도 빈 호출
    }
}

@Service
public class QueueTransactionalService {
    @Transactional
    public Queue doCreate(Long tenantId, CreateQueueCommand cmd) { ... }
}
```

방법 2가 더 명시적이고 안전하다. AOP 순서 디버깅 비용 없음.

### 4.7 부수 작업 처리

Redis 초기화, Kafka 발행, 외부 API 호출 등은 **트랜잭션 커밋 이후**에 실행.

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onQueueCreated(QueueCreatedEvent event) {
    redisQueueInitializer.initialize(event.queueId());
    kafkaProducer.publish(event);
}
```

이유:
- 트랜잭션 안에서 외부 호출 → 락 점유 시간 증가
- 외부 호출 실패 시 트랜잭션 롤백 복잡 (Redis는 트랜잭션 못 따라옴)
- 커밋 후 실행이면 "DB는 확실히 반영됨"이 보장됨

---

## 5. Do / Don't

### ✅ Do

```java
// 비관적 락 사용 (DB만 건드리는 경우)
@Transactional
public Queue createQueue(Long tenantId, CreateQueueCommand cmd) {
    Tenant tenant = tenantRepository.findByIdForUpdate(tenantId).orElseThrow();
    // ...
}

// 분산 락 + 트랜잭션 분리 (외부 시스템 포함)
@DistributedLock(key = "'lock:tenant:' + #tenantId + ':queue-create'")
public Queue createQueue(Long tenantId, CreateQueueCommand cmd) {
    Queue q = queueTxService.doCreate(tenantId, cmd);
    // 트랜잭션 커밋 후 외부 호출 (이벤트 리스너로 분리 권장)
    return q;
}

// 핫패스는 Lua Script로 락 회피
public EnqueueResult enqueue(...) {
    return redisAdapter.executeLua(ENQUEUE_SCRIPT, keys, args);
}
```

### ❌ Don't

```java
// 단일 JVM 락으로 분산 환경 동시성 제어 시도
private static final Object lock = new Object();
public Queue createQueue(...) {
    synchronized (lock) { ... }  // 다른 인스턴스가 무력화
}

// 트랜잭션 안에서 분산 락 (순서 역전)
@Transactional
@DistributedLock(key = "...")
public Queue createQueue(...) { ... }

// 전역 락
@DistributedLock(key = "'lock:queue-create-global'")  // 모든 tenant 직렬화

// 트랜잭션 안에서 외부 호출
@Transactional
public Queue createQueue(...) {
    Queue q = queueRepository.save(...);
    kafkaTemplate.send(...);  // 락 점유 시간 늘어남, 롤백 문제
    return q;
}
```

---

## 6. 함정 모음

### 6.1 TTL 만료 vs 작업 미완료

```
1. API #1이 락 획득 (TTL 10초)
2. API #1 작업이 12초 걸림 → TTL 먼저 만료
3. API #2가 락 획득 (TTL 만료된 키 다시 SETNX 성공)
4. API #1과 #2가 동시에 작업 → 보호 실패
```

**완화책**:
- leaseTime을 충분히 길게
- Redisson watchdog 사용 (leaseTime=-1 또는 미지정 시 자동 갱신)
- fencing token 패턴 (외부 시스템 호출 시 단조 증가 토큰 검증)

### 6.2 GC pause

```
1. API #1 락 획득
2. API #1에서 STW GC 15초 발생
3. 그 사이 락 TTL 만료, API #2가 락 획득
4. API #1 GC 종료, 자기가 락 가졌다고 착각하며 작업 계속
```

**완화책**: 짧은 임계영역, fencing token.

### 6.3 락 해제 누락

```java
RLock lock = redissonClient.getLock(key);
lock.lock();
doWork();   // 예외 발생
lock.unlock();  // 실행 안 됨 → leaseTime까지 다른 요청 대기
```

**완화책**: try-finally, `lock.isHeldByCurrentThread()` 체크 후 unlock.

### 6.4 다른 트랜잭션의 락 해제 시도

```java
// API #1이 락 획득 (TTL 만료됨)
// API #2가 같은 키로 락 획득
// API #1이 finally에서 unlock() → API #2의 락 해제됨
```

**완화책**: Redisson은 내부적으로 ownerId 체크. 직접 Lua로 구현 시에도 ownerId 검증 필수.

```lua
if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("del", KEYS[1])
else
    return 0
end
```

### 6.5 Cluster 환경에서 Multi-key Lua Script (Sprint 10+ 대비)

Redis Cluster는 Lua Script 내 여러 key 사용 시 제약 있음.

```lua
-- ❌ 문제 시나리오
-- KEYS[1] = "queue:q_bts:waiting" → slot A
-- KEYS[2] = "queue:q_bts:count"   → slot B (다를 수 있음)
-- Lua Script는 하나의 Master에서만 실행 → CROSSSLOT 에러

-- 에러 예시:
-- (error) CROSSSLOT Keys in request don't hash to the same slot
```

**해결책 - Hash Tag 활용**:
```lua
-- ✅ 같은 slot 강제
-- KEYS[1] = "queue:{q_bts}:waiting"  → {q_bts}로 slot 계산
-- KEYS[2] = "queue:{q_bts}:count"    → 같은 slot 보장
-- 두 key 같은 slot → 같은 Master → Lua Script 정상 실행
```

**Queue Platform 관점** (2026-07-15 개정 — DECISIONS §70):

> ⚠️ 개정 전 이 문단은 "enqueue.lua는 단일 key만 사용 → CROSSSLOT 이슈 없음"이라고 적혀 있었다.
> 5-E에서 score 발급을 `INCR seq`로 바꾸며 **Lua가 2-key가 되어 전제가 깨졌다.**

- `enqueue_bulk.lua`는 **키 2개**를 사용 → **해시태그 없이는 CROSSSLOT 발생**
  - `KEYS[1]` = 대기열 ZSet, `KEYS[2]` = 순번 카운터(`INCR`)
  - seq 키는 제거 불가: `ZCARD+1`은 admit으로 중간이 빠지면 충돌, `currentTimeMillis()`는 동점 발생 → `INCR`만 단조증가·유일 보장
- **해시태그 적용 완료** (`queue/QueueKeys.java`) → 두 키가 항상 같은 slot
- 로컬 Cluster A 실측:
  ```
  queue:q_bts:waiting    → slot 7911   → 포트 7002  ┐ 다른 마스터 → CROSSSLOT
  queue:q_bts:seq        → slot 11273  → 포트 7003  ┘

  queue:{q_bts}:waiting  → slot 10592  → 포트 7003  ┐ 같은 마스터 → 정상
  queue:{q_bts}:seq      → slot 10592  → 포트 7003  ┘
  ```
- Sentinel 환경에서는 슬롯 개념이 없어 해시태그가 **무해** → 선제 적용해도 안전
- Sprint 8+ Cluster 도입 시 **무변경으로 작동** (로컬 Cluster A에서 실제 스크립트 실행 검증 완료)
- Sprint 12+ 이중 라우팅 도입 시 태그가 shard로 이동: `queue:{shard_X}:{queueId}:waiting`

### 6.6 Cluster Failover 중 짧은 순간 데이터 불일치

Master 장애 시 Replica 승격까지 5-10초 소요. 이 사이 요청은 어떻게?

```
t=0    Master 장애
t=1-5  Cluster 감지 (cluster-node-timeout)
t=5-10 Replica 승격 완료

t=1-10 사이 요청:
- 해당 slot 담당 Master 없음
- Lettuce가 MOVED/ASK 응답 처리 (재시도)
- 최대 max-redirects (기본 3회) 재시도
- 실패 시 클라이언트 에러
```

**완화책**:
- Application 재시도 로직 (@Retryable)
- 클라이언트 사이드 재시도 (SDK 자동)
- Failover 감지 시간 단축 (cluster-node-timeout 조정, 기본 5000ms)

**Queue Platform 관점**:
- Enqueue 실패 시 클라이언트 SDK가 재시도
- 실제 서비스 영향 최소화 (사용자 관점 5-10초 지연은 무관)

### 6.7 Cluster 환경에서 분산 락 위치

`@DistributedLock` (Redisson) 사용 시 어느 Master에 락이 저장되는가?

```
Redisson RLock 사용 시:
- lock key: "lock:tenant:{tenantId}:queue-create"
- Lettuce가 CRC16으로 담당 Master 결정
- 해당 Master에만 락 저장

주의사항:
- Redisson은 자동으로 Cluster 감지
- 단일 key 사용 시 자동 라우팅
- Multi-lock (여러 key 락) 시 각 key가 다른 Master 가능
```

**Queue Platform 관행**:
- 락 key는 항상 단일 도메인/ID 단위 (예: `lock:tenant:{tenantId}:queue-create`)
- Multi-lock 필요 시 Hash Tag 활용 (`lock:{tenant_1}:queue-create`, `lock:{tenant_1}:api-key-issue`)
- 락 위치 자동 라우팅에 위임

---

## 7. 테스트 전략

### 7.1 단위 테스트

Aspect 자체보다, **락이 걸린 메서드에 동시 호출 시 직렬화되는가**를 검증.

```java
@SpringBootTest
class QueueCreationConcurrencyTest {
    
    @Autowired QueueCreationService service;
    
    @Test
    void 동일_tenant_동시_createQueue_정확히_quota만큼만_생성() throws Exception {
        Long tenantId = createTenantWithPlan(maxQueues = 3);
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        
        ExecutorService es = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            int idx = i;
            es.submit(() -> {
                try {
                    start.await();
                    service.createQueue(tenantId, new CreateQueueCommand("q" + idx));
                    success.incrementAndGet();
                } catch (QuotaExceededException e) {
                    failure.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    end.countDown();
                }
            });
        }
        
        start.countDown();
        end.await();
        
        assertThat(success.get()).isEqualTo(3);
        assertThat(failure.get()).isEqualTo(7);
    }
}
```

### 7.2 통합 테스트

여러 API 인스턴스를 띄우고 실제 분산 환경 시뮬레이션:
- TestContainers로 Redis 띄우기
- 같은 Redisson 클라이언트 설정으로 2개 컨텍스트 기동
- 양쪽에서 동시 요청

---

## 8. 관련 결정 (DECISIONS.md)

- §57: 동시성 제어 우선순위 정책
- §58: Queue 생성 동시성 처리 방식 (비관적 락 + UNIQUE 제약)
- §59: `@DistributedLock` 도입 및 모듈 배치
- §66: Redis Cluster 도입 결정 (Sprint 10+)
- §67: 이중 라우팅 아키텍처 (Cluster + Hash Tag)
- §68: Master 크기 최적화 원리
- §69: 극대 분산 4x4x4GB 최종 구성

### Cluster 환경 요약 (전환 확정 · 시점 미정, §75)

> **§75 추가 제약**: 이중 라우팅에서 **한 큐의 키 4종은 같은 클러스터**에 놓인다(라우팅 단위 = 큐 1개).
> 해시태그는 *한 클러스터 안의* 슬롯만 정렬하며 **클러스터 경계는 못 넘기 때문**에, 큐가 두 클러스터에
> 걸치면 3키 Lua(`enqueue_bulk`·`poll_verify`)를 같은 EVAL로 보낼 수조차 없다.

| 항목 | Sentinel (현재 구현) | Cluster (확정, 시점 미정) | 이중 라우팅 (§75 — 큐 단위) |
|------|-----------------|---------------------|-------------------------|
| Redis 원자성 | Master 하나 | 각 Master 개별 원자 | 각 Master 개별 원자 |
| Lua Script | 모든 key 자유 | 단일 key 또는 Hash Tag | Hash Tag 필수 |
| 분산 락 | Master 하나에 저장 | slot 담당 Master에 저장 | Hash Tag로 위치 제어 |
| Multi-key 명령 | 자유 | CROSSSLOT 주의 | Hash Tag로 회피 |
| 확장 방식 | Scale-Up만 | Scale-Out 가능 | 완전한 제어 |

**Queue Platform 무변경 자산**:
- ~~enqueue.lua는 단일 key만 사용 → Cluster 무변경 작동~~
  → **`enqueue_bulk.lua`는 2-key(waiting + seq)이므로 Hash Tag 필수** (2026-07-15 개정, §70)
  → Hash Tag 선제 적용 완료(`queue/QueueKeys.java`) → **이제 Cluster 무변경 작동**
- `@DistributedLock` key 패턴 (`lock:{domain}:{id}:{action}`) → Cluster 무변경
- Rate Limiter Lua Script → 단일 key 사용, 무변경 (`rl:tenant:{id}`, `rl:{action}:ip:{ip}`)

**Sprint 12+ 변경 예상**:
- ~~필요 시~~ Lua Script Hash Tag → **Sprint 5-E에서 이미 도입 완료**. Sprint 12+엔 태그 기준이 queueId → shard로 이동
- `@DistributedLock` key도 Hash Tag 활용
- 이중 라우팅 정보 도메인에 반영

---

## 9. 참조

- CLAUDE.md "동시성 제어" 섹션
- `doc/ARCHITECTURE_ROADMAP.md` (부록 F: 이중 라우팅, 부록 H: Master 최적화)
- `doc/INFRA_SETUP.md` §6.5 (Cluster 로컬 실습)
- Martin Kleppmann, "How to do distributed locking"
- Redis Documentation, "Distributed Locks with Redis"
- Redisson Wiki, "Distributed locks and synchronizers"