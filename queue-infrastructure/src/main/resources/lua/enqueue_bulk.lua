-- enqueue_bulk.lua
-- Queue Platform - Bulk 대기열 진입 처리 (Redis INCR seq 방식)

-- KEYS[1]: queue key (예: queue:{q_bts}:waiting)
-- KEYS[2]: seq key   (예: queue:{q_bts}:seq) — 큐별 전역 순번 카운터
-- KEYS[3]: token key (예: queue:{q_bts}:tokens) — identifier -> "tokenId|issuedAt" 매핑 Hash
--   중괄호는 Redis Cluster 해시태그(QueueKeys 참조). 세 키가 같은 슬롯에 놓여야
--   Lua가 실행된다 — 없으면 CROSSSLOT 에러.
-- ARGV[1]: maxCapacity (Queue 최대 인원)
-- ARGV[2]: requestCount (Bulk 요청 개수)
-- ARGV[3]: issuedAt (이 청크의 발급 시각, epoch millis 문자열)
--   Lua에서 시각을 만들지 않는 이유: redis.call('TIME')은 비결정적이고, 무엇보다
--   DB에 저장될 포맷을 Java가 통제해야 하기 때문이다.
-- ARGV[4..]: identifier1, tokenId1, identifier2, tokenId2, ...   (아이템당 2개)
--   score는 Lua가 INCR로 발급. tokenId는 Java에서 발급한 후보로,
--   OK일 때만 채택되고 EXISTS/FULL이면 버려진다.

-- Returns: {{identifier, tokenId, status, rank, total, seq, issuedAt}, ...}   (원소 7개 고정)
--   score는 KEYS[2] INCR로 발급 (단조증가, 유일) → Redis 도달 순서 = rank 순서
--   OK: 정상 추가 (rank 0-based, total 추가 후 크기, issuedAt = ARGV[3])
--   EXISTS: 이미 존재 (기존 rank + 현재 total, tokenId·issuedAt은 Hash의 최초 값)
--   FULL: Capacity 초과 (rank -1, 현재 total, tokenId·issuedAt = "")
--   ※ 빈 문자열은 배열을 자르지 않는다. nil/false만 RESP 변환에서 뒤를 끊으므로
--     "모름"은 반드시 ""로 표현할 것 (Java의 size() < 7 검사가 이를 전제한다).

-- issuedAt을 Hash에 함께 저장하는 이유:
--   tokens 테이블의 UNIQUE KEY가 (token_id, issued_at)이라 issuedAt이 다르면 같은
--   토큰도 다른 row가 된다(= @SQLInsert의 ON DUPLICATE KEY가 흡수하지 못함).
--   outbox 항목을 놓쳤을 때 Redis만 보고 복구하려면 issuedAt을 정확히 되살릴 수
--   있어야 하므로, 발급 시점에 Redis에도 남긴다.

-- Step 1: 인자 파싱
local queueKey = KEYS[1]
local seqKey = KEYS[2]
local tokenKey = KEYS[3]
local maxCapacity = tonumber(ARGV[1])
local requestCount = tonumber(ARGV[2])
local issuedAt = ARGV[3]

local currentSize = redis.call('ZCARD', queueKey)
local enqueueResults = {}

-- Hash 값("tokenId|issuedAt")을 분해한다.
-- 구분자가 없으면 issuedAt 도입 이전에 쌓인 값이므로 tokenId만 있는 것으로 취급한다.
local function splitTokenValue(value)
	if not value then return '', '' end
	local sep = string.find(value, '|', 1, true)
	if not sep then return value, '' end
	return string.sub(value, 1, sep - 1), string.sub(value, sep + 1)
end

-- Step 2: 각 요청 순차 처리
for i = 1, requestCount do
	local identifier = ARGV[2 * i + 2]   -- ARGV[4]부터 identifier (ARGV[3]을 issuedAt이 차지)
	local tokenId = ARGV[2 * i + 3]

	if currentSize >= maxCapacity then
	-- FULL 처리 (tokenId 자리는 빈 문자열 — 응답 전 Service에서 예외 처리됨)
		table.insert(enqueueResults, {identifier, "", "FULL", -1, currentSize, -1, ""})
	else
	-- score를 INCR로 발급 (큐별 전역 순번, 단조증가)
		local seq = redis.call('INCR', seqKey)
		-- ZADD NX 시도
		local isNew = redis.call('ZADD', queueKey, 'NX', seq, identifier)

		if isNew == 0 then
		-- EXISTS (이미 존재 — INCR한 seq는 버려짐, 순서엔 영향 없음)
			local existingRank = redis.call('ZRANK', queueKey, identifier)
			local existingToken, existingIssuedAt = splitTokenValue(redis.call('HGET', tokenKey, identifier))
			local existingSeq = tonumber(redis.call('ZSCORE', queueKey, identifier))
			if not existingSeq then existingSeq = -1 end   -- nil-truncation 방어
			table.insert(enqueueResults, {identifier, existingToken, "EXISTS", existingRank, currentSize, existingSeq, existingIssuedAt})
		else
		-- OK
			redis.call('HSET', tokenKey, identifier, tokenId .. '|' .. issuedAt)
			local newRank = redis.call('ZRANK', queueKey, identifier)
			currentSize = currentSize + 1
			table.insert(enqueueResults, {identifier, tokenId, "OK", newRank, currentSize, seq, issuedAt})
		end
	end
end

-- Step 3: 결과 반환
return enqueueResults