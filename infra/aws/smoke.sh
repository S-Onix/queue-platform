#!/usr/bin/env bash
# 전 구간 1건 + 시간 데이터 검증. **data 노드 위에서** 돈다(MySQL 을 docker exec 로 본다).
#   ssh ubuntu@<data> 'cd ~/queue-platform && APP_IP=<app 사설 IP> ./infra/aws/smoke.sh'
set -euo pipefail
B=http://${APP_IP:?APP_IP 필요}:8080
E="smoke-$(date +%s)@test.com"
MY="docker exec q-mysql mysql -uqueueapp -pqueueapp1234 queue_platform -N -B"
pick() { python3 -c "import sys,json;print(json.load(sys.stdin)['data']['$1'])"; }
fail() { echo "  ❌ $1"; FAILED=1; }
FAILED=0
q() { $MY -e "$1" 2>/dev/null; }   # 비밀번호 경고를 버린다

echo "[1/5] 전 구간 (signup → complete)"
curl -s -X POST $B/api/v1/tenants/signup -H 'Content-Type: application/json' \
  -d "{\"email\":\"$E\",\"password\":\"Password1234!\",\"name\":\"smoke\"}" >/dev/null
AT=$(curl -s -X POST $B/api/v1/tenants/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"$E\",\"password\":\"Password1234!\"}" | pick accessToken)
AK=$(curl -s -X POST $B/api/v1/tenants/me/api-keys -H "Authorization: Bearer $AT" \
  -H 'Content-Type: application/json' -d '{"name":"smoke"}' | pick rawKey)
QID=$(curl -s -X POST $B/api/v1/queues -H "Authorization: Bearer $AT" \
  -H 'Content-Type: application/json' -d '{"name":"smoke","maxCapacity":1000}' | pick queueId)
K=(-H "X-API-Key: $AK" -H 'Content-Type: application/json')
TID=$(curl -s -X POST $B/api/v1/queues/$QID/tokens "${K[@]}" -d '{"identifier":"u1"}' | pick tokenId)
ADM=$(curl -s -X POST $B/api/v1/queues/$QID/admit "${K[@]}" \
  -d "{\"count\":1,\"requestId\":\"smoke-$(date +%s)\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['admitted'][0]['admitToken'])")
curl -s -X POST $B/api/v1/queues/$QID/admit-tokens/$ADM/verify "${K[@]}" >/dev/null
curl -s -X POST $B/api/v1/queues/$QID/tokens/$TID/complete "${K[@]}" -d "{\"admitToken\":\"$ADM\"}" >/dev/null
echo "  queue=$QID token=$TID"

echo "[2/5] 컨슈머 적재 대기 (worker 노드가 Kafka → tokens)"
for i in $(seq 1 40); do [ "$(q "SELECT COUNT(*) FROM tokens WHERE token_id='$TID'")" = "1" ] && break; sleep 3; done

echo "[3/5] 시간대 조건"
TZ_G=$(q "SELECT @@global.time_zone")
echo "  서버 TZ = $TZ_G (기대 +09:00 — UTC 면 NOW() 오용이 안 보인다)"
[ "$TZ_G" = "+09:00" ] || fail "서버 TZ 가 +09:00 이 아니다"

echo "[4/5] 시간 데이터가 UTC 로 들어갔나"
# 🔑 앱 커넥션은 세션 TZ=UTC 라 DEFAULT CURRENT_TIMESTAMP(3) 도 UTC 여야 한다.
#    세션 강제가 깨지면 KST 가 들어가 UTC_TIMESTAMP 와 32,400초(9시간) 벌어진다.
for chk in "tenants|created_at|email='$E'" "tokens|issued_at|token_id='$TID'" \
           "tokens|admitted_at|token_id='$TID'" "tokens|completed_at|token_id='$TID'"; do
  IFS='|' read -r T C W <<< "$chk"
  D=$(q "SELECT IFNULL(ABS(TIMESTAMPDIFF(SECOND, $C, UTC_TIMESTAMP(3))),'NULL') FROM $T WHERE $W")
  if   [ "$D" = "NULL" ];  then fail "$T.$C 가 NULL"
  elif [ "$D" -gt 600 ];   then fail "$T.$C 가 UTC 와 ${D}초 차이 (32400 이면 KST 오적재)"
  else echo "  ✅ $T.$C — UTC 기준 ${D}초 이내"; fi
done
echo "  완료 상태: status=$(q "SELECT status FROM tokens WHERE token_id='$TID'") (기대 2)"

echo "[5/5] data 노드 자원"
free -m | awk 'NR<=2'; docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}' | sort

[ $FAILED -eq 0 ] && echo "=== 스모크 통과 ===" || { echo "=== 실패 항목 있음 ==="; exit 1; }
