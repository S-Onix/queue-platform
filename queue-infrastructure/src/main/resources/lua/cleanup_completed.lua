-- cleanup_completed.lua
-- Queue Platform - complete 처리 뒤 Redis 상태 정리 (DECISIONS §36 · §80 · §82)

-- KEYS[1]: waiting key        (예: queue:{q_bts}:waiting)              — ZSet, score=seq, member=identifier
-- KEYS[2]: admitted key       (예: queue:{q_bts}:admitted)             — ZSet, member="seq|identifier"
-- KEYS[3]: tokens key         (예: queue:{q_bts}:tokens)               — Hash, identifier -> "tokenId|issuedAt"
-- KEYS[4]: admit-by-token key (예: queue:{q_bts}:admit-by-token:tok_x) — String
-- KEYS[5]: admit-by-admit key (예: queue:{q_bts}:admit-by-admit:adm_x) — String
--   다섯 모두 QueueKeys의 정적 팩토리가 {queueId} 해시태그를 붙인다 = 같은 슬롯.
--   ⚠️ admit-by-* 를 admit.lua처럼 ARGV 접두사로 받지 않는다. 여기서는 tokenId·admitToken이 둘 다
--   호출자 손에 있어 **Java가 다섯 키 이름을 실행 전에 전부 안다** — 그러면 KEYS로 선언할 수 있고,
--   선언하면 Redis의 CROSSSLOT 사전 검사가 **실제로 걸린다**(실증: 태그가 다른 키를 섞으면
--   "CROSSSLOT Keys in request don't hash to the same slot"). admit.lua가 경고하는 "선언 없는
--   동적 키는 슬롯이 달라도 같은 노드면 조용히 성공"(마스터 4대 = 약 25%)이 여기선 원천 차단된다.
--   🪤 admit.lua가 왜 ARGV를 쓰는지는 여기서 단정하지 마라 — 그 파일의 admit-by-*는 tokenId가
--   스크립트 안 HGET 결과라 Java가 미리 모르지만, admit-idem은 완성 키인데도 ARGV다(admit.lua:12).
--   근거가 하나로 정리돼 있지 않으니, 이 파일의 선택은 이 파일 사정으로만 정당화한다.
-- ARGV[1]: identifier
-- ARGV[2]: seq (문자열 원문. Java가 Long.toString으로 넘긴다)
-- ARGV[3]: tokenId (완료를 신청한 회차)

-- Returns: 1  자기 회차를 정리했다
--          0  🔴 이미 **다른 회차**가 자리를 차지하고 있어 건드리지 않았다 (가드가 막은 것)
--         -1  정리할 게 애초에 없었다 (이미 정리됐거나 고아)
--   ⚠️ 0과 -1을 합치지 마라. 늦은 complete는 -1로도 오는데(TTL 만료 뒤 재-enqueue 없이 complete),
--      합치면 Java의 WARN이 "축출을 막았다"를 아무 일도 없던 경우에까지 찍어 **빈도 자체가
--      의미를 잃는다**. 이 카운트는 §36(60초)과 complete 창(300초)의 240초 모순이 실제로 얼마나
--      열리는지를 재는 유일한 수단이라, 오탐이 섞이면 재는 의미가 없다.

-- 🔴 **왜 회차 대조가 필요한가.**
--   identifier는 사람 이름표라 회차 간에 재사용된다(같은 사용자 = 같은 UUIDv7). 반면 이 정리는
--   한 회차를 끝내는 일이다. §36이 admitToken TTL 만료 시 tokens Hash를 HDEL해 중복 게이트를
--   풀어주므로, 만료된 사람은 곧바로 재-enqueue해 **새 회차**를 받는다. 그런데 complete의
--   유효 창은 Token.COMPLETE_VALID_WINDOW_SECONDS(300초)이고 admitToken TTL은 60초다 —
--   그 **240초 차이** 동안 옛 회차의 늦은 complete가 도착하면, identifier만 보고 지울 경우
--   **새 회차의 자리(waiting)와 게이트(tokens)를 지운다.** 피해자는 이미 만료로 한 번 손해 본
--   사람이고, 폴링이 조용히 404가 될 뿐 아무 신호가 없다(§4번 항목의 상용 차단 결함).
--
-- 🔴 **왜 Lua여야 하는가 (원자성).**
--   Java에서 HGET → 비교 → HDEL로 쪼개면 그 사이에 admit_expire + 재-enqueue가 끼어들어
--   **같은 결함이 TOCTOU로 재발**한다. 240초 창이 마이크로초 창으로 줄 뿐 사라지지 않는다.
--   부수 효과가 하나 더 있다: 명령 4개 시절에는 ZREM들만 성공하고 HDEL 직전에 프로세스가 죽으면
--   (seq, identifier) 쌍을 아는 자료구조가 **0**이 되어(admitted·waiting 모두 삭제됨) 세 회수
--   배치 어디도 그 사람에게 닿지 못했다 — **해소 경로 없는 영구 락아웃**이었다. EVAL 1회면
--   그 중간 상태 자체가 생기지 않는다.

local identifier = ARGV[1]
local seq = ARGV[2]   -- 문자열 그대로 쓴다. tonumber를 거치면 Lua 숫자 포맷(%.14g)이 섞여
                      -- admit.lua가 ZADD한 member("seq|identifier")와 바이트가 어긋난다.
local tokenId = ARGV[3]

-- ── 회차 고유 키는 무조건 지운다 ──────────────────────────────────────────────
-- member에 seq가, 키 뒷조각에 tokenId/admitToken이 박혀 있다. 셋 다 회차마다 유일하므로
-- (seq=INCR, tokenId·admitToken=UUIDv7) 남의 회차를 지울 수 없다. 대조가 필요 없다.
-- 🔴 반대로 여기에 가드를 걸면 안 된다 — admit-by-admit이 남으면 완료된 admitToken으로
--    verify가 TTL(60초) 동안 계속 통과한다. QueueEngine#findAdmitRefByAdmitToken 참조.
redis.call('ZREM', KEYS[2], seq .. '|' .. identifier)
redis.call('DEL', KEYS[4], KEYS[5])

-- ── 사람 키는 지금 그 사람이 어느 회차인지 물어본 뒤에 지운다 ─────────────────
local stored = redis.call('HGET', KEYS[3], identifier)
if not stored then
	-- 이미 정리됐거나 애초에 없다. waiting에 남아 있더라도 그건 tokens 미스인 고아이고
	-- (admit.lua가 되돌려 놓은 자) 그 사람은 admitToken을 못 받아 complete에 도달할 수 없다.
	-- 즉 이 complete의 소유가 아니다. 건드리지 않는다.
	return -1
end

-- 🔴 구분자가 없으면 **값 전체를 tokenId로 본다** (poll_verify.lua·enqueue_bulk.lua와 같은 규약).
--    "미스 취급"으로 두면 롤링 배포 중 남은 구 포맷 값에서 완료자의 게이트가 영영 안 풀려
--    영구 락아웃이 된다 — 현행 무조건 HDEL보다 나빠지는 유일한 지점이라 반드시 이쪽이다.
--    (issuedAt이 필요한 admit_expire.lua·inactive_expire.lua는 반대 규약을 쓴다. 거긴 멱등 키
--     (token_id, issued_at)이 성립하지 않아 발행 자체가 불가능하기 때문이고, 여기선 이벤트
--     재료가 이미 AdmitRef/Token에 있어 issuedAt이 필요 없다.)
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
