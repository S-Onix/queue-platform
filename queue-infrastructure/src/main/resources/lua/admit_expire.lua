-- admit_expire.lua
-- Queue Platform - admitToken TTL 만료 → WAITING 복귀 claim (FRS §10 / DECISIONS §36 · §80 ⑧)

-- KEYS[1]: admitted key  (예: queue:{q_bts}:admitted) — ZSet, score=만료 epoch ms, member="seq|identifier"
-- KEYS[2]: waiting key   (예: queue:{q_bts}:waiting)  — ZSet, score=seq, member=identifier
-- KEYS[3]: tokens key    (예: queue:{q_bts}:tokens)   — Hash, identifier -> "tokenId|issuedAt"
-- ARGV[1]: now (epoch ms 문자열). **Java가 넘긴다** — Lua의 TIME은 비결정적이라 쓰지 않는다
-- ARGV[2]: limit (한 번에 집어올 최대 건수)

-- Returns: { {identifier, seq, tokenId, issuedAt}, ... }   (원소 4개 고정)
--   tokenId/issuedAt은 tokens Hash 미스일 때 **빈 문자열**이다. nil/false는 RESP 변환에서
--   뒤를 잘라내므로 "모름"은 반드시 ""로 표현한다 (enqueue_bulk.lua·admit.lua와 같은 규약).

-- 🔴 이 EVAL 자체가 claim이다 — ShedLock도 분산 락도 쓰지 않는다 (§80 ⑧).
--   ZRANGEBYSCORE와 ZREM이 한 스크립트 안에 있으면 Redis 단일 스레드가 둘을 쪼개지 않는다.
--   queue-batch가 N대여도 멤버를 가져가는 것은 한 대뿐이고 나머지는 빈 배열을 받는다.
--   중복 실행의 대가는 낭비된 EVAL 한 번이지 중복 복귀가 아니다.
--   ⚠️ 그래서 ZRANGEBYSCORE와 ZREM을 Java로 쪼개면 그 순간 이 잡의 유일한 동시성 방어가 사라진다.

local admittedKey = KEYS[1]
local waitingKey = KEYS[2]
local tokensKey = KEYS[3]

local now = ARGV[1]
local limit = tonumber(ARGV[2])

-- LIMIT을 거는 이유는 두 가지다. (1) 아래 ZREM이 unpack으로 인자를 펴므로 Lua 스택 상한
-- (LUAI_MAXCSTACK 약 8000)에 걸리지 않아야 하고, (2) 만료가 한꺼번에 몰려도 Redis 단일 스레드를
-- 오래 붙잡지 않아야 한다(폴링 15만/s가 같은 노드에서 대기한다). 남은 몫은 다음 주기가 가져간다.
local expired = redis.call('ZRANGEBYSCORE', admittedKey, '-inf', now, 'LIMIT', 0, limit)
if #expired == 0 then
	return {}
end

redis.call('ZREM', admittedKey, unpack(expired))

local records = {}

for i = 1, #expired do
	local member = expired[i]

	-- ⚠️ **첫 '|'로만 쪼갠다.** identifier는 Tenant가 만드는 자유 문자열이고(FRS는 형식 가이드만
	--    제시하고 검증은 Tenant 책임이라 적었다) 서버 검증은 @NotBlank + @Size(max=100)뿐이라
	--    '|'가 들어올 수 있다. seq는 숫자라 '|'를 포함할 수 없으므로 **첫 '|'가 정확한 경계**다.
	--    오른쪽 기준으로 쪼개거나 split('|')로 "단순화"하면 identifier가 잘린다.
	--    같은 규약이 enqueue_bulk.lua의 splitTokenValue에도 있다.
	local sep = string.find(member, '|', 1, true)

	if sep then
		local seq = string.sub(member, 1, sep - 1)   -- 문자열 그대로. tonumber를 거치면 Lua 숫자
		                                             -- 포맷(%.14g)이 섞여 원래 score와 어긋난다
		local identifier = string.sub(member, sep + 1)

		-- 🔴 원래 seq 그대로 되돌린다. 이것이 §36의 핵심이다 — 새 seq를 받으면 60초 기다린
		--    사람이 맨 뒤로 밀린다. ZADD(NX 아님)인 이유: 같은 identifier가 이미 대기 중이면
		--    그건 재입장한 사람이고, 그 사람의 진짜 순번은 더 앞선 이 seq다.
		redis.call('ZADD', waitingKey, seq, identifier)

		-- RETURNED 이벤트의 멱등 키가 (token_id, issued_at)이라 둘 다 필요하다.
		-- 미스면 빈 문자열로 두고 Java가 발행을 건너뛴다 — 추측해 채우면 같은 토큰의 두 번째
		-- 행이 생긴다(admit.lua의 issuedAt 주석과 같은 이유).
		local tokenId = ''
		local issuedAt = ''
		local stored = redis.call('HGET', tokensKey, identifier)
		if stored then
			local s = string.find(stored, '|', 1, true)
			if s then
				tokenId = string.sub(stored, 1, s - 1)
				issuedAt = string.sub(stored, s + 1)
			end
			-- 구분자 없는 레거시 값은 issuedAt을 복원할 수 없다. tokenId만 알아도 발행하지
			-- 못하므로(멱등 키가 성립하지 않는다) 통째로 미스 취급한다 (§80 Consequences).
		end

		table.insert(records, { identifier, seq, tokenId, issuedAt })
	end
	-- sep이 없는 멤버는 admit.lua가 만들 수 없는 형태다(항상 seq..'|'..identifier로 넣는다).
	-- 위에서 이미 ZREM됐고 되돌릴 seq를 알 수 없으므로 그대로 버린다. 남겨두면 주기마다
	-- 집었다 되돌리는 무한 순환이 된다.
end

return records
