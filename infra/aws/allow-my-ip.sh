#!/usr/bin/env bash
# 지금 내 공인 IP 를 보안 그룹에 연다. **판 도중에 IP 가 바뀌면 이걸 다시 돌린다.**
#
# 🔴 왜 필요한가 (2026-09-17 에 두 번 당했다)
#   SG 는 SSH 를 내 IP 의 /32 에만 연다. 그런데 회선이 끊겼다 붙거나 시간이 지나면 IP 가 바뀌고,
#   그러면 **모든 노드의 SSH 가 타임아웃**된다. deploy.sh 의 [0/5] 대기 루프는 성공할 때까지
#   무한 재시도라 **실패 메시지 없이 21분을 멈춰 있었다** — "느린 것"과 구분되지 않는다.
#
#   🪤 `terraform apply` 로는 못 고친다. `data.http.myip` 가 셸에서 외부 조회를 못 하면
#      옛 값을 그대로 들고 "0 changed" 를 낸다(실측). 그래서 CLI 로 직접 연다.
#
# 사용:
#   ./infra/aws/allow-my-ip.sh          # 지금 IP 를 열고, 예전에 열어둔 것은 닫는다
#   KEEP=1 ./infra/aws/allow-my-ip.sh   # 예전 것을 닫지 않는다(여러 곳에서 접속할 때)
set -euo pipefail
cd "$(dirname "$0")"

# 🪤 호스트 셸에서 checkip 이 막히는 환경이 있다(WSL + 샌드박스). 컨테이너로 우회한다.
IP=$(curl -s -m 10 https://checkip.amazonaws.com 2>/dev/null || true)
[ -n "$IP" ] || IP=$(docker run --rm --network host curlimages/curl:8.10.1 -s -m 10 https://checkip.amazonaws.com)
IP=$(printf '%s' "$IP" | tr -d '[:space:]')
[[ "$IP" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "🔴 내 IP 를 못 알아냈다: '$IP'"; exit 1; }

SG=$(python3 - <<'PY'
import json
d = json.load(open('terraform.tfstate'))
print([r['instances'][0]['attributes']['id'] for r in d['resources']
       if r['type'] == 'aws_security_group'][0])
PY
)
echo "보안 그룹 $SG · 내 IP $IP"

# 🔑 포트가 셋이다. 22 는 배포·조사용, 3000·9093 은 **운영자가 알람을 받고 바로 여는 화면**이다
#    (Grafana · Alertmanager). 터널이 전제이던 때는 22 하나면 됐지만, 그때는 새벽에 알람을 받은
#    사람이 터널부터 뚫어야 화면을 봤다 — 그게 "운영자가 대시보드를 못 본다" 의 실체였다.
# 🔴 **/32 가 유일한 방어선이다.** Grafana 자격증명이 admin/admin 이고 이 레포는 PUBLIC 이라,
#    여기를 조금이라도 넓히면 무인증 Admin 을 인터넷에 내놓는 것과 같다. 0.0.0.0/0 금지.
PORTS="22 3000 9093"

for PORT in $PORTS; do
  # 이미 열려 있으면 그대로 둔다(중복 호출이 실패하지 않게)
  OPEN=$(aws ec2 describe-security-groups --group-ids "$SG" \
    --query "SecurityGroups[0].IpPermissions[?FromPort==\`$PORT\`].IpRanges[].CidrIp" --output text)

  if grep -qw "$IP/32" <<< "$OPEN"; then
    echo "  $PORT: 이미 열려 있다"
  else
    aws ec2 authorize-security-group-ingress --group-id "$SG" \
      --protocol tcp --port "$PORT" --cidr "$IP/32" > /dev/null
    echo "  ✅ $PORT 열었다"
  fi

  # 🔑 예전 IP 는 닫는다. 안 닫으면 내가 쓰던 IP 가 남의 것이 된 뒤에도 열려 있다.
  if [ -z "${KEEP:-}" ]; then
    for old in $OPEN; do
      [ "$old" = "$IP/32" ] && continue
      aws ec2 revoke-security-group-ingress --group-id "$SG" \
        --protocol tcp --port "$PORT" --cidr "$old" > /dev/null 2>&1 && echo "  🔒 $PORT 옛 IP 닫음: $old"
    done
  fi
done

# 실제로 붙는지까지 확인한다 — 여기서 끝내면 "열었는데 안 되는" 상태를 못 잡는다
MYSQL=$(terraform output -json public_ip 2>/dev/null | python3 -c "import json,sys; print(json.load(sys.stdin).get('mysql',''))" 2>/dev/null || true)
if [ -n "$MYSQL" ]; then
  if timeout 20 ssh -i ~/.ssh/queue-aws -o StrictHostKeyChecking=no -o ConnectTimeout=10 \
       -o BatchMode=yes "ubuntu@$MYSQL" true 2>/dev/null; then
    echo "  ✅ SSH 접속 확인 ($MYSQL)"
  else
    echo "  🔴 열었는데도 SSH 가 안 된다 — 인스턴스가 떠 있는지 먼저 확인해라"
    exit 1
  fi
fi
