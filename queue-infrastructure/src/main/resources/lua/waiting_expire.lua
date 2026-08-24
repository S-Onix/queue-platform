-- waiting_expire.lua
-- Queue Platform - waitingTtl(절대 만료) 초과 대기자 회수 claim (FRS §10 · DECISIONS §82)
--   폴링을 계속하고 있어도 정해진 시간을 넘기면 자리를 비운다. inactiveTtl(이탈 회수)과 달리
--   **폴링으로 리셋되지 않는다** — 그래서 판정 기준이 마지막 폴링 시각이 아니라 발급 시각이다.

-- KEYS[1]: waiting key    (예: queue:{q_bts}:waiting)     — ZSet, score=seq, member=identifier
-- KEYS[2]: tokens key     (예: queue:{q_bts}:tokens)      — Hash, identifier -> "tokenId|issuedAt"
-- KEYS[3]: lastActive key (예: queue:{q_bts}:last-active) — ZSet, score=마지막 폴링 ms, member=seq
--   세 키 모두 QueueKeys가 {queueId} 해시태그를 붙인다. 같은 슬롯이어야 Cluster에서 실행된다.
-- ARGV[1]: cutoff (epoch ms 문자열). Java가 now - queue.waitingTtl*1000 으로 만든다.
--   🔴 큐마다 waitingTtl이 다르므로 Java가 계산한다 — Lua는 큐 설정을 모른다.
-- ARGV[2]: limit (한 번에 **검사할** 최대 건수. 회수 건수가 아니다)

-- Returns: { {identifier, seq, tokenId, issuedAt}, ... }   (원소 4개 고정)
--   inactive_expire.lua와 같은 형식이라 Java의 파싱을 공유한다.

-- 🔴 이 EVAL 자체가 claim이다 — ShedLock도 분산 락도 쓰지 않는다 (§80 ⑧과 같은 근거).
--   조회와 제거가 한 스크립트 안에 있으면 Redis 단일 스레드가 둘을 쪼개지 않는다.

-- ⚠️ **앞부분만 훑는다.** seq는 INCR 발급이라 시간과 단조증가하므로, 오래된 사람은 항상
--   waiting의 앞쪽에 모여 있다. 전수 스캔(HSCAN tokens)이나 별도 timestamp ZSet이 필요 없다 —
--   별도 ZSet은 enqueue 핫패스에 쓰기를 하나 더 얹는 대가가 있고, 그건 §82 A안과 같은 값이다.

-- ⚠️ **조기 종료(early exit)를 하지 않는다.** "첫 미만료에서 멈춘다"가 그럴듯해 보이지만,
--   enqueue_bulk.lua는 issuedAt을 **청크 단위**로 받으므로 청크 실행 순서에 따라 밀리초 단위
--   역전이 가능하다. 7200초 TTL에서 그 역전 자체는 무해하지만, 조기 종료를 넣으면 그것이
--   **영구 누락**으로 바뀐다. 상한까지 전부 검사한다 — 어차피 상한이 비용을 묶는다.

local waitingKey = KEYS[1]
local tokensKey = KEYS[2]
local lastActiveKey = KEYS[3]

local cutoff = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])

-- score 오름차순 = seq 오름차순 = 오래된 순. WITHSCORES로 seq를 함께 받는다
-- (last-active의 member가 seq이고, 반환 record에도 seq가 필요하다).
local head = redis.call('ZRANGE', waitingKey, 0, limit - 1, 'WITHSCORES')

local records = {}

for i = 1, #head, 2 do
	local identifier = head[i]
	local seq = head[i + 1]   -- 문자열 그대로. tonumber를 거치면 Lua 숫자 포맷(%.14g)이 섞여
	                          -- last-active의 member(폴링이 넣은 원문)와 어긋난다.

	-- 🔴 HGET이 HDEL보다 **먼저**다. 필드를 먼저 지우면 issuedAt 원본을 영영 못 읽고,
	--    추측해 채우면 UNIQUE(token_id, issued_at)에 충돌이 안 나 같은 토큰의 두 번째 행이
	--    생긴다. 과금이 상태를 보지 않으므로(§82) 그 행은 한 건 더 청구된다.
	local stored = redis.call('HGET', tokensKey, identifier)

	if stored then
		local s = string.find(stored, '|', 1, true)
		if s then
			local tokenId = string.sub(stored, 1, s - 1)
			local issuedAt = string.sub(stored, s + 1)
			local issuedAtNum = tonumber(issuedAt)

			if issuedAtNum and issuedAtNum < cutoff then
				redis.call('ZREM', waitingKey, identifier)
				-- 중복 게이트 해제는 waiting 제거 **뒤**다. 먼저 지우면 waiting에 남은 채
				-- Hash만 사라져 poll_verify가 HGET 미스로 계속 0을 준다 (QueueKeys.tokens 규칙).
				redis.call('HDEL', tokensKey, identifier)
				-- last-active에서도 뺀다. 남기면 stale 멤버가 inactive sweep의 한도 앞자리를
				-- 매 주기 먹어 진짜 대상을 굶긴다 (inactive_expire.lua가 같은 이유로 먼저 뺀다).
				redis.call('ZREM', lastActiveKey, seq)

				table.insert(records, { identifier, seq, tokenId, issuedAt })
			end
		end
		-- 구분자 없는 레거시 값은 issuedAt을 복원할 수 없어 **만료 판정 자체가 불가능**하다.
		-- 건드리지 않는다 (admit_expire.lua·inactive_expire.lua와 같은 규약).
	end

	-- 🔴 HGET 미스 = **고아**다 (admit.lua가 되돌려 놓은 자). 여기서 건드리지 않는다.
	--   ① issuedAt을 모르므로 만료 판정 자체가 성립하지 않는다.
	--   ② 고아를 이 잡이 조용히 치우면 U9 gauge(queue_waiting_orphans)가 영원히 0이 되어
	--      **탐지 수단이 무력화**된다. 고아는 사람이 보고 판단할 대상이지 sweep이 삼킬 것이 아니다.
	--   ⚠️ 대가: 고아가 head를 점유하면 그 뒤의 진짜 만료 대상이 이 상한 안에 안 들어온다.
	--      바로 그 상황을 U9 gauge가 0이 아닌 값으로 알려준다 — 그게 그 메트릭의 존재 이유다.
end

return records
