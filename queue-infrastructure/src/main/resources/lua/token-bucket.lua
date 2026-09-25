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
-- TTL: 가득 회복된 버킷은 키가 없을 때(= capacity 로 시작)와 결과가 같다 → full refill 시간 + 60초 여유면 된다.
--   폴링(cap 5, refill 1/s) → 65초 · 테넌트(cap 50,000, refill 833.34/s) → 120초.
--   3600 고정 시절엔 폴링 키가 토큰마다 1시간 남아 Redis 메모리 상한이 됐다(§75 D27-4).
-- 60~3600 으로 조인다 — refillRate 0 이면 EXPIRE 오류로 TTL 없는 키가 영구히 남고, 음수면 키를 즉시 지워
--   한도가 리셋된다. 호출자 값이라 이 스크립트가 막는다. §96-18
redis.call('EXPIRE', key, math.max(60, math.min(3600, math.ceil(capacity / refillRate) + 60)))

return allowed