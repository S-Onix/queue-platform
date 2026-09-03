# Sprint 5 Phase C — Rate Limiter 설계 + 구현 통합 문서

> 작성일: 2026-06-10 (Sprint 5-C 완료 시점)
>
> ⚠️ **이 문서는 그 시점의 설계·구현 노트다.** 이후 폴링 전용 버킷이 추가됐다
> (`rl:poll:token:{tokenId}`, cap 5 · refill 1.0/s — §74·PR #23).
> **운영 수치의 정본은 `doc/monitoring/runbook/rate-limit.md`다**(코드 줄 번호까지 대조돼 있다).
>
> 🔴 **이 문서의 "Plan별 차등 한도"는 2026-09-03에 폐기됐다 (§88).** `Plan` enum을 걷어내고
> 한도를 **모든 테넌트 동일한 상수**로 바꿨다 — 과금이 plan을 읽지 않고(청구는 token 개수),
> plan을 읽는 코드가 `RateLimitFilter` 한 곳뿐이라 등급제가 실제로 하던 일은 "SaaS 약속"이 아니라
> **독식 방어** 하나였기 때문이다. **방어는 등급이 아니라 상수다.**
> 아래 본문의 등급별 표·서술은 **그 시점의 기록으로만** 읽어라.
> 목적: Queue Platform의 Rate Limiter 알고리즘 선택, 분리, 구현, 면접 답변 자산 통합 정리

---

## 1. 왜 Rate Limiter인가?

### 1.1 멀티 테넌시의 위협

```
[문제]
한 Tenant가 시스템 자원 독점 가능
- 대기열 폭주 시 Redis Master 부하
- DB Connection Pool 고갈
- 다른 Tenant 서비스 영향

[필요]
Tenant 단위 자원 사용량 제한
- SaaS Plan별 차등 한도
- 콘서트 티켓팅 같은 burst 흡수
- 공정한 자원 분배
```

### 1.2 인증 전 endpoint의 위협

```
[공격 시나리오]
- Brute Force: 무차별 비밀번호 시도
- Credential Stuffing: 유출 정보 대량 시도
- 회원가입 남용: 봇이 가짜 계정 대량 생성
- DDoS: 인증 API 폭주로 시스템 마비

[필요]
- IP 기반 한도
- 비교적 엄격한 한도
- 한도 도달 = 비정상 신호
```

---

## 2. 알고리즘 선택 — 5개 비교

### 2.1 알고리즘 비교표

| 알고리즘 | 정확성 | Burst | 메모리 | 구현 복잡도 | 적합한 상황 |
|---------|--------|-------|--------|------------|----------|
| **Fixed Window** | 낮음 (경계 burst) | 시간 경계만 | O(1) | 매우 단순 | 단순 한도, 보안 |
| **Sliding Window Log** | 매우 높음 | 없음 | O(N) | 중간 | 정확성 우선 |
| **Sliding Window Counter** | 높음 | 작음 | O(1) | 중간 | 일반적 API |
| **Token Bucket** ⭐ | 높음 | 제어 가능 | O(1) | 단순 | Burst 처리 |
| **Leaky Bucket** | 매우 높음 | 없음 | O(1) | 중간 | 일정 속도 |

### 2.2 Fixed Window

**원리:**
```
1분 윈도우 카운터:
  12:00 → 12:01 윈도우: count = 0
  요청 1 → count = 1
  요청 2 → count = 2
  ...
  count >= limit → 거부
```

**장점:**
- 가장 단순
- O(1) 메모리
- "분당 N회" 명확한 의미

**단점:**
- 시간 경계 burst (12:59에 5회 + 13:00에 5회 = 한도의 2배 통과 가능)

**적합한 상황:**
- 한도가 작아 경계 burst 영향 미미 (예: 분당 5회)
- 한도 도달 = 비정상 신호 (보안 한도)

### 2.3 Sliding Window Log

**원리:**
```
요청 타임스탬프 리스트 유지:
  [12:00:30, 12:00:45, 12:01:00, ...]
  요청 시 1분 이내 카운트 → 한도 비교
  오래된 타임스탬프 제거
```

**장점:**
- 매우 정확
- Burst 없음

**단점:**
- O(N) 메모리 (요청마다 1 entry)
- 대규모 트래픽에 부적합

**기각 이유 (Queue Platform):**
- 메모리 부담 (10,000 요청 × 8 byte = 80KB per key)
- 정확성 절대 필요 X

### 2.4 Sliding Window Counter

**원리:**
```
현재 윈도우 카운터 + 이전 윈도우 카운터의 시간 비율
weighted_count = current + previous × (1 - elapsed_ratio)
```

**장점:**
- O(1) 메모리
- Fixed Window 경계 문제 완화

**단점:**
- Burst 처리 부족
- 구현 약간 복잡

**기각 이유 (Queue Platform):**
- Burst 허용은 Token Bucket이 명확
- 보안 한도는 Fixed Window가 명확

### 2.5 Token Bucket ⭐ (채택)

**원리:**
```
양동이 모델:
  capacity: 양동이 크기 (burst 한도)
  refillRatePerSecond: 토큰 회복 속도

요청 시:
  토큰 1개 가져감 → 통과
  토큰 부족 → 거부

회복:
  매 초 refillRatePerSecond개 토큰 추가
  capacity 한도 내에서만 회복
```

**장점:**
- Burst 허용 (양동이 가득찬 상태 즉시 통과)
- O(1) 메모리 (Hash 필드 in-place 갱신)
- 평균 처리량 + 순간 burst 분리 제어
- SaaS Plan과 자연스러운 매핑

**단점:**
- 장시간 미사용 후 큰 burst 가능 (양동이 가득참)
- 운영 시 burst 도달 빈도 모니터링 필요

**Queue Platform 선택 이유:**
- 콘서트 티켓팅 같은 burst 흡수 필요
- Tenant Plan과 매핑 (capacity = refillRate × 60)
- SLA 보장 (평균 처리량) + Burst 허용 (순간 폭증)

### 2.6 Leaky Bucket

**원리:**
```
일정 속도로 빠지는 양동이:
  요청 → 양동이에 넣음
  큐 가득참 → 거부
  서버는 일정 속도로 처리
```

**장점:**
- 매우 일정한 처리 속도

**단점:**
- Burst 거부 (양동이 가득함)
- 대기열 같은 비즈니스와 충돌

**기각 이유 (Queue Platform):**
- Burst 거부는 비즈니스 목적과 충돌
- 콘서트 티켓팅에 부적합

---

## 3. 알고리즘 분리 — Token Bucket + Fixed Window

### 3.1 분리 결정

Queue Platform은 **두 알고리즘을 분리 적용**:

| 용도 | 알고리즘 | 이유 |
|------|---------|------|
| Tenant SLA (인증 후) | Token Bucket | Burst 허용 + SLA 매핑 |
| 인증 전 (signup/login/refresh) | Fixed Window | Burst 불필요 + 명확한 보안 한도 |

### 3.2 왜 단일 알고리즘으로 통합하지 않았나?

**시나리오 1: Token Bucket으로 통합**
```
SIGNUP에 Token Bucket 적용
  capacity 5, refillRate 0.083 (분당 5)

문제:
  - 새 사용자: burst 5회 즉시 가능 (5초 안에 5회 가입 시도)
  - 공격자에게 유리 (초기 5회 통과 후 거부)
  - "1분에 5회" 의미가 모호 (실제로는 burst + 회복)

결론: 보안 목적에 부적합
```

**시나리오 2: Fixed Window로 통합**
```
Tenant SLA에 Fixed Window 적용
  PRO Plan: 분당 10,000회

문제:
  - 콘서트 시작 시 폭증 흡수 X
  - 시간 경계 burst (이전 윈도우 + 현재 윈도우)
  - SaaS Plan과 매핑 어색 (burst 개념 없음)

결론: 비즈니스 목적에 부적합
```

### 3.3 알고리즘 분리 정당성

**다른 책임:**
- Tenant SLA = 비즈니스 약속 (Plan별 차등)
- 보안 한도 = 시스템 보호

**다른 시그니처:**
- Token Bucket: `tryAcquire(key, capacity, refillRate)`
- Fixed Window: `tryAcquire(key, limit, windowSizeMillis)`

**다른 자리:**
- RateLimitFilter에서 인증 후/전 분기
- 같은 자리에서 교체 X → 다형성 불필요

### 3.4 인터페이스 분리의 정당성

```java
[원칙]
인터페이스의 가치 = 다형성 + 의존성 역전 + 테스트 + 명세

[Queue Platform 분리]
RateLimiter (Token Bucket용)
FixedWindowRateLimiter (Fixed Window용)

[잃은 것]
- 다형성 (같은 자리 교체 가능성)

[유지된 것]
- 의존성 역전 (도메인이 Redis 모름) ✓
- 테스트 용이성 (Mock 가능) ✓
- 명세 (Contract) ✓

[결론]
다형성 외 3가지 가치가 유지되므로 인터페이스 분리해도 손해 없음
오히려 의미 명확성 ↑
```

---

## 4. Tenant Plan — SaaS 등급 시스템

### 4.1 Plan 정의

```java
public enum Plan {
    FREE(100, 1.67),             // 분당 100 RPS
    STARTER(1_000, 16.67),       // 분당 1,000 RPS
    PRO(10_000, 166.67),         // 분당 10,000 RPS
    ENTERPRISE(100_000, 1_666.67); // 분당 100,000 RPS

    private final int capacity;
    private final double refillRatePerSecond;
}
```

### 4.2 비율 결정 — capacity = refillRate × 60

**시나리오:**
```
콘서트 티켓팅 시작:
  시작 1초: 5,000 요청 (폭증)
  시작 5초: 평균으로 안정화
  1분 후: 매진

Token Bucket 동작:
  capacity 10,000 (PRO Plan)
  refillRate 166.67/초

  시작 1초: 5,000 요청 → 양동이 50% 사용
  → 모두 통과 ✓

  시작 5초: 양동이 회복 + 사용
  → 평균 처리량 내에서 통과
```

**왜 1분치인가?**
- 콘서트 매진까지 일반적 시간 = 1분
- 1분 후엔 트래픽 안정화
- 1분치 burst가 비즈니스 시나리오와 일치

### 4.3 DB 매핑

```sql
ALTER TABLE tenants ADD COLUMN plan TINYINT NOT NULL DEFAULT 0;

-- Plan ordinal:
-- 0 = FREE
-- 1 = STARTER
-- 2 = PRO
-- 3 = ENTERPRISE
```

TenantStatus 패턴과 동일 (DECISIONS §50).

### 4.4 Plan 도메인 위치

**Plan은 Tenant 도메인에 두는 이유:**
- Plan = SaaS 비즈니스 약속 (Tenant의 속성)
- Rate Limit은 그 약속의 한 구현
- 도메인 객체로 자연스러움

```java
public class Tenant {
    private Plan plan;

    public void changePlan(Plan newPlan) {
        // 업그레이드/다운그레이드 로직
    }
}
```

---

## 5. 인증 전 한도 — Fixed Window 적용

### 5.1 PublicEndpointRateLimit

```java
public enum PublicEndpointRateLimit {
    SIGNUP(5, 60_000),     // 분당 5건 (회원가입 남용 방지)
    LOGIN(10, 60_000),     // 분당 10건 (Brute Force 방지)
    REFRESH(30, 60_000);   // 분당 30건 (정상 SDK 사용 여유)

    private final int limit;
    private final long windowSizeMillis;
}
```

### 5.2 한도 결정 근거

**SIGNUP 5/분:**
- 정상 사용자: 하루 1-2회 회원가입
- 5회/분은 정상에겐 절대 도달 X
- 도달 = 봇/공격 신호

**LOGIN 10/분:**
- 정상 사용자: 비밀번호 오타 3-5회 + 여유
- 10회/분은 정상 사용 충분
- 도달 = Brute Force 의심

**REFRESH 30/분:**
- 정상 SDK: 15분마다 1회 갱신
- 30회/분은 매우 여유
- 도달 = SDK 버그 또는 봇

### 5.3 키 패턴

```
rl:signup:ip:127.0.0.1:{windowNo}
rl:login:ip:127.0.0.1:{windowNo}
rl:refresh:ip:127.0.0.1:{windowNo}

windowNo = currentTimeMillis / windowSizeMillis
→ 윈도우별로 키 분리 → 자동 만료 (메모리 효율)
```

### 5.4 IP 추출

```java
String ip = request.getRemoteAddr();
```

**peer IP만 쓰는 이유:**
- 현재 구성에 프록시(Nginx/ALB)가 없다 → `X-Forwarded-For`는 클라이언트가 직접 쓰는 값
- 그 값을 버킷 키로 쓰면 요청마다 헤더만 바꿔 매번 새 버킷 → 인증 전 Rate Limit이 무력화 (실증됨)
- TCP peer 주소는 위조할 수 없으므로 유일하게 신뢰 가능한 식별자
- LB 도입(Sprint 11) 시: 앱 코드가 아니라 `server.forward-headers-strategy=native`(RemoteIpValve) + 신뢰 프록시 목록으로 처리

### 5.5 NAT 공유 IP 대응

```
[고려]
회사 100명 동시 가입 = 같은 IP
한도 분당 5회 → 정상 사용자도 차단 가능

[해결]
한도 너무 엄격하지 않음
보조 수단 (CAPTCHA, 이메일 인증) Sprint 6+
```

---

## 6. RateLimitFilter HTTP 통합

### 6.1 Filter 체인 위치

```
HTTP 요청
   ↓
... 기본 Filter들 ...
   ↓
JwtAuthenticationFilter        ← addFilterBefore
   - JWT 파싱
   - SecurityContext에 TenantAuth 저장
   ↓
RateLimitFilter               ← addFilterAfter
   - 인증 후/전 분기
   - Token Bucket / Fixed Window 적용
   - 429 응답 가능
   ↓
UsernamePasswordAuthenticationFilter
   ↓
... 그 외 Filter들 ...
   ↓
FilterSecurityInterceptor (인가)
   ↓
Controller
```

### 6.2 RateLimitFilter 구현

```java
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter tokenBucketLimiter;
    private final FixedWindowRateLimiter fixedWindowLimiter;
    private final TenantRepository tenantRepository;

    @Override
    protected void doFilterInternal(...) {
        if (shouldSkip(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null && auth.getPrincipal() instanceof TenantAuth tenantAuth) {
            // 인증 후 → Token Bucket
            if (!checkAuthenticatedRateLimit(tenantAuth, response)) return;
        } else {
            // 인증 전 → Fixed Window
            if (!checkPublicRateLimit(request, response)) return;
        }

        filterChain.doFilter(request, response);
    }
}
```

### 6.3 SecurityConfig 등록

```java
.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
.addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);
```

**addFilterAfter 두 번째 파라미터:**
- "어느 Filter 뒤에 끼울지" 기준점 지정
- JwtAuthenticationFilter 뒤 = JWT 인증 후 Rate Limit

### 6.4 429 응답 형식

**Header:**
```
HTTP/1.1 429 Too Many Requests
Retry-After: 60
Content-Type: application/json;charset=UTF-8
```

**Body:**
```json
{
  "error": "RL001",
  "message": "요청 한도를 초과했습니다.",
  "retryAfter": 60
}
```

**Retry-After 값:**
- Token Bucket: `Math.max(1, ceil(1.0 / refillRate))` (1 토큰 회복 시간)
- Fixed Window: `windowSizeMillis / 1000` (보수적, 윈도우 크기)

---

## 7. Lua Script 구현

### 7.1 token-bucket.lua

```lua
-- KEYS[1]: 양동이 키 (예: "rl:tenant:t_abc123")
-- ARGV[1]: capacity (양동이 크기)
-- ARGV[2]: refillRatePerSecond (토큰 회복 속도)
-- ARGV[3]: nowMillis (현재 시간)
-- 반환: 1 = 허용, 0 = 거부

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillRate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

-- 1. 현재 상태 조회
local bucket = redis.call('HMGET', key, 'tokens', 'lastRefillMillis')
local tokens = tonumber(bucket[1]) or capacity
local lastRefillMillis = tonumber(bucket[2]) or now

-- 2. 회복 계산 (경과 시간만큼 토큰 회복)
local elapsedSeconds = (now - lastRefillMillis) / 1000.0
local refillTokens = elapsedSeconds * refillRate
tokens = math.min(capacity, tokens + refillTokens)

-- 3. 토큰 사용 시도
if tokens >= 1 then
    tokens = tokens - 1
    redis.call('HMSET', key, 'tokens', tokens, 'lastRefillMillis', now)
    redis.call('EXPIRE', key, 60)  -- 1분 미사용 시 정리
    return 1  -- 허용
else
    redis.call('HMSET', key, 'tokens', tokens, 'lastRefillMillis', now)
    redis.call('EXPIRE', key, 60)
    return 0  -- 거부
end
```

### 7.2 fixed-window.lua

```lua
-- KEYS[1]: 카운터 키 (예: "rl:signup:ip:127.0.0.1")
-- ARGV[1]: limit (윈도우당 허용 요청 수)
-- ARGV[2]: windowSizeMillis (윈도우 크기, ms)
-- ARGV[3]: nowMillis (현재 시간)
-- 반환: 1 = 허용, 0 = 거부

local baseKey = KEYS[1]
local limit = tonumber(ARGV[1])
local windowSizeMillis = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

-- 1. 윈도우 번호 계산
local windowNo = math.floor(now / windowSizeMillis)

-- 2. 윈도우별 키 (자동 만료를 위해 분리)
local key = baseKey .. ":" .. windowNo

-- 3. 카운터 증가
local current = redis.call('INCR', key)

-- 4. 첫 증가 시 TTL 설정
if current == 1 then
    local ttlSeconds = math.floor(windowSizeMillis / 1000) + 1
    redis.call('EXPIRE', key, ttlSeconds)
end

-- 5. 한도 체크
if current > limit then
    return 0  -- 거부
end

return 1  -- 허용
```

### 7.3 Lua Script 원자성

```
[Redis 싱글스레드 보장]
- Script 실행 중 다른 명령 큐잉
- HMGET → 계산 → HMSET 전체가 원자적
- Race Condition 자동 해결

[Java 단독 구현 시 한계]
- GET → 계산 → SET 사이 동시 요청 가능
- Lost Update 발생
- 분산 환경에서 정확성 보장 X
```

---

## 8. 헥사고날 아키텍처 적용

### 8.1 모듈 구조

```
queue-domain/.../ratelimit/                  ← 도메인 포트
├── RateLimiter.java                         (Token Bucket용)
└── FixedWindowRateLimiter.java              (Fixed Window용)

queue-infrastructure/.../ratelimit/          ← 인프라 구현
├── InMemoryTokenBucketRateLimiter.java      (학습/단일 JVM)
├── RedisTokenBucketRateLimiter.java         (운영, Lua)
└── RedisFixedWindowRateLimiter.java         (운영, Lua)

queue-infrastructure/.../resources/lua/      ← Lua Scripts
├── token-bucket.lua
└── fixed-window.lua

queue-api/.../security/                      ← HTTP 통합
├── RateLimitFilter.java
├── PublicEndpointRateLimit.java
└── SecurityConfig.java (수정)
```

### 8.2 의존성 방향

```
queue-domain (포트)
   ↑ 의존
queue-infrastructure (구현)
   ↑ 의존
queue-api (사용)

[헥사고날 원칙]
- 도메인은 외부 모듈에 의존 X
- 인프라가 도메인 포트 구현
- API가 도메인 포트 사용
```

### 8.3 In-Memory 구현이 있는 이유

```
[학습 목적]
- Token Bucket 알고리즘 이해
- Java ConcurrentHashMap 활용
- 단일 JVM 동시성 학습

[테스트 목적]
- Redis 없이 단위 테스트
- 빠른 피드백 (CI 속도)

[Production은 Redis]
- 분산 환경 정확성
- Lua Script 원자성
- 멀티 인스턴스 공유 상태
```

---

## 9. 검증 + 테스트

### 9.1 동시성 검증

```java
@Test
void 동시_1000개_요청에서_capacity_보장() {
    String key = "test:tenant:1";
    int capacity = 100;
    double refillRate = 1.67;

    // 1,000 동시 요청
    int allowed = IntStream.range(0, 1000)
        .parallel()
        .mapToObj(i -> rateLimiter.tryAcquire(key, capacity, refillRate))
        .filter(Boolean::booleanValue)
        .count();

    // 정확히 capacity개만 통과
    assertThat(allowed).isEqualTo(capacity);
}
```

**Lua 원자성 검증 성공:**
- Java 단독 구현: 동시성 문제로 1,050~1,150 통과 (실패)
- Redis Lua 구현: 정확히 100 통과 ✓

### 9.2 Burst 허용 검증

```java
@Test
void Token_Bucket_burst_허용() {
    String key = "test:tenant:2";

    // 즉시 capacity개 요청 → 모두 허용
    for (int i = 0; i < 100; i++) {
        assertThat(rateLimiter.tryAcquire(key, 100, 1.67)).isTrue();
    }

    // 101번째 → 거부
    assertThat(rateLimiter.tryAcquire(key, 100, 1.67)).isFalse();
}
```

### 9.3 회복 검증

```java
@Test
void Token_Bucket_시간_경과_후_회복() {
    String key = "test:tenant:3";

    // capacity 소진
    for (int i = 0; i < 100; i++) {
        rateLimiter.tryAcquire(key, 100, 1.67);
    }
    assertThat(rateLimiter.tryAcquire(key, 100, 1.67)).isFalse();

    // 1초 대기 → 1.67 토큰 회복
    Thread.sleep(1000);

    // 다시 허용
    assertThat(rateLimiter.tryAcquire(key, 100, 1.67)).isTrue();
}
```

### 9.4 수동 검증 (signup)

```bash
# signup 7회 빠르게 (한도 5/분)
for i in {1..7}; do
  echo -n "Request $i: "
  curl -s -o /dev/null -w "HTTP %{http_code}\n" \
    -X POST http://localhost:8080/api/v1/tenants/signup \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"test$i@test.com\",\"password\":\"password123\",\"name\":\"Test$i\"}"
done

# 결과:
# Request 1: HTTP 200
# Request 2: HTTP 200
# Request 3: HTTP 200
# Request 4: HTTP 200
# Request 5: HTTP 200
# Request 6: HTTP 429  ⭐
# Request 7: HTTP 429
```

### 9.5 Retry-After 헤더 검증

```bash
curl -i -X POST http://localhost:8080/api/v1/tenants/signup ...

# 응답:
# HTTP/1.1 429 Too Many Requests
# Retry-After: 60
# Content-Type: application/json;charset=UTF-8
#
# {"error":"RL001","message":"요청 한도를 초과했습니다.","retryAfter":60}
```

---

## 10. 트레이드오프 + 한계

### 10.1 Redis Lua Script 의존도

```
[Queue Platform Redis 사용]
- Rate Limiter: 매 요청 1-2 ops
- Token 캐시: 매 Polling 1 op
- Queue 순서: ZADD/ZREM/ZSCORE
- 합계: 요청당 평균 3-5 ops

[Redis 단일 Master 한도]
- ~100,000 ops/sec
- Lua Script 1회 = 여러 ops (HMGET + HMSET 등)

[처리 가능 RPS 추정]
20,000 ~ 25,000 RPS (Queue Platform)
```

**콘서트급 처리 (10만 명 / 5초 = 20,000 RPS):**
- 현재 인프라로 한계점 도달
- Sprint 10+에서 검토:
    - Lua Script 통합 (Rate Limit + Queue 등 한 Script로)
    - Redis Cluster (Sharding)
    - 일부 한도를 Application 레벨로 분산

### 10.2 공정성 vs 시스템 보호

```
[Tenant 단위 Rate Limit]
- 같은 Tenant 내 사용자는 선착순 유지
- Tenant 간만 격리

[User 단위 Rate Limit 제외 이유]
- 선착순 비즈니스와 충돌
- 일찍 폴링한 사용자가 입장 가까워짐
- User 한도가 불공정 발생
```

### 10.3 Tenant 조회 비용

```
[현재]
RateLimitFilter가 매 요청 Tenant DB 조회
- TenantRepository.findByTenantId() → DB 1회

[부담]
2,000 RPS × Tenant 조회 = 2,000 DB QPS

[해결 (Sprint 5-D)]
Redis 캐시 도입
- tenant-cache:{tenantId} (TTL 60s)
- DB QPS → 0에 가까움
```

### 10.4 NAT 공유 IP

```
[한계]
회사 100명 동시 가입 = 같은 IP
한도 분당 5회 → 일부 차단 가능

[완화]
한도 너무 엄격하지 않음 (5, 10, 30)
정상 사용자 영향 미미

[보강 (Sprint 6+)]
- CAPTCHA (의심 IP)
- 이메일 인증
- Device Fingerprinting
```

### 10.5 Polling 적응형 간격과 Rate Limit 충돌

```
[직접 충돌 X]
- Rate Limit Retry-After (429 응답)
- Polling nextPollAfterSec (200 응답)
- HTTP 코드 다름 → 동시 발생 X

[시계열 충돌]
SDK가 두 타이머 중 어느 걸 따를지

[해결]
- 매 응답마다 다음 시점 결정
- 기존 타이머 취소 → 새 타이머 설정
- 마지막 응답 우선
```

---

## 11. 면접 답변 자산

### 11.1 알고리즘 선택

> **Q: Rate Limiter 알고리즘 어떻게 선택했나요?**
>
> A: 비즈니스 요구사항 중심으로 선택했습니다. Queue Platform은 콘서트 티켓팅 같은 burst 트래픽을 흡수해야 하므로 Token Bucket을 선택했습니다.
>
> 5개 알고리즘을 비교했습니다:
> - Fixed Window: 시간 경계 burst 문제 (12:59 + 13:00 = 한도의 2배)
> - Sliding Window Log: 매우 정확하지만 O(N) 메모리 부담
> - Sliding Window Counter: O(1)이지만 burst 처리 부족
> - Token Bucket: capacity + refillRate로 burst와 평균 처리량 분리 제어
> - Leaky Bucket: burst 거부 → 비즈니스와 충돌
>
> Token Bucket의 capacity는 1분치 burst를 허용하도록 `refillRate × 60`으로 설정했고, 양동이 모델이 SaaS Plan(FREE/STARTER/PRO/ENTERPRISE)과 자연스럽게 매핑됩니다.

### 11.2 알고리즘 분리

> **Q: 왜 Token Bucket + Fixed Window 두 알고리즘을 분리했나요?**
>
> A: 인증 후와 인증 전 한도의 의도가 다르기 때문입니다.
>
> - 인증 후 (Tenant SLA): burst 처리가 핵심 → Token Bucket
> - 인증 전 (보안): burst 불필요, 명확한 한도 → Fixed Window
>
> 단일 알고리즘으로 통합하려 했지만 의미가 모호해졌습니다. SIGNUP에 Token Bucket을 적용하면 "분당 5회"가 실제로는 "burst 5회 + 회복"으로 동작해서 공격자에게 유리하고, Tenant SLA에 Fixed Window를 적용하면 burst 처리가 안 됩니다.
>
> 인터페이스를 분리해도 인터페이스의 다른 가치(의존성 역전, 테스트 용이성, 명세)는 유지되므로 손해 없이 의미 명확화를 얻을 수 있었습니다.

### 11.3 Tenant Plan

> **Q: Tenant Plan은 어떻게 설계했나요?**
>
> A: SaaS 비즈니스 모델을 Rate Limit에 매핑한 구조입니다.
>
> | Plan | capacity | refillRate |
> |------|----------|-----------|
> | FREE | 100 | 1.67/초 (분당 100) |
> | STARTER | 1,000 | 16.67/초 (분당 1,000) |
> | PRO | 10,000 | 166.67/초 (분당 10,000) |
> | ENTERPRISE | 100,000 | 1,666.67/초 (분당 100,000) |
>
> 비율은 `capacity = refillRate × 60`으로 1분치 burst를 허용합니다. 콘서트 매진까지 일반적 시간이 1분이라 비즈니스 시나리오와 일치합니다.
>
> Plan은 Tenant 도메인에 enum으로 두고, DB에는 TINYINT로 저장합니다. TenantStatus 패턴과 동일해서 일관성 있고, ordinal 매핑이라 추가 컬럼 없이 확장 가능합니다.

### 11.4 인증 전 보안 한도

> **Q: 인증 전 endpoint의 한도는 어떻게 결정했나요?**
>
> A: 정상 사용자 패턴 + 공격 시나리오 기반으로 결정했습니다.
>
> - SIGNUP 5/분: 정상은 하루 1-2회 → 5회는 절대 도달 X
> - LOGIN 10/분: 정상은 비밀번호 오타 3-5회 + 여유
> - REFRESH 30/분: SDK 정상 15분마다 1회 → 매우 여유
>
> 한도 도달 = 비정상 신호 (봇/공격)로 운영자가 알람을 받을 수 있습니다. NAT 공유 IP 고려해서 너무 엄격하지 않게 했고, 추가 보안(CAPTCHA, 이메일 인증)은 Sprint 6+에서 보강합니다.

### 11.5 Lua Script 원자성

> **Q: 왜 Lua Script를 썼나요?**
>
> A: 분산 환경에서 정확성 보장을 위해서입니다.
>
> Java 단독 구현 시:
> ```
> Long current = redis.get("counter");
> Long newValue = current + 1;
> if (newValue <= limit) redis.set("counter", newValue);
> ```
> 동시 호출 시 Lost Update 발생 → 한도 초과 통과 가능
>
> Lua Script 보장:
> ```
> Redis 싱글스레드:
>   Script 실행 중 다른 명령 큐잉
>   → 전체가 원자적
>   → Race Condition 자동 해결
> ```
>
> 실제 검증으로 1,000 동시 요청 시 Java 구현은 1,050~1,150 통과(실패), Lua는 정확히 100 통과(성공)을 확인했습니다.

### 11.6 Filter 체인 위치

> **Q: RateLimitFilter를 왜 JWT 인증 뒤에 두었나요?**
>
> A: Tenant Plan 한도를 적용하려면 먼저 Tenant 식별이 필요하기 때문입니다.
>
> ```
> JwtAuthenticationFilter → SecurityContext에 TenantAuth 저장
>    ↓
> RateLimitFilter → SecurityContext에서 Tenant 조회 → Plan 한도 적용
> ```
>
> 순서가 반대였다면:
> - SecurityContext 비어있음
> - Tenant 식별 불가
> - 모든 요청이 IP 기반 한도로 처리됨 (의도 X)
>
> 인증 전 endpoint(signup/login/refresh)는 SecurityContext 비어있으므로 자연스럽게 Fixed Window + IP 분기로 처리됩니다.

### 11.7 인터페이스 설계

> **Q: 인터페이스를 두 개로 나누면 다형성이 사라지지 않나요?**
>
> A: 정확한 지적입니다. 다형성은 사라집니다.
>
> 하지만 인터페이스의 가치는 다형성만이 아닙니다:
>
> 1. **의존성 역전 (DIP)** — 가장 중요
     >    - 도메인이 Redis를 모름 (헥사고날)
>    - 인터페이스가 도메인에 위치
>    - 구현은 infrastructure에 위치
>
> 2. **테스트 용이성**
     >    - Mock으로 대체 가능
>    - Redis 없이 단위 테스트
>
> 3. **명세 (Contract)**
     >    - 구현체 간 일관성 보장
>
> 통합과 분리의 판단 기준:
> - 통합: 같은 자리 + 같은 책임 + 같은 시그니처 (예: DataSource)
> - 분리: 다른 자리 + 다른 책임 + 다른 시그니처 (예: JpaRepository vs MongoRepository)
>
> Queue Platform은 후자에 해당합니다.

---

## 12. 향후 개선 (Sprint 5-D 이후)

### 12.1 Sprint 5-D: Tenant 캐시
- TenantRepository에 Redis 캐시 도입
- TTL 60s
- DB QPS 감소

### 12.2 Sprint 6+: 보조 보안
- CAPTCHA (의심 IP)
- 이메일 인증
- Device Fingerprinting

### 12.3 Sprint 10+: 모니터링 강화
- Rate Limit 한도 도달 메트릭 (Tenant 단위)
- Grafana 대시보드
- Alert 설정 (한도 도달 빈도)

### 12.4 Sprint 10+: 부하 테스트
- k6 시나리오: Rate Limit 한도 검증
- Tenant Plan별 burst 동작 실측
- Redis Lua 부하 한계 측정

### 12.5 향후: Redis Cluster
- 단일 Master 한계 도달 시
- Sharding으로 Rate Limit 분산
- 또는 Application 레벨 분산

---

## 13. 참조 문서

### 코드
- `queue-domain/src/main/java/com/sonix/queue/domain/ratelimit/RateLimiter.java`
- `queue-domain/src/main/java/com/sonix/queue/domain/ratelimit/FixedWindowRateLimiter.java`
- `queue-domain/src/main/java/com/sonix/queue/domain/tenant/Plan.java`
- `queue-infrastructure/src/main/java/com/sonix/queue/infrastructure/ratelimit/RedisTokenBucketRateLimiter.java`
- `queue-infrastructure/src/main/java/com/sonix/queue/infrastructure/ratelimit/RedisFixedWindowRateLimiter.java`
- `queue-infrastructure/src/main/resources/lua/token-bucket.lua`
- `queue-infrastructure/src/main/resources/lua/fixed-window.lua`
- `queue-api/src/main/java/com/sonix/queue/api/security/RateLimitFilter.java`
- `queue-api/src/main/java/com/sonix/queue/api/security/PublicEndpointRateLimit.java`
- `queue-api/src/main/java/com/sonix/queue/api/security/SecurityConfig.java`

### 문서
- DECISIONS §60 (Rate Limiter 알고리즘 선택)
- DECISIONS §61 (알고리즘 분리)
- DECISIONS §62 (Tenant Plan 도입)
- DECISIONS §63 (RateLimitFilter HTTP 통합)
- DECISIONS §64 (Lua Script Bean 등록 패턴)
- DECISIONS §65 (인증 전 알고리즘 의도)
- ROADMAP Sprint 5-C
- CONCURRENCY.md (분산 환경 정확성)
- sprint-5/LUA_SCRIPTS.md (Lua Script 일반)
- sprint-5/REDIS_SENTINEL.md (Redis 인프라)

---

<p align="center">
  <sub>Sprint 5 Phase C 완료 · 2026-06-10 · 다음: Sprint 5-D (Redis 캐시)</sub>
</p>