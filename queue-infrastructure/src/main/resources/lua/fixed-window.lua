-- KEYS[1]: 카운터 키 (예: "rl:signup:ip127.0.0.1:29222190") — 윈도우 번호까지 포함된 최종 키(ip 뒤 콜론 없음)
-- ARGV[1]: limit (윈도우당 허용 요청 수)
-- ARGV[2]: windowSizeMillis (윈도우 크기, ms 단위 — TTL 계산용)
-- 반환: 1 = 허용, 0 = 거부
-- 목적: 인증 전(signup/login/refresh) IP당 분당 횟수 제한. INCR → 첫 증가면 EXPIRE → 카운터 ≤ limit 이면 허용.
-- ⚠️ 키를 이 스크립트 안에서 조립하지 마라 — 선언한 KEYS 와 실제 접근 키가 다르면 Cluster 가 거부한다
--    ("non local key"). 예전에 그렇게 해서 Cluster 전환 즉시 signup/login/refresh 가 전부 죽었다. §96-17
-- TTL 은 여기 남긴다 — "첫 증가일 때만 EXPIRE" 는 INCR 과 원자적으로 묶여야 한다.

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
