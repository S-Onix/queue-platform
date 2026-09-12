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

echo "[1/4] 소스 동기화"
for h in $DATA $APP $WORKER; do push "$h" & done; wait

echo "[2/4] data 노드 기동 + 초기화"
on "$DATA" "cd ~/queue-platform && DATA_IP=$DATA_IP docker compose -f infra/aws/data.yml up -d &&
  until docker exec q-mysql mysqladmin ping -h127.0.0.1 -prootpw1234 >/dev/null 2>&1; do sleep 3; done &&
  DATA_IP=$DATA_IP ./infra/aws/init.sh"

echo "[3/4] 이미지 빌드 (app·worker 병렬. 최초 5~10분)"
on "$APP"    "cd ~/queue-platform && DATA_IP=$DATA_IP docker compose -f infra/aws/app.yml build" &
on "$WORKER" "cd ~/queue-platform && DATA_IP=$DATA_IP docker compose -f infra/aws/worker.yml build" &
wait

echo "[4/4] 앱 기동"
on "$WORKER" "cd ~/queue-platform && DATA_IP=$DATA_IP docker compose -f infra/aws/worker.yml up -d"
on "$APP" "cd ~/queue-platform && DATA_IP=$DATA_IP docker compose -f infra/aws/app.yml up -d &&
  for p in 8080 8083 8084; do until curl -sf localhost:\$p/actuator/health >/dev/null; do sleep 3; done; echo \"  :\$p UP\"; done"

echo "완료. k6 는 app 사설 IP 로 때린다: $($TF output -json private_ip | python3 -c 'import sys,json;print(json.load(sys.stdin)["app"])')"
