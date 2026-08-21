-- poll_verify.lua
-- Queue Platform - 폴링 소유권 검증 + last-active 갱신 (원자 1회)

-- KEYS[1]: waiting key     (예: queue:{q_bts}:waiting)   — ZSet, score=seq, member=identifier
-- KEYS[2]: tokens key      (예: queue:{q_bts}:tokens)    — Hash, identifier -> "tokenId|issuedAt"
-- KEYS[3]: lastActive key  (예: queue:{q_bts}:last-active) — ZSet, member=seq, score=now ms
--   해시태그 {queueId}는 QueueKeys가 붙인다. 세 키가 같은 슬롯이어야 Cluster에서 실행된다.
-- ARGV[1]: seq (조회할 순번)
-- ARGV[2]: tokenId (요청자가 제시한 토큰 — 소유권 증명)
-- ARGV[3]: keepalive — **더 이상 쓰지 않는다** (§82 F안). API 하위호환을 위해 자리만 남긴다.
--          호출부가 계속 넘기지만 이 스크립트는 무시하고 언제나 last-active를 갱신한다.
-- ARGV[4]: nowMillis (Java가 넘긴 시각)
--   Lua에서 시각을 만들지 않는 이유는 TIME이 비결정적이어서가 아니다. Redis 5+의
--   effects replication 하에서는 write 스크립트에서 TIME을 써도 안전하다.
--   판정 기준 시각을 Java가 통제해야 테스트에서 Clock을 고정할 수 있기 때문이다.

-- Returns: 1(검증 통과) 또는 0(대기 항목 없음 또는 tokenId 불일치)

-- 검증과 last-active 갱신을 한 스크립트로 묶는 이유:
--   나누면 "검증 통과 → 그 사이 admit/이탈로 항목 제거 → touch"가 성립해서
--   이미 사라진 seq의 last-active가 되살아난다(좀비). 왕복도 2배.
-- tokenId는 'tok_' + UUIDv7 문자열이므로 tonumber 금지 — 반드시 문자열 비교.

local seq = tonumber(ARGV[1])
local tokenId = ARGV[2]
local nowMillis = ARGV[4]

if not seq then return 0 end

-- score=seq인 멤버(identifier) 찾기. seq는 INCR 발급이라 유일하므로 결과는 0 또는 1건.
local members = redis.call('ZRANGEBYSCORE', KEYS[1], seq, seq)
local identifier = members[1]
if not identifier then return 0 end

-- Hash 값 "tokenId|issuedAt"에서 tokenId만 떼어낸다.
-- 구분자가 없으면 issuedAt 도입 이전 값이므로 전체를 tokenId로 본다 (enqueue_bulk.lua와 동일 규칙).
local stored = redis.call('HGET', KEYS[2], identifier)
if not stored then return 0 end
local sep = string.find(stored, '|', 1, true)
local storedTokenId = stored
if sep then storedTokenId = string.sub(stored, 1, sep - 1) end

if storedTokenId ~= tokenId then return 0 end

-- 🔴 keepalive 분기를 두지 않는다 (§82 F안). 폴링이 오면 **언제나** 갱신한다.
--    분기가 있던 시절엔 "이 사람이 살아 있다"의 유일한 근거가 클라이언트가 자발적으로 붙이는
--    ka 쿼리 파라미터였다(@RequestParam(defaultValue="false")). ka를 안 붙이는 클라이언트는
--    2초마다 폴링해도 inactive 회수에 걸려 죽는다 — 그 회수가 생긴 순간 이건 조용한 사고다.
--    member는 ARGV[1] 원문을 그대로 쓴다. tostring(tonumber(...))는 Lua의 숫자 포맷(%.14g)을
--    거치므로 Java가 만든 문자열과 어긋날 수 있다 — 배치 스캔이 이 member로 seq를 되읽는다.
redis.call('ZADD', KEYS[3], nowMillis, ARGV[1])

return 1
