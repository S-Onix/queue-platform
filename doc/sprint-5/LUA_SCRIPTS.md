# Sprint 5 Phase 2 — Lua Script 3종 학습 노트

> 작성일: 2026-05 (Sprint 5 Phase 2 진행 예정)
> 목적: Queue Platform의 핵심 Redis Lua Script 분석

---

## 1. 왜 Lua Script인가?

### Race Condition 문제

순진한 방식 (GET → 계산 → SET):

```java
Long current = redis.get("counter");
Long newValue = current + 5;
redis.set("counter", newValue);
```

동시 호출 시 Lost Update 발생 → Lua Script로 해결.

### Lua Script의 보장

```
Redis는 싱글스레드:
  Script 실행 중에는 다른 명령 큐잉
  → Script 전체가 원자적
  → Race Condition 자동 해결
```

### 성능 이점

```
일반 명령 (Polling 1회):
  Java → Redis (ZSCORE) → Java
  Java → Redis (ZCOUNT) → Java   × 3개 슬라이스
  Java → Redis (ZCOUNT) → Java
  Java → Redis (ZCOUNT) → Java
  → 4번 왕복 (~4ms)

Lua Script:
  Java → Redis (EVAL) → Java
  → 1번 왕복 (~1ms)

Polling 2,000 rps 기준:
  일반: 8,000번 왕복/초
  Lua: 2,000번 왕복/초
  → 75% 감소
```

---

## 2. Lua 기초 (Java/Python 다른 점)

### 인덱스는 1부터 시작

```lua
KEYS[1]  -- 첫 번째 KEY (Java로 치면 KEYS[0])
ARGV[1]  -- 첫 번째 인수
```

### KEYS vs ARGV (중요!)

```
KEYS[]: Redis Key 이름 (영향받는 데이터의 위치)
ARGV[]: 값, 숫자, 설정 등 일반 인수

규칙:
  데이터 위치(Key 이름) → KEYS[]
  값/숫자/설정 → ARGV[]

이유: Redis Cluster 환경에서
  - Redis는 KEYS[]만 검사해 슬롯 라우팅 결정
  - KEY를 ARGV에 숨기면 잘못된 노드 접근 가능성
  - Sentinel 환경에서도 처음부터 규칙 지키면 Cluster 마이그레이션 비용 0
```

### 자주 쓰는 문법

```lua
local x = 5              -- 지역 변수
if not x then ... end    -- nil 체크
tonumber("5")            -- 문자열 → 숫자
local list = {1, 2, 3}   -- 테이블
#list                    -- 길이
table.insert(list, 4)    -- 추가
table.sort(list)         -- 정렬
for i = 1, 3 do ... end  -- 반복
'a' .. 'b'               -- 문자열 연결
redis.call('SET', key, value)  -- Redis 명령 호출
```

---

## 3. Sorted Set 복습 (Queue Platform 핵심 자료구조)

```
키: queue:tenant1:queueA:0  (슬라이스 0)
원소: tokenId → score(seq)

ZADD key score value           추가
ZADD key NX score value        없을 때만 추가 (중복 방지)
ZSCORE key value               value의 score 조회
ZCOUNT key min max             score 범위 내 개수
ZRANGE key start end           인덱스 범위 (작은 score부터)
ZREM key value1 value2 ...     제거 (multi-member)
```

ZCOUNT 경계:
- `ZCOUNT key 0 5` → 0 ≤ score ≤ 5 (포함)
- `ZCOUNT key 0 (5` → 0 ≤ score < 5 (제외)

---

## 4. 슬라이스 구조 이해

```
하나의 큰 Sorted Set (100만 명) → ZADD/ZREM 느림 O(log N)
→ 슬라이스 3개로 나눠 각 33만 명 → 빠름

분배 공식 (라운드로빈):
  slice = (seq - 1) % sliceCount
  
  seq=1 → slice 0
  seq=2 → slice 1
  seq=3 → slice 2
  seq=4 → slice 0  ← 한 바퀴 돌고
  ...
```

**핵심**: slice 위치는 부하 분산용 분배 키일 뿐, 순서 결정과 무관.
순서는 score(seq)만 결정.

---

