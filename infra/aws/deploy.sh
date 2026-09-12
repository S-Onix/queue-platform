#!/usr/bin/env bash
# 로컬 → SUT 소스 동기화 + 빌드 + 기동. 세션마다 돌린다.
#   ./infra/aws/deploy.sh <sut_public_ip>
# ECR 을 안 쓰는 이유: 프라이빗 레지스트리 자격증명과 ARM 크로스컴파일을 동시에 피한다.
set -euo pipefail
IP=${1:?사용법: deploy.sh <sut_public_ip>}
KEY=~/.ssh/queue-aws
SSH="ssh -i $KEY -o StrictHostKeyChecking=accept-new ubuntu@$IP"

# user_data 의 docker/rsync 설치가 끝나기 전에 rsync 를 쏘면 실패한다. 끝날 때까지 기다린다.
echo "[0/4] 인스턴스 준비 대기 (user_data 설치 완료까지)"
until $SSH 'docker info >/dev/null 2>&1 && command -v rsync >/dev/null' 2>/dev/null; do sleep 10; done

echo "[1/4] 소스 동기화"
rsync -az --delete -e "ssh -i $KEY -o StrictHostKeyChecking=accept-new" \
  --exclude '.git' --exclude 'build' --exclude '.gradle' --exclude 'node_modules' \
  ./ "ubuntu@$IP:~/queue-platform/"

echo "[2/4] 이미지 빌드 (최초 5~10분)"
$SSH 'cd ~/queue-platform && docker compose -f docker-compose.bridge.yml -f docker-compose.aws.yml build'

echo "[3/4] 인프라 기동 + 초기화"
$SSH 'cd ~/queue-platform && docker compose -f docker-compose.bridge.yml -f docker-compose.aws.yml up -d \
        mysql kafka kafka-2 kafka-3 redis-a-1 redis-a-2 redis-a-3 redis-b-1 redis-b-2 redis-b-3 &&
      until docker exec br-mysql mysqladmin ping -h127.0.0.1 -prootpw1234 >/dev/null 2>&1; do sleep 3; done &&
      KAFKA_RF=3 KAFKA_MIN_ISR=2 ./scripts/bridge-init.sh'

echo "[4/4] 앱 기동"
$SSH 'cd ~/queue-platform && docker compose -f docker-compose.bridge.yml -f docker-compose.aws.yml up -d \
        queue-api queue-api-2 queue-api-3 queue-batch queue-consumer &&
      for p in 18080 18083 18084; do
        until curl -sf "localhost:$p/actuator/health" >/dev/null; do sleep 3; done; echo "  :$p UP"
      done'

echo "완료. k6 드라이버에서 SUT 프라이빗 IP 로 때린다."
