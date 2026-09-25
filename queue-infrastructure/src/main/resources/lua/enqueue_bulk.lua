-- enqueue_bulk.lua
-- Queue Platform - Bulk 대기열 진입 처리 (Redis INCR seq 방식)

-- KEYS[1]: queue key (예: queue:{q_bts}:waiting)
-- KEYS[2]: seq key   (예: queue:{q_bts}:seq) — 큐별 전역 순번 카운터
-- KEYS[3]: token key (예: queue:{q_bts}:tokens) — identifier -> "tokenId|issuedAt" 매핑 Hash
--   {q_bts} 중괄호는 Cluster 해시태그다 — 세 키가 같은 슬롯이어야 실행된다(없으면 CROSSSLOT).
--   score 는 KEYS[2] 의 INCR 로 발급한다(단조증가·유일).
--   🔴 **중복 게이트는 이 Hash 다**(waiting ZSet 이 아니다). admit 되면 waiting 에서 빠지므로 그걸로 판정하면
--   재-enqueue 가 새 tokenId·새 seq 를 받아 과금이 중복된다. HDEL 은 완료(cleanupCompleted·cleanupVerified)와 회수 배치만 한다. §96-15
-- ARGV[1]: maxCapacity (Queue 최대 인원)
-- ARGV[2]: requestCount (Bulk 요청 개수)
-- ARGV[3]: issuedAt (이 청크의 발급 시각, epoch millis — DB 포맷을 Java 가 통제하려고 Lua 에서 만들지 않는다)
-- ARGV[4..]: identifier1, tokenId1, identifier2, tokenId2, ...   (아이템당 2개. tokenId 는 OK 일 때만 채택)

-- Returns: {{identifier, tokenId, status, rank, total, seq, issuedAt}, ...}   (원소 7개 고정)
--   OK: 정상 추가 · EXISTS: 이미 있음(tokenId·issuedAt 은 Hash 의 최초 값) · FULL: 정원 초과(tokenId·issuedAt = "")
--   ※ admit 됐지만 완료하지 않은 사람의 재-enqueue 는 EXISTS 이면서 rank·seq = -1(폴링이 입장권을 돌려준다).
--     완료한 사람은 게이트가 지워져 OK(새 tokenId·맨 뒤·과금 +1)다 — §92.
--   ※ "모름"은 반드시 "" 로 — nil/false 는 RESP 변환에서 배열 뒤를 끊는다(Java 의 size() < 7 검사가 전제). §96-16

-- issuedAt을 Hash에 함께 저장하는 이유:
--   tokens 테이블의 UNIQUE KEY가 (token_id, issued_at)이라 issuedAt이 다르면 같은 토큰도 다른 행이 된다
--   (ENQUEUE_INSERT 의 ODKU 가 흡수하지 못한다). 뒤의 ADMITTED(admit.lua)·EXPIRED(회수 배치) 발행이
--   ENQUEUED 와 같은 issuedAt 을 실어야 같은 행을 갱신하므로, 발급 시점에 Redis에도 남긴다.

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

-- 이미 발급받은 사람의 응답. **두 곳에서 쓴다** — 정원에 여유가 있을 때(HSETNX가 0)와
-- 정원이 찼을 때(아래 HGET 히트). 한 벌로 두지 않으면 두 경로의 응답 모양이 갈린다.
local function existsResult(identifier, existingValue)
	local existingToken, existingIssuedAt = splitTokenValue(existingValue)
	-- 🔴 admit된 사람은 waiting에 없어 ZRANK/ZSCORE가 false를 준다. false를 그대로
	--    테이블에 넣으면 RESP 변환이 배열을 그 자리에서 잘라 Java의 size() < 7 검사가
	--    터지고, 청크(최대 500건)가 통째로 실패한다.
	local existingRank = redis.call('ZRANK', queueKey, identifier)
	if not existingRank then existingRank = -1 end   -- nil-truncation 방어
	local existingSeq = tonumber(redis.call('ZSCORE', queueKey, identifier))
	if not existingSeq then existingSeq = -1 end     -- nil-truncation 방어
	return {identifier, existingToken, "EXISTS", existingRank, currentSize, existingSeq, existingIssuedAt}
end

-- Step 2: 각 요청 순차 처리
for i = 1, requestCount do
	local identifier = ARGV[2 * i + 2]   -- ARGV[4]부터 identifier (ARGV[3]을 issuedAt이 차지)
	local tokenId = ARGV[2 * i + 3]

	if currentSize >= maxCapacity then
	-- 🔴 정원이 찼어도 **이미 발급받은 사람은 FULL이 아니다** — 새로고침으로 같은 identifier 가 오는 것은 정상이다.
	--    FULL 을 주면 줄 맨 앞에서 기다리던 사람에게 마감 페이지가 뜬다(실측 재현).
	-- 🪤 HSETNX 를 먼저 부르면 안 된다 — 정원이 찬 상태에서 신규까지 게이트(=과금 게이트)에 심긴다. 여기선 HGET 으로 읽기만 한다.
		local existingValue = redis.call('HGET', tokenKey, identifier)
		if existingValue then
			table.insert(enqueueResults, existsResult(identifier, existingValue))
		else
	-- FULL 처리 (tokenId 자리는 빈 문자열 — 응답 전 Service에서 예외 처리됨)
			table.insert(enqueueResults, {identifier, "", "FULL", -1, currentSize, -1, ""})
		end
	else
	-- score를 INCR로 발급 (큐별 전역 순번, 단조증가)
		local seq = redis.call('INCR', seqKey)
		-- 게이트는 tokens Hash다 (KEYS[3] 주석 참조). HSETNX가 0이면 이미 발급된 사람이다.
		local isNew = redis.call('HSETNX', tokenKey, identifier, tokenId .. '|' .. issuedAt)

		if isNew == 0 then
		-- EXISTS (이미 존재 — INCR한 seq는 버려짐, 순서엔 영향 없음)
			-- HSETNX는 기존 값을 돌려주지 않는다. 최초 tokenId·issuedAt을 알려면 HGET이 필수다.
			table.insert(enqueueResults, existsResult(identifier, redis.call('HGET', tokenKey, identifier)))
		else
		-- OK
			-- NX를 붙이지 않는다. 게이트를 이미 HSETNX가 통과시켰으므로, waiting에 같은
			-- identifier가 남아 있다면 그건 admit.lua가 HGET 미스로 되돌려 놓은 고아다.
			-- NX로 옛 score를 살려두면 응답의 seq와 실제 score가 갈려 폴링이 자기 항목을 못 찾는다.
			redis.call('ZADD', queueKey, seq, identifier)
			local newRank = redis.call('ZRANK', queueKey, identifier)
			currentSize = currentSize + 1
			table.insert(enqueueResults, {identifier, tokenId, "OK", newRank, currentSize, seq, issuedAt})
		end
	end
end

-- Step 3: 결과 반환
return enqueueResults