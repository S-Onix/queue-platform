#!/usr/bin/env bash
# Redis Cluster 하나를 기동한다. CI 통합 레인 전용.
#
# 사용: redis-cluster-up.sh <base-port>     예) 7001 → 7001~7008
#
# 🔑 포트를 로컬(7001-7008 / 8001-8008)과 똑같이 맞춘다.
#    RedisConfig의 @Value 기본값이 그 목록이라, 맞춰 두면 테스트에 프로퍼티를 주입할 배관이
#    통째로 필요 없어진다. 노드 수(8 = 4 master + 4 replica)도 같은 이유로 로컬과 같다.
#
# ⚠️ 클러스터가 하나면 안 된다. 두 클러스터가 독립이어야 큐 단위 라우팅(§75)이 검증된다 —
#    한 클러스터를 둘로 가리키면 "다른 슬롯이라도 같은 노드면 조용히 성공"하는 구멍이 열린다.
set -euo pipefail

BASE=${1:?base port required}
DIR=${RUNNER_TEMP:-/tmp}/redis-$BASE
mkdir -p "$DIR"

NODES=()
for i in $(seq 0 7); do
  PORT=$((BASE + i))
  mkdir -p "$DIR/$PORT"
  redis-server \
    --port "$PORT" \
    --cluster-enabled yes \
    --cluster-config-file "$DIR/$PORT/nodes.conf" \
    --cluster-node-timeout 5000 \
    --appendonly no \
    --save '' \
    --dir "$DIR/$PORT" \
    --daemonize yes \
    --logfile "$DIR/$PORT/redis.log"
  NODES+=("127.0.0.1:$PORT")
done

# 8노드 전부 응답할 때까지 기다린다. 바로 create 하면 아직 안 뜬 노드에서 실패한다.
for n in "${NODES[@]}"; do
  for _ in $(seq 1 30); do
    redis-cli -h 127.0.0.1 -p "${n##*:}" ping >/dev/null 2>&1 && break
    sleep 0.5
  done
done

redis-cli --cluster create "${NODES[@]}" --cluster-replicas 1 --cluster-yes

# cluster_state:ok 를 확인하고 끝낸다. 여기서 안 막으면 슬롯 배정 중에 테스트가 시작돼
# CLUSTERDOWN 으로 죽는다 — 재현이 안 되는 종류의 실패다.
for _ in $(seq 1 60); do
  if redis-cli -c -p "$BASE" cluster info 2>/dev/null | grep -q 'cluster_state:ok'; then
    echo "cluster $BASE ready"
    exit 0
  fi
  sleep 1
done
echo "cluster $BASE 가 ok 상태가 되지 않았다" >&2
redis-cli -p "$BASE" cluster info >&2 || true
exit 1