## Script 1: Ranking Lua

### 목적
"내 토큰이 전체에서 몇 등이야?"

### 코드

```lua
-- KEYS[1..3] = queue:t:q:0, :1, :2 (슬라이스)
-- ARGV[1] = tokenId

local mySeq = redis.call('ZSCORE', KEYS[1], ARGV[1])
              or redis.call('ZSCORE', KEYS[2], ARGV[1])
              or redis.call('ZSCORE', KEYS[3], ARGV[1])

if not mySeq then
    return -1
end

mySeq = tonumber(mySeq)

local rank = 0
for i = 1, 3 do
    rank = rank + redis.call('ZCOUNT', KEYS[i], '0', '(' .. mySeq)
end

return rank + 1
```

### 해부

1. **ZSCORE 3번 시도**: 토큰이 어느 슬라이스에 있는지 모르므로
    - `or` 단락 평가: 왼쪽이 nil이면 오른쪽 시도
    - 한 곳에서 찾으면 더 안 봄

2. **`(mySeq` 표현**: `(`은 exclusive (해당 값 제외)
    - 내 자신은 빼고 내 앞의 사람만 셈

3. **ZCOUNT 합산**: 모든 슬라이스에서 내 앞 사람 수 합산
    - slice 위치 무관, score 작은 것 = 내 앞 사람

### 시뮬레이션

```
queue:t:q:0:  tA(1), tD(4), tG(7), tJ(10)
queue:t:q:1:  tB(2), tE(5), tH(8), tK(11)
queue:t:q:2:  tC(3), tF(6), tI(9), tL(12)

tokenId=tE 호출:
  mySeq = 5
  slice 0: ZCOUNT 0 (5 = 2 (tA, tD)
  slice 1: ZCOUNT 0 (5 = 1 (tB)
  slice 2: ZCOUNT 0 (5 = 1 (tC)
  rank = 4 + 1 = 5등 ✅
```

---

## Script 2: Enqueue Bulk Lua

### 목적
대량 enqueue를 한 번에 처리 (500건 단위로 묶음)

### 효과
```
순진한 방식 (1만 enqueue): 20,000 ops/초
Bulk Lua (500건씩):         40 ops/초
→ 500배 효율
```

### 코드

```lua
-- KEYS[1] = global-seq:t:q
-- KEYS[2..N] = queue:t:q:0, :1, ... (슬라이스)
-- ARGV[1] = batchSize
-- ARGV[2] = sliceCount
-- ARGV[3+] = tokenId 들

local batchSize = tonumber(ARGV[1])
local sliceCount = tonumber(ARGV[2])
local endSeq = redis.call('INCRBY', KEYS[1], batchSize)
local startSeq = endSeq - batchSize + 1

local results = {}
for i = 1, batchSize do
    local seq = startSeq + i - 1
    local tokenId = ARGV[2 + i]
    local sliceIdx = (seq - 1) % sliceCount
    local sliceKey = KEYS[2 + sliceIdx]
    redis.call('ZADD', sliceKey, 'NX', seq, tokenId)
    results[i] = seq
end

return results
```

### 해부

1. **INCRBY 블록 채번**: 한 번에 N개 seq 발급
    - 원자적 → 다른 클라이언트와 절대 충돌 없음

2. **`(seq - 1) % sliceCount`**: 라운드로빈 분배
    - 상태 없음 (어디까지 갔는지 기억 안 함)
    - seq 자체가 분배 키

3. **`ZADD NX`**: 중복 방지
    - 같은 tokenId 두 번 들어와도 안전

### 면접 포인트

> "Enqueue 폭증 시 1만 건의 개별 INCRBY + ZADD는 Redis 부하 폭증.
> BlockingQueue로 500건씩 모아 1번의 EVAL로 처리하면 40 ops로 끝남.
> 500배 효율로 폭증 트래픽 흡수 가능."

---

## Script 3: Admit Dequeue Lua

### 목적
앞 N명을 뽑아서 큐에서 제거 (FIFO 보장)

### 핵심 도전
"전체에서 앞 N명" 어떻게 뽑나? → **Over-fetch + Lua 내 정렬**

### 코드

