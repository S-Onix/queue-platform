-- token-bucket.lua
-- Token Bucket 알고리즘의 Redis 원자 실행
--
-- KEYS[1]: 양동이 키 (예: "rl:tenant:1")
-- ARGV[1]: capacity (양동이 크기)
-- ARGV[2]: refillRatePerSecond (초당 회복 토큰)
-- ARGV[3]: nowMillis (현재 시간, ms)
--
-- 반환: 1 = 허용, 0 = 거부

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillRate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

-- 1) 양동이 상태 읽기 (Hash로 저장)
local bucket = redis.call('HMGET', key, 'tokens', 'lastRefillMillis')
local tokens = tonumber(bucket[1])
local lastRefillMillis = tonumber(bucket[2])

-- 2) 첫 요청 처리 (양동이 없음)
if tokens == nil then
    tokens = capacity
    lastRefillMillis = now
end

-- 3) 경과 시간만큼 토큰 회복
local elapsedMillis = now - lastRefillMillis
local refilled = elapsedMillis * refillRate / 1000.0
local currentTokens = math.min(capacity, tokens + refilled)

-- 4) 토큰 차감 시도
local allowed
local newTokens
if currentTokens >= 1.0 then
    allowed = 1
    newTokens = currentTokens - 1.0
else
    allowed = 0
    newTokens = currentTokens
end

-- 5) 양동이 상태 저장
redis.call('HMSET', key, 'tokens', newTokens, 'lastRefillMillis', now)
-- TTL: 버킷이 가득 회복된 뒤의 상태는 키가 없을 때(= capacity로 시작)와 결과가 같다.
-- 그래서 full refill 시간만 버티면 되고, 그 위 60초는 경계 여유다.
-- 3600 고정은 폴링 키(rl:poll:token:*)를 토큰 하나당 1시간씩 남겼다. 폴링은 토큰 수만큼
-- 키가 생기므로 이 상수가 곧 Redis 메모리 상한이 된다 — 큐 비종속 키라 Cluster 전환 시
-- cluster1 한 곳에 전부 몰린다(DECISIONS §75 D27-4).
--   폴링(cap 5, refill 1/s)      → 65초
--   Tenant plan(cap = refill×60) → 120초
--
-- 60~3600으로 조인다. capacity/refillRate는 호출자가 넘기는 값이라 이 스크립트가 통제하지 못한다.
--   refillRate = 0  → inf    → 상한 3600. 클램프가 없으면 EXPIRE 인자 오류로 스크립트가 죽는데,
--                              위 HMSET은 이미 커밋된 뒤라 TTL 없는 키가 영구히 남는다
--   refillRate 극소 → 수만 초 → 상한 3600. 줄이려던 메모리가 도리어 늘어나는 것을 막는다
--   refillRate < 0  → 음수   → 하한 60. 음수 EXPIRE는 키를 즉시 지워 한도를 리셋시킨다
redis.call('EXPIRE', key, math.max(60, math.min(3600, math.ceil(capacity / refillRate) + 60)))

return allowed