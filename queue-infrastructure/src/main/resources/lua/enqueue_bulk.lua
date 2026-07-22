-- enqueue_bulk.lua
-- Queue Platform - Bulk 대기열 진입 처리 (Redis INCR seq 방식)

-- KEYS[1]: queue key (예: queue:{q_bts}:waiting)
-- KEYS[2]: seq key   (예: queue:{q_bts}:seq) — 큐별 전역 순번 카운터
--   중괄호는 Redis Cluster 해시태그(QueueKeys 참조). 두 키가 같은 슬롯에 놓여야
--   Lua가 실행된다 — 없으면 CROSSSLOT 에러.
-- ARGV[1]: maxCapacity (Queue 최대 인원)
-- ARGV[2]: requestCount (Bulk 요청 개수)
-- ARGV[3..]: identifier1, identifier2, ...   (score 없음 — Lua가 INCR로 발급)

-- Returns: {{identifier, status, rank, total}, ...}
--   score는 KEYS[2] INCR로 발급 (단조증가, 유일) → Redis 도달 순서 = rank 순서
--   OK: 정상 추가 (rank 0-based, total 추가 후 크기)
--   EXISTS: 이미 존재 (기존 rank + 현재 total)
--   FULL: Capacity 초과 (rank -1, 현재 total)

-- Step 1: 인자 파싱
local queueKey = KEYS[1]
local seqKey = KEYS[2]
local maxCapacity = tonumber(ARGV[1])
local requestCount = tonumber(ARGV[2])

local currentSize = redis.call('ZCARD', queueKey)
local enqueueResults = {}

-- Step 2: 각 요청 순차 처리
for i = 1, requestCount do
	local identifier = ARGV[2 + i]   -- ARGV[3]부터 identifier (score 없으므로 오프셋 변경)

	if currentSize >= maxCapacity then
	-- FULL 처리
		table.insert(enqueueResults, {identifier, "FULL", -1, currentSize})
	else
	-- score를 INCR로 발급 (큐별 전역 순번, 단조증가)
		local seq = redis.call('INCR', seqKey)

		-- ZADD NX 시도
		local isNew = redis.call('ZADD', queueKey, 'NX', seq, identifier)

		if isNew == 0 then
		-- EXISTS (이미 존재 — INCR한 seq는 버려짐, 순서엔 영향 없음)
			local existingRank = redis.call('ZRANK', queueKey, identifier)
			table.insert(enqueueResults, {identifier, "EXISTS", existingRank, currentSize})
		else
		-- OK
			local newRank = redis.call('ZRANK', queueKey, identifier)
			currentSize = currentSize + 1
			table.insert(enqueueResults, {identifier, "OK", newRank, currentSize})
		end
	end
end

-- Step 3: 결과 반환
return enqueueResults