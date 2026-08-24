-- admit.lua
-- Queue Platform - Admit 전 구간 원자 처리 (FRS §6.4 / DECISIONS §80)

-- KEYS[1]: waiting key    (예: queue:{q_bts}:waiting)         — ZSet, score=seq, member=identifier
-- KEYS[2]: tokens key     (예: queue:{q_bts}:tokens)          — Hash, identifier -> "tokenId|issuedAt"
-- KEYS[3]: admitted key   (예: queue:{q_bts}:admitted)        — ZSet, score=만료 epoch ms, member="seq|identifier"
-- KEYS[4]: watermark key  (예: queue:{q_bts}:admit-watermark) — String, 마지막 admit seq (§79 전광판 원본)
-- ARGV[1]: count (뽑을 인원. 상한 100은 API DTO의 @Max(100)이 강제한다 — 여기서 다시 막지 않는다)
-- ARGV[2]: expiresAt (admitToken 만료 epoch ms 문자열) — admitted ZSet의 score
-- ARGV[3]: admitTtlMillis (admit-by-* 키의 PX. 60000)
-- ARGV[4]: idemKey     — queue:{q}:admit-idem:{requestId} **완성 키** (QueueKeys.admitIdem)
-- ARGV[5]: idemTtlMillis (300000)
-- ARGV[6]: admitByTokenPrefix — queue:{q}:admit-by-token:   (QueueKeys.admitByTokenPrefix)
-- ARGV[7]: admitByAdmitPrefix — queue:{q}:admit-by-admit:   (QueueKeys.admitByAdmitPrefix)
-- ARGV[8..]: admitToken 후보 N개 (Java가 UUIDv7로 미리 만든 것. 채택될 때만 쓰고 남으면 버린다)

-- Returns: { status, { {identifier, tokenId, seq, admitToken, issuedAt}, ... } }   (원소 5개 고정)
--   status = "OK"    정상 처리 (레코드 0건일 수 있다 — 대기열이 비었으면 result=empty)
--   status = "REPLAY" 같은 requestId의 재시도 — 저장해 둔 payload를 그대로 돌려준다
--   ※ 빈 문자열/빈 배열은 배열을 자르지 않는다. nil/false만 RESP 변환에서 뒤를 끊으므로
--     "없음"은 반드시 ""/{} 로 표현할 것 (enqueue_bulk.lua와 같은 규약).

-- ⚠️ 동적 키(admit-by-token / admit-by-admit / admit-idem)의 **접두사를 이 파일에 박지 않는다** (§80 ⑥).
--   두 번째 조각이 런타임 값이라 KEYS[] 선언이 원리적으로 불가능하고, 선언이 없으면
--   Redis의 CROSSSLOT 사전 검사가 아예 걸리지 않는다(선언 없는 접근은
--   "ERR Script attempted to access a non local key" — 노드 소유 여부만 본다).
--   즉 **슬롯이 달라도 같은 노드면 조용히 성공**한다(마스터 4대 = 약 25%). 남는 방어는
--   QueueKeys를 리플렉션 전수 열거하는 QueueKeysSlotTest뿐이라, 접두사가 여기 있으면 그 단언이 닿지 못한다.

-- ⚠️ admitToken과 시각을 Lua에서 만들지 않는 이유:
--   admitToken은 UUIDv7(랜덤)이라 스크립트가 비결정적이 되고, 만료 시각은 DB(tokens.admitted_at)에
--   저장될 값이라 포맷을 Java가 통제해야 한다 (enqueue_bulk.lua ARGV[3]과 같은 이유).

local waitingKey = KEYS[1]
local tokensKey = KEYS[2]
local admittedKey = KEYS[3]
local watermarkKey = KEYS[4]

local count = tonumber(ARGV[1])
local expiresAt = ARGV[2]
local admitTtl = ARGV[3]
local idemKey = ARGV[4]
local idemTtl = ARGV[5]
local byTokenPrefix = ARGV[6]
local byAdmitPrefix = ARGV[7]
local ADMIT_TOKEN_OFFSET = 7   -- 후보는 ARGV[8]부터

-- Step 1: 멱등 — 같은 requestId면 저장된 payload를 그대로 돌려준다 (대기열은 건드리지 않는다)
local cached = redis.call('GET', idemKey)
if cached then
	return { 'REPLAY', cjson.decode(cached) }
end

-- Step 2: 앞에서 N명 pop. ZSet 하나(§66 D2) + score가 INCR 단조증가(§70 D9)라 이미 FIFO이고
--   거를 대상이 없으므로 ZRANGE+ZREM이 아니라 ZPOPMIN 한 명령이다 (§80).
--   반환은 평탄 배열 {member1, score1, member2, score2, ...}.
local popped = redis.call('ZPOPMIN', waitingKey, count)

local records = {}
local maxSeq = nil

