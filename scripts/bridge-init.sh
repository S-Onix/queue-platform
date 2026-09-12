#!/usr/bin/env bash
# docker-compose.bridge.yml 최초 1회 초기화 — Redis 클러스터 2개 + Kafka 토픽.
# 재실행 안전(이미 구성돼 있으면 건너뛴다).
set -euo pipefail

# RF/min.insync 는 브로커 수에 종속이다. bridge(1브로커)가 기본, AWS 판(3브로커)은
# KAFKA_RF=3 KAFKA_MIN_ISR=2 로 넘긴다. 브로커 수보다 큰 RF 를 주면 생성 자체가 실패한다.
RF=${KAFKA_RF:-1}
MIN_ISR=${KAFKA_MIN_ISR:-1}

A=(172.28.1.1 172.28.1.2 172.28.1.3)
B=(172.28.2.1 172.28.2.2 172.28.2.3)

form() {  # $1=클러스터명  $2...=노드 IP
  local name=$1; shift
  local state
  state=$(docker exec br-redis-a-1 redis-cli -h "$1" cluster info | grep -oP 'cluster_state:\K\w+' || echo unknown)
  if [ "$state" = "ok" ]; then echo "  $name: 이미 구성됨 (skip)"; return; fi
  # --cluster-replicas 0 : 레플리카 없음. 이 판의 목적은 failover 가 아니라 MOVED 재현이다.
  docker exec br-redis-a-1 sh -c \
    "redis-cli --cluster create $(printf '%s:6379 ' "$@") --cluster-replicas 0 --cluster-yes" >/dev/null
  echo "  $name: 구성 완료"
}

echo "[1/2] Redis 클러스터"
form "Cluster A" "${A[@]}"
form "Cluster B" "${B[@]}"

echo "[2/2] Kafka 토픽"
# 파티션 18 = 호스트 판과 동일.
# 🔑 파티션 수는 murmur2(tokenId) % N 의 N 이다. in-flight 토큰이 있는 동안 바꾸면
#    같은 tokenId 가 다른 파티션으로 가서 순서 보장이 깨진다.
if docker exec br-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server 172.28.3.1:9092 \
     --list 2>/dev/null | grep -qx token-lifecycle; then
  echo "  token-lifecycle: 이미 존재 (skip)"
else
  docker exec br-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server 172.28.3.1:9092 \
    --create --topic token-lifecycle --partitions 18 --replication-factor "$RF" \
    --config min.insync.replicas="$MIN_ISR" >/dev/null
  echo "  token-lifecycle: 생성 완료 (partitions=18, RF=$RF, min.insync=$MIN_ISR)"
fi
