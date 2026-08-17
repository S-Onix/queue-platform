-- KEYS[1]: 카운터 키 (예: "rl:signup:ip:127.0.0.1:29222190") — 윈도우 번호까지 포함된 최종 키
-- ARGV[1]: limit (윈도우당 허용 요청 수)
-- ARGV[2]: windowSizeMillis (윈도우 크기, ms 단위 — TTL 계산용)
--
-- 반환: 1 = 허용, 0 = 거부
--
-- 동작:
--   1. INCR (카운터 증가)
--   2. 첫 증가면 EXPIRE 설정 (윈도우 종료 시 자동 정리)
--   3. 카운터 ≤ limit이면 허용
-- 목적 : tenantId 인증 전 분당 횟수로 과도한 요청 방지 (burst되어도 상관 없음)
--
-- ⚠️ 키를 이 스크립트 안에서 조립하지 마라. 예전에는 여기서
--    `local key = KEYS[1] .. ':' .. windowNo` 로 윈도우 번호를 이어붙여 INCR했는데,
--    선언한 KEYS와 실제 접근 키가 달라 Redis Cluster가 거부한다:
--      ERR Script attempted to access a non local key in a cluster node
--    Sentinel에는 슬롯 개념이 없어 드러나지 않았고, Cluster 전환 즉시 signup/login/refresh가
--    전부 죽었다. 윈도우 번호 조립은 RateLimitKeys.fixedWindow가 한다.
--
-- TTL 계산은 여기 남긴다 — "첫 증가일 때만 EXPIRE"는 INCR과 원자적으로 묶여야 한다.

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local windowSizeMillis = tonumber(ARGV[2])

-- 카운터 증가
local current = redis.call('INCR', key)

-- 첫증가 >> TTL 설정 (윈도우가 종료시 자동 삭제)
if current == 1 then
	local ttlSeconds = math.floor(windowSizeMillis / 1000) + 1
	redis.call('EXPIRE', key, ttlSeconds)
end

-- 한도 체크
if current > limit then
	return 0 -- 거부
end

-- 허용
return 1
