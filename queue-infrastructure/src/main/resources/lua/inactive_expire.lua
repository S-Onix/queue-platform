-- inactive_expire.lua
-- Queue Platform - inactiveTtl 초과 대기자 회수 claim (DECISIONS §82 · FRS §6.7)
--   🔴 §82가 Cancel API를 폐기하면서 **이탈 회수의 유일한 경로**가 됐다. 유저가 취소 버튼을
--   누르든 탭을 닫든 네트워크가 끊기든, Platform이 관측하는 신호는 "폴링이 멈춘다" 하나뿐이다.

-- KEYS[1]: lastActive key (예: queue:{q_bts}:last-active) — ZSet, score=마지막 폴링 ms, member=seq
-- KEYS[2]: waiting key    (예: queue:{q_bts}:waiting)     — ZSet, score=seq, member=identifier
-- KEYS[3]: tokens key     (예: queue:{q_bts}:tokens)      — Hash, identifier -> "tokenId|issuedAt"
--   세 키 모두 QueueKeys가 {queueId} 해시태그를 붙인다. 같은 슬롯이어야 Cluster에서 실행된다.
-- ARGV[1]: cutoff (epoch ms 문자열). Java가 now - queue.inactiveTtl*1000 으로 만든다.
--   🔴 큐마다 inactiveTtl이 다르므로 Java가 계산한다 — Lua는 큐 설정을 모른다.
-- ARGV[2]: limit (한 번에 집어올 최대 건수)

-- Returns: { {identifier, seq, tokenId, issuedAt}, ... }   (원소 4개 고정)
--   tokenId/issuedAt은 tokens Hash 미스일 때 **빈 문자열**이다. nil/false는 RESP 변환에서
--   뒤를 잘라내므로 "모름"은 반드시 ""로 표현한다 (enqueue_bulk.lua·admit.lua와 같은 규약).

-- 🔴 이 EVAL 자체가 claim이다 — ShedLock도 분산 락도 쓰지 않는다 (§80 ⑧과 같은 근거).
--   ZRANGEBYSCORE와 ZREM이 한 스크립트 안에 있으면 Redis 단일 스레드가 둘을 쪼개지 않는다.
--   queue-batch가 N대여도 멤버를 가져가는 것은 한 대뿐이고 나머지는 빈 배열을 받는다.
--   ⚠️ 그래서 조회와 제거를 Java로 쪼개면 그 순간 이 잡의 유일한 동시성 방어가 사라진다.

-- ⚠️ admit_expire.lua를 복사할 수 없다. 그쪽 member는 "seq|identifier"라 조회 한 번으로 둘 다
--   나오지만, last-active의 member는 **seq뿐**이다(poll_verify.lua가 그렇게 넣는다).
--   그래서 waiting에서 identifier를 역산하는 단계가 하나 더 필요하다.

local lastActiveKey = KEYS[1]
local waitingKey = KEYS[2]
local tokensKey = KEYS[3]

local cutoff = ARGV[1]
local limit = tonumber(ARGV[2])

-- LIMIT을 거는 이유는 admit_expire.lua와 같다. (1) 아래 ZREM이 unpack으로 인자를 펴므로 Lua
-- 스택 상한(LUAI_MAXCSTACK 약 8000)에 걸리지 않아야 하고, (2) 회수가 몰려도 Redis 단일 스레드를
-- 오래 붙잡으면 같은 노드의 폴링(최대 15만/s)이 함께 밀린다. 남은 몫은 다음 주기가 가져간다.
local stale = redis.call('ZRANGEBYSCORE', lastActiveKey, '-inf', '(' .. cutoff, 'LIMIT', 0, limit)
if #stale == 0 then
	return {}
end

-- 🔴 last-active에서 먼저 뺀다. 이게 claim이다 — 여기까지 오면 다른 인스턴스는 이 멤버를 못 본다.
redis.call('ZREM', lastActiveKey, unpack(stale))

local records = {}

for i = 1, #stale do
	local seq = stale[i]   -- 문자열 그대로. tonumber를 거치면 Lua 숫자 포맷(%.14g)이 섞여
	                       -- ZRANGEBYSCORE의 score 비교와 어긋난다 (poll_verify.lua ARGV[1] 규약)

	-- seq → identifier 역산. seq는 INCR 발급이라 유일하므로 결과는 0 또는 1건이다.
	-- (poll_verify.lua가 같은 방식으로 소유권을 판정한다)
	local members = redis.call('ZRANGEBYSCORE', waitingKey, seq, seq)
	local identifier = members[1]

	if identifier then
		-- 🔴 HGET이 HDEL보다 **먼저**다. 필드를 먼저 지우면 issuedAt 원본을 영영 못 읽고,
		--    추측해 채우면 UNIQUE(token_id, issued_at)에 충돌이 안 나 **같은 토큰의 두 번째 행**이
		--    생긴다. 과금이 상태를 보지 않으므로(§82) 그 행은 한 건 더 청구된다.
		local tokenId = ''
		local issuedAt = ''
		local stored = redis.call('HGET', tokensKey, identifier)
		if stored then
			local s = string.find(stored, '|', 1, true)
			if s then
				tokenId = string.sub(stored, 1, s - 1)
				issuedAt = string.sub(stored, s + 1)
			end
			-- 구분자 없는 레거시 값은 issuedAt을 복원할 수 없다. 멱등 키가 성립하지 않으므로
			-- 통째로 미스 취급한다 (admit_expire.lua와 같은 규약).
		end

		redis.call('ZREM', waitingKey, identifier)
		-- 중복 게이트 해제는 **마지막**이다. 먼저 지우면 waiting에 남은 채 Hash만 사라져
		-- poll_verify가 HGET 미스로 계속 0을 준다 (QueueKeys.tokens 주석의 순서 규칙).
		redis.call('HDEL', tokensKey, identifier)

		table.insert(records, { identifier, seq, tokenId, issuedAt })
	end
	-- 🔴 waiting에 없는 seq는 그냥 버린다 (§36의 "역산 미스 처리 규약").
	--   그 사람은 (ㄱ) admit되어 waiting 밖이거나 (ㄴ) 이미 정리된 사람이다. 둘 다 이 잡이
	--   건드리면 안 되는 대상이다 — (ㄱ)을 지우면 admit 대기자의 중복 게이트가 풀려
	--   재-enqueue가 새 자리를 받고, 원래 자리는 admitted에 남아 유령이 된다.
	--   위에서 이미 last-active를 뺐으므로 stale 멤버가 다음 주기의 LIMIT 앞자리를 먹지 않는다.
end

return records
