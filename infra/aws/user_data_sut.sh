#!/bin/bash
set -eux
apt-get update
apt-get install -y ca-certificates curl rsync openjdk-21-jdk-headless
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=arm64 signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu noble stable" \
  > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
usermod -aG docker ubuntu

# 🔑 Docker 기본 json-file 은 크기 제한이 **없다**. 컨테이너 11개(특히 Kafka 3대)가
#    몇 시간 돌면 40GB 를 채우고, 그러면 MySQL 쓰기가 실패한다 —
#    부하 테스트 결과가 "성능 한계"가 아니라 "디스크 참"으로 오염된다.
#    컨테이너마다 적지 않고 데몬에 한 번 건다: 컨테이너당 최대 30MB, 전체 상한 약 330MB.
#    최근 30MB 는 그대로 남으므로 docker logs 로 디버깅하는 데 지장이 없다.
cat > /etc/docker/daemon.json <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
JSON
systemctl restart docker
# Kafka/Redis가 요구하는 커널 파라미터
sysctl -w vm.max_map_count=262144
sysctl -w vm.overcommit_memory=1