```lua
-- KEYS[1..N] = queue:t:q:0, :1, :2 (슬라이스)
-- ARGV[1] = count (뽑을 인원)
-- ARGV[2] = sliceCount

local count = tonumber(ARGV[1])
local sliceCount = tonumber(ARGV[2])

-- Step 1: Over-fetch (각 슬라이스에서 count명씩)
local candidates = {}
for i = 1, sliceCount do
    local items = redis.call('ZRANGE', KEYS[i], 0, count - 1, 'WITHSCORES')
    for j = 1, #items, 2 do
        table.insert(candidates, {
            tokenId = items[j],
            score = tonumber(items[j + 1]),
            sliceIdx = i
        })
    end
end

if #candidates == 0 then
    return {}
end

-- Step 2: score 기준 정렬
table.sort(candidates, function(a, b)
    return a.score < b.score
end)

-- Step 3: 상위 count개 선택 + 슬라이스별 분류
local selected = {}
local toRemoveBySlice = {}
for i = 1, sliceCount do
    toRemoveBySlice[i] = {}
end

local actualCount = math.min(count, #candidates)
for i = 1, actualCount do
    local item = candidates[i]
    table.insert(selected, item.tokenId)
    table.insert(toRemoveBySlice[item.sliceIdx], item.tokenId)
end

-- Step 4: 슬라이스별 ZREM (multi-member)
for i = 1, sliceCount do
    if #toRemoveBySlice[i] > 0 then
        redis.call('ZREM', KEYS[i], unpack(toRemoveBySlice[i]))
    end
end

return selected
```

### 해부

1. **Over-fetch (각 슬라이스 N명씩)**: 안전 마진
    - 한 슬라이스에 토큰 몰려있어도 누락 없음
    - 수학적 증명: 진짜 N등은 자기 슬라이스 N번째 이내에 있음

2. **Lua 내 정렬**: score 기준
    - slice 위치 무관
    - 진짜 FIFO 보장

3. **슬라이스별 ZREM**: multi-member로 한 번에
    - 1만 명 dequeue 시 1만 호출 → 3 호출로 감소

### "Slice 차례를 기억해야 한다"는 오해

순진한 발상:
```
"Slice 0부터 dequeue, 다음은 slice 1, 다음은 slice 2"
```

→ 이렇게 하면 FIFO 깨짐!

예시:
```
slice 0: 4, 7
slice 1: 5, 8
slice 2: 3, 6, 9   ← 1등(seq=3)이 여기 있음

순진한 방식: slice 0의 4를 먼저 가져옴 ❌
Lua 방식: 정렬 후 3 먼저 가져옴 ✅
```

### 면접 포인트

> "Admit Lua의 핵심은 'Over-fetch + 정렬'입니다.
> Slice 위치 기반 라운드로빈으로 dequeue하면 FIFO가 깨집니다.
> 모든 후보를 score로 정렬한 후 상위 N개를 선택하면 항상 정확합니다.
> 상태가 없어서 동시성 안전이고, 슬라이스 비어있어도 자연스럽게 처리됩니다."

---

## 5. 공통 패턴 정리

```
Queue Platform Lua Script:

1. KEYS[]에는 Redis Key, ARGV[]에는 값
2. tonumber()로 문자열 → 숫자 변환
3. 빈 큐 / nil 처리 (안전성)
4. 다수 ZADD/ZREM은 multi-member로 묶기
5. INCRBY는 원자 채번에 활용
6. Slice 위치는 부하 분산, score가 우선순위
```

---

## 6. 구현 시 주의사항

1. **KEYS/ARGV 위치 일관성**:
    - Java에서 호출할 때 순서 맞추기
    - 잘못 넘기면 Cluster 환경에서 라우팅 실패

2. **Script 길이 제한**:
    - 너무 긴 Script는 Redis 전체 블록 (싱글스레드)
    - 보통 ms 단위로 유지

3. **EVALSHA 캐싱**:
    - 매번 Script 전체 전송하지 말고 SHA1 해시로 호출
    - Spring Data Redis가 자동 처리

4. **에러 처리**:
    - Lua 안에서 redis.call() 에러는 즉시 중단
    - 가능한 사전 검증으로 회피