#!/usr/bin/env bash
# 3노드 배포. terraform output 에서 IP 를 읽으므로 인자가 없다.
#   ./infra/aws/deploy.sh
# ECR 을 안 쓴다 — rsync 로 소스를 올려 인스턴스에서 굽는다(프라이빗 레지스트리
# 자격증명과 ARM 크로스컴파일을 동시에 회피).
set -euo pipefail
cd "$(dirname "$0")/../.."
KEY=~/.ssh/queue-aws
SSHOPT="-i $KEY -o StrictHostKeyChecking=accept-new"
TF="terraform -chdir=infra/aws"

APP=$($TF output -json public_ip | python3 -c 'import sys,json;print(json.load(sys.stdin)["app"])')
DATA=$($TF output -json public_ip | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"])')
WORKER=$($TF output -json public_ip | python3 -c 'import sys,json;print(json.load(sys.stdin)["worker"])')
DATA_IP=$($TF output -json private_ip | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"])')
echo "app=$APP  data=$DATA  worker=$WORKER  (data 사설 IP=$DATA_IP)"

on() { ssh $SSHOPT "ubuntu@$1" "${@:2}"; }
push() {
  rsync -az --delete -e "ssh $SSHOPT" --exclude '.git' --exclude 'build' \
    --exclude '.gradle' --exclude 'node_modules' --exclude 'infra/aws/.terraform' \
    ./ "ubuntu@$1:~/queue-platform/"
}

echo "[0/4] 인스턴스 준비 대기 (user_data 설치 완료까지)"
for h in $DATA $APP $WORKER; do
  until on "$h" 'docker info >/dev/null 2>&1 && command -v rsync >/dev/null' 2>/dev/null; do sleep 10; done
  echo "  $h 준비됨"
done

# 🔑 Prometheus 는 설정 파일에서 환경변수를 치환하지 않는다. 그래서 타깃을 file_sd 로 빼고
#    여기서 만든다. rsync 전에 만들어야 그대로 실려 간다.
echo "[1/5] 관측 설정 생성 (타깃 + 대시보드)"
APP_IP=$(ip app); WORKER_IP=$(ip worker)
mkdir -p infra/aws/monitoring/targets infra/aws/monitoring/dashboards
python3 - "$APP_IP" "$WORKER_IP" "$DATA_IP" <<'PYEOF'
import json, sys
app, worker, data = sys.argv[1:4]
d = "infra/aws/monitoring/targets"
json.dump([{"targets": [f"{app}:{p}" for p in (8080, 8083, 8084)]}], open(f"{d}/api.json", "w"))
json.dump([{"targets": [f"{worker}:8081"], "labels": {"app": "batch"}},
           {"targets": [f"{worker}:8082"], "labels": {"app": "consumer"}}], open(f"{d}/worker.json", "w"))
json.dump([{"targets": [f"{ip}:9100"], "labels": {"node": n}}
           for n, ip in (("app", app), ("worker", worker), ("data", data))], open(f"{d}/node.json", "w"))

# 대시보드는 ${DS_PROMETHEUS} 를 쓰는 export 판이다. 프로비저닝에는 실제 uid 가 필요하므로
# datasource.yml 에서 고정한 uid(prometheus)로 치환하고 import 전용 키를 걷어낸다.
src = json.load(open("doc/monitoring/dashboards/queue-platform-local.json"))
for k in ("__inputs", "__requires", "id"):
    src.pop(k, None)
out = json.dumps(src).replace("${DS_PROMETHEUS}", "prometheus")
open("infra/aws/monitoring/dashboards/queue-platform.json", "w").write(out)
PYEOF
echo "  타깃 3종 + 대시보드 1개"

echo "[2/5] 소스 동기화"
for h in $DATA $APP $WORKER; do push "$h" & done; wait

echo "[3/5] data 노드 기동 + 초기화 (Prometheus·Grafana 포함)"
on "$DATA" "cd ~/queue-platform && DATA_IP=$DATA_IP docker compose -f infra/aws/data.yml up -d &&
  until docker exec q-mysql mysqladmin ping -h127.0.0.1 -prootpw1234 >/dev/null 2>&1; do sleep 3; done &&
  DATA_IP=$DATA_IP ./infra/aws/init.sh"

echo "[4/5] 이미지 빌드 (app·worker 병렬. 최초 5~10분)"
on "$APP"    "cd ~/queue-platform && DATA_IP=$DATA_IP docker compose -f infra/aws/app.yml build" &
on "$WORKER" "cd ~/queue-platform && DATA_IP=$DATA_IP docker compose -f infra/aws/worker.yml build" &
wait

echo "[5/5] 앱 기동"
on "$WORKER" "cd ~/queue-platform && DATA_IP=$DATA_IP docker compose -f infra/aws/worker.yml up -d"
on "$APP" "cd ~/queue-platform && DATA_IP=$DATA_IP docker compose -f infra/aws/app.yml up -d &&
  for p in 8080 8083 8084; do until curl -sf localhost:\$p/actuator/health >/dev/null; do sleep 3; done; echo \"  :\$p UP\"; done"

cat <<EOF

완료.
  k6 대상    : $(ip app) (포트 8080 · 8083 · 8084)
  Grafana    : ssh -i ~/.ssh/queue-aws -L 3000:localhost:3000 ubuntu@$DATA
               열고 http://localhost:3000 (익명 Admin, 로그인 없음)
  Prometheus : 같은 방식으로 -L 9090:localhost:9090
EOF
true "$($TF output -json private_ip | python3 -c 'import sys,json;print(json.load(sys.stdin)["app"])')"