for i = 1, #popped, 2 do
	local identifier = popped[i]
	local seq = popped[i + 1]   -- 문자열 그대로 쓴다. tonumber를 거치면 Lua 숫자 포맷(%.14g)이
	                            -- 섞여 Java가 만든 값과 어긋난다 (poll_verify.lua와 같은 이유).
	local stored = redis.call('HGET', tokensKey, identifier)

	local sep = nil
	if stored then sep = string.find(stored, '|', 1, true) end

	if not sep then
		-- HGET 미스 또는 레거시(구분자 없는 값) → 원래 seq로 되돌리고 건너뛴다.
		-- 되돌리지 않으면 그 사람은 대기열에서 빠진 채 admitToken도 못 받아 사라진다
		-- (§80이 ②중간 DB 확인을 폐기한 이유가 바로 그 사고다). admitted에도 안 넣으므로
		-- Kafka 발행 대상도 아니다 — TTL 만료 복귀와는 다른 경로다.
		redis.call('ZADD', waitingKey, seq, identifier)
	else
		local tokenId = string.sub(stored, 1, sep - 1)
		-- issuedAt(epoch ms 문자열)도 함께 싣는다. ADMITTED Kafka 이벤트의 멱등 키가
		-- UNIQUE(token_id, issued_at)이고 tokens 테이블의 파티션 키이기도 해서, 이 값이 없으면
		-- 컨슈머가 같은 토큰의 두 번째 행을 만든다. 어차피 위에서 HGET으로 읽은 값이라
		-- 여기서 버리면 Java가 HMGET으로 한 번 더 읽어야 한다(그 우회가 U5의 findIssuedAt이었다).
		-- 뒷조각이 비어 있어도 nil이 아니라 빈 문자열이라 배열이 잘리지 않는다.
		local issuedAt = string.sub(stored, sep + 1)
		local admitToken = ARGV[ADMIT_TOKEN_OFFSET + (i + 1) / 2]

		redis.call('SET', byTokenPrefix .. tokenId, admitToken, 'PX', admitTtl)
		-- 값이 "tokenId|seq|issuedAt|identifier"인 이유: verify가 이 네 값만으로 답과 완료 처리를
		-- 모두 끝내 **DB를 한 번도 읽지 않게** 하기 위해서다.
		--   identifier — Tenant에게 돌려줄 신원. DB에서만 얻으면 컨슈머 백로그 구간(= Kafka 적재가
		--                아직 안 끝난 정상 토큰)에 404가 난다
		--   seq·issuedAt — verify가 COMPLETED 이벤트를 만들 때 필요하다. 없으면 verify가 DB를
		--                읽어야 하고, verify는 @Transactional(readOnly)라 그 읽기가 Replica로 간다
		-- 넷 다 지금 이 자리에 이미 있다 — 새로 조회하는 값이 하나도 없다.
		--
		-- ⚠️ **identifier가 맨 뒤인 것이 규약이다.** identifier는 Tenant 자유 문자열이라 '|'가
		--    들어올 수 있다. 앞 세 값(tokenId='tok_'+UUID, seq=숫자, issuedAt=숫자)에는 '|'가
		--    없으므로, 읽는 쪽은 **앞에서 세 번만 쪼개고 나머지 전부를 identifier로 본다.**
		--    가변 필드를 중간에 두면 경계가 무너진다.
		redis.call('SET', byAdmitPrefix .. admitToken,
			tokenId .. '|' .. seq .. '|' .. issuedAt .. '|' .. identifier, 'PX', admitTtl)
		redis.call('ZADD', admittedKey, expiresAt, seq .. '|' .. identifier)

		table.insert(records, { identifier, tokenId, seq, admitToken, issuedAt })
		maxSeq = seq   -- ZPOPMIN은 오름차순이므로 마지막 성공분이 최대다
	end
end

-- Step 3: watermark는 현재값보다 클 때만 올린다 (§79).
--   admit이 동시에 두 건 돌면 나중 응답이 작은 seq를 들고 올 수 있어 전광판이 뒤로 간다.
if maxSeq then
	local current = redis.call('GET', watermarkKey)
	if (not current) or tonumber(current) < tonumber(maxSeq) then
		redis.call('SET', watermarkKey, maxSeq)
	end
end

-- Step 4: 결과 payload 저장 → 재시도는 Step 1에서 REPLAY.
--   레코드 0건이어도 저장한다. "같은 requestId엔 같은 답"이 멱등의 정의이고,
--   새 인원을 받고 싶으면 Tenant가 새 requestId를 쓰면 된다.
--   문자열 이어붙이기 대신 cjson을 쓰는 이유: identifier는 Tenant가 정하는 자유 문자열이라
--   '|'나 개행이 들어올 수 있다 — 구분자 규약이 그 자리에서 깨진다.
redis.call('SET', idemKey, cjson.encode(records), 'PX', idemTtl)

return { 'OK', records }
