#!/usr/bin/env bash
# 데이터 계층 초기화 — Redis 클러스터 2개 + Kafka 토픽. 재실행 안전.
#
# 🔑 2차 실측에서 data 노드를 셋으로 갈랐다(main.tf 주석 참조). Redis 와 Kafka 가 이제
#    다른 인스턴스에 있으므로 초기화도 각 노드 위에서 자기 몫만 돈다 —
#    `docker exec q-redis-a-1` 은 redis 노드에서만, `q-kafka-1` 은 kafka 노드에서만 된다.
#
#   REDIS_IP=<redis 사설 IP> ./init.sh redis      (redis 노드 위에서)
#   KAFKA_IP=<kafka 사설 IP> ./init.sh kafka      (kafka 노드 위에서)
set -euo pipefail
RF=${KAFKA_RF:-3}
MIN_ISR=${KAFKA_MIN_ISR:-2}

init_redis() {
  local IP=${REDIS_IP:?REDIS_IP 필요}
  form() {  # $1=이름  $2...=포트
    local name=$1; shift
    local state
    state=$(docker exec q-redis-a-1 redis-cli -h "$IP" -p "$1" cluster info 2>/dev/null | grep -oP 'cluster_state:\K\w+' || echo unknown)
    if [ "$state" = "ok" ]; then echo "  $name: 이미 구성됨 (skip)"; return; fi
    # --cluster-replicas 0 : 레플리카 없음. 복제본은 failover 용이고 처리량과 무관하다.
    docker exec q-redis-a-1 sh -c \
      "redis-cli --cluster create $(for p in "$@"; do printf '%s:%s ' "$IP" "$p"; done) --cluster-replicas 0 --cluster-yes" >/dev/null
    echo "  $name: 구성 완료"
  }
  echo "[redis] 클러스터"
  form "Cluster A" 7001 7002 7003
  form "Cluster B" 8001 8002 8003
}

init_kafka() {
  local IP=${KAFKA_IP:?KAFKA_IP 필요}
  echo "[kafka] 토픽"
  # 🔑 파티션 수는 murmur2(tokenId) % N 의 N 이다. in-flight 토큰이 있는 동안 바꾸면
  #    같은 tokenId 가 다른 파티션으로 가서 순서 보장이 깨진다.
  local K="docker exec q-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server $IP:9092"
  if $K --list 2>/dev/null | grep -qx token-lifecycle; then
    echo "  token-lifecycle: 이미 존재 (skip)"
  else
    $K --create --topic token-lifecycle --partitions 18 --replication-factor "$RF" \
       --config min.insync.replicas="$MIN_ISR" >/dev/null
    echo "  token-lifecycle: 생성 완료 (partitions=18, RF=$RF, min.insync=$MIN_ISR)"
  fi
}

case "${1:?redis 또는 kafka 를 지정하라}" in
  redis) init_redis ;;
  kafka) init_kafka ;;
  *) echo "알 수 없는 대상: $1 (redis|kafka)" >&2; exit 1 ;;
esac
