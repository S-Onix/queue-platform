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
-- TTL 설정 (옛 키 자동 정리, 1시간)
redis.call('EXPIRE', key, 3600)

return allowed