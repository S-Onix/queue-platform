-- cleanup_completed.lua
-- Queue Platform - complete 처리 뒤 Redis 상태 정리 (DECISIONS §36 · §80 · §82)

-- KEYS[1]: waiting key        (예: queue:{q_bts}:waiting)              — ZSet, score=seq, member=identifier
-- KEYS[2]: admitted key       (예: queue:{q_bts}:admitted)             — ZSet, member="seq|identifier"
-- KEYS[3]: tokens key         (예: queue:{q_bts}:tokens)               — Hash, identifier -> "tokenId|issuedAt"
-- KEYS[4]: admit-by-token key (예: queue:{q_bts}:admit-by-token:tok_x) — String
-- KEYS[5]: admit-by-admit key (예: queue:{q_bts}:admit-by-admit:adm_x) — String  **선택(§92)**
--   🔴 KEYS[5] 는 complete 만 넘긴다. verify 는 admit-by-admit 을 **일부러 남긴다** — verify 재시도와
--   complete 폴백의 유일한 근거라서다(PX 60s 가 거둔다). 나머지 넷은 verify·complete 둘 다 지운다 — 남기면 완료자가
--   옛 토큰으로 재입장하고 과금이 경로마다 갈린다(§92).
--   다섯 키를 Java 가 실행 전에 다 알아 KEYS 로 선언한다 → CROSSSLOT 사전 검사가 실제로 걸린다. §96-13
-- ARGV[1]: identifier
-- ARGV[2]: seq (문자열 원문. Java가 Long.toString으로 넘긴다)
-- ARGV[3]: tokenId (완료를 신청한 회차)

-- Returns: 1  자기 회차를 정리했다
--          0  🔴 이미 **다른 회차**가 자리를 차지하고 있어 건드리지 않았다 (가드가 막은 것)
--         -1  정리할 게 애초에 없었다 (이미 정리됐거나 고아)
--   ⚠️ 0과 -1을 합치지 마라 — 합치면 "축출을 막았다" WARN 이 아무 일 없던 경우에도 찍혀 빈도가 의미를 잃는다.
--      이 카운트가 아래 "240초 창"(입장권 60초 vs complete 300초)이 실제로 얼마나 열리는지 재는 유일한 수단이다.

-- 🔴 **왜 회차 대조가 필요한가.** identifier 는 회차 간에 재사용된다. 입장권이 만료되면(60초) 중복 게이트(tokens Hash 필드)가 풀려
--   곧바로 **새 회차**를 받는데, complete 창은 300초다 — 그 240초 안에 옛 회차의 늦은 complete 가 오면
--   identifier 만 보고 지울 경우 **새 회차의 자리와 게이트를 지운다**(피해자에게 신호도 없다).
-- 🔴 **왜 Lua 인가.** Java 에서 HGET → 비교 → HDEL 로 쪼개면 같은 결함이 TOCTOU 로 재발하고, 중간에 죽으면
--   어느 회수 배치도 닿지 못하는 영구 락아웃이 된다. EVAL 1회면 그 중간 상태가 없다. §96-14

local identifier = ARGV[1]
local seq = ARGV[2]   -- 문자열 그대로 쓴다. tonumber를 거치면 Lua 숫자 포맷(%.14g)이 섞여
                      -- admit.lua가 ZADD한 member("seq|identifier")와 바이트가 어긋난다.
local tokenId = ARGV[3]

-- ── 회차 고유 키는 무조건 지운다 ──────────────────────────────────────────────
-- member 의 seq, 키의 tokenId/admitToken 은 회차마다 유일해(INCR · UUIDv7) 남의 회차를 지울 수 없다 — 대조가 필요 없다.
-- 🔴 여기에 회차 가드를 걸면 HGET 미스 경로에서 이 셋이 남는다. admit-by-admit(KEYS[5])은 verify 가 안 넘긴다(재시도 계약).
redis.call('ZREM', KEYS[2], seq .. '|' .. identifier)
redis.call('DEL', KEYS[4])
if KEYS[5] then redis.call('DEL', KEYS[5]) end

-- ── 사람 키는 지금 그 사람이 어느 회차인지 물어본 뒤에 지운다 ─────────────────
local stored = redis.call('HGET', KEYS[3], identifier)
if not stored then
	-- 이미 정리됐거나 애초에 없다. waiting에 남아 있더라도 그건 tokens 미스인 고아이고
	-- (admit.lua가 되돌려 놓은 자) 그 사람은 admitToken을 못 받아 complete에 도달할 수 없다.
	-- 즉 이 complete의 소유가 아니다. 건드리지 않는다.
	return -1
end

-- 🔴 구분자가 없으면 **값 전체를 tokenId로 본다** (poll_verify.lua·enqueue_bulk.lua와 같은 규약).
--    "미스 취급"이면 구 포맷 값에서 완료자의 게이트가 영영 안 풀려 영구 락아웃이 된다.
--    (admit_expire·inactive_expire 는 반대 규약 — issuedAt 없이는 멱등 키가 안 서서 발행 자체가 불가능하다)
local sep = string.find(stored, '|', 1, true)
local storedTokenId = stored
if sep then storedTokenId = string.sub(stored, 1, sep - 1) end

-- tokenId는 'tok_' + UUIDv7이라 '|'를 포함하지 않는다 → 앞조각이 같으면 같은 회차다.
if storedTokenId ~= tokenId then
	return 0
end

-- 자기 회차 확인. 여기 오는 waiting 멤버는 §36으로 복귀가 폐기된 뒤로는 사실상 없다
-- (admit이 ZPOPMIN으로 이미 뺐다). 그래도 남겨 두는 이유는 §71의 DB→Redis 복원처럼
-- waiting을 재구성하는 경로가 생겼을 때 자기 회차 잔재를 자동으로 치우기 위해서다.
redis.call('ZREM', KEYS[1], identifier)

-- 🔴 **HDEL은 마지막이다.** 원자 실행이라 "중간에 죽는" 경우는 없어졌지만, 순서 규칙 자체는
--    유지한다. 먼저 지우면 같은 스크립트 안에서도 이후 명령이 tokens 미스를 전제로 돌게 되고,
--    무엇보다 이 파일을 다시 명령 여러 개로 쪼개려는 사람이 순서까지 함께 잃는다.
--    (게이트를 먼저 풀면 waiting에 남은 채 Hash만 사라져 poll_verify가 영영 0을 준다)
redis.call('HDEL', KEYS[3], identifier)

return 1
