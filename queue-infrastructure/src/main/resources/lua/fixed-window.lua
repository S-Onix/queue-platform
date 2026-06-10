-- KEYS[1]: 카운터 키 (예: "rl:signup:ip:127.0.0.1")
-- ARGV[1]: limit (윈도우당 허용 요청 수)
-- ARGV[2]: windowSizeMillis (윈도우 크기, ms 단위)
-- ARGV[3]: nowMillis (현재 시간)
--
-- 반환: 1 = 허용, 0 = 거부
--
-- 동작:
--   1. 현재 윈도우 번호 = nowMillis / windowSizeMillis
--   2. 키 = baseKey:windowNo (시간 윈도우별로 키 분리)
--   3. INCR (카운터 증가)
--   4. 첫 증가면 EXPIRE 설정 (자동 정리)
--   5. 카운터 ≤ limit이면 허용
-- 목적 : tenantId 인증 전 분당 횟수로 과도한 요청 방지 (burst되어도 상관 없음)

local baseKey = KEYS[1]
local limit = tonumber(ARGV[1])
local windowSizeMillis = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

-- 윈도우 번호 계산
local windowNo = math.floor(now / windowSizeMillis)

-- 윈도우별 키
local key = baseKey .. ":" .. windowNo

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


