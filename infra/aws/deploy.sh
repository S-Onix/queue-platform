#!/usr/bin/env bash
# 5노드 배포. terraform output 에서 IP 를 읽으므로 인자가 없다.
#   ./infra/aws/deploy.sh
# ECR 을 안 쓴다 — rsync 로 소스를 올려 인스턴스에서 굽는다(프라이빗 레지스트리
# 자격증명과 ARM 크로스컴파일을 동시에 회피).
#
# 🔑 data 한 대를 mysql·kafka·redis 셋으로 갈랐다(2026-09-12 2차 실측, 근거는 main.tf 주석).
#    compose 파일은 쪼개지 않았다 — data.yml 하나를 세 노드에 그대로 올리고
#    **노드마다 필요한 서비스만 지정해서 띄운다.** data.yml 안의 ${DATA_IP} 는
#    "그 컨테이너가 사는 노드의 IP"라는 뜻이 되고, 각 노드가 자기 IP 를 넣는다
#    (redis announce-ip · kafka advertised.listeners 가 그 값이라 반드시 자기 IP 여야 한다).
set -euo pipefail
cd "$(dirname "$0")/../.."
KEY=~/.ssh/queue-aws
SSHOPT="-i $KEY -o StrictHostKeyChecking=accept-new"
TF="terraform -chdir=infra/aws"

# 🪤 이름을 ip() 로 두지 마라 — /sbin/ip 와 겹쳐 디버깅이 어려워진다(실제로 한 번 밟았다).
pubip()  { $TF output -json public_ip  | python3 -c "import sys,json;print(json.load(sys.stdin)['$1'])"; }
privip() { $TF output -json private_ip | python3 -c "import sys,json;print(json.load(sys.stdin)['$1'])"; }

APP=$(pubip app); WORKER=$(pubip worker)
MYSQL=$(pubip mysql); KAFKA=$(pubip kafka); REDIS=$(pubip redis)
APP_IP=$(privip app); WORKER_IP=$(privip worker)
MYSQL_IP=$(privip mysql); KAFKA_IP=$(privip kafka); REDIS_IP=$(privip redis)
# 앱 컨테이너가 볼 주소. 셋을 한 덩어리로 넘긴다.
DATAENV="MYSQL_IP=$MYSQL_IP KAFKA_IP=$KAFKA_IP REDIS_IP=$REDIS_IP"
echo "app=$APP  worker=$WORKER  mysql=$MYSQL  kafka=$KAFKA  redis=$REDIS"
echo "  사설: app=$APP_IP worker=$WORKER_IP mysql=$MYSQL_IP kafka=$KAFKA_IP redis=$REDIS_IP"

on() { ssh $SSHOPT "ubuntu@$1" "${@:2}"; }
push() {
  rsync -az --delete -e "ssh $SSHOPT" --exclude '.git' --exclude 'build' \
    --exclude '.gradle' --exclude 'node_modules' --exclude 'infra/aws/.terraform' \
    ./ "ubuntu@$1:~/queue-platform/"
}

ALL="$MYSQL $KAFKA $REDIS $APP $WORKER"

echo "[0/5] 인스턴스 준비 대기 (user_data 설치 완료까지)"
for h in $ALL; do
  until on "$h" 'docker info >/dev/null 2>&1 && command -v rsync >/dev/null' 2>/dev/null; do sleep 10; done
  echo "  $h 준비됨"
done

# 🔑 Prometheus 는 설정 파일에서 환경변수를 치환하지 않는다. 그래서 타깃을 file_sd 로 빼고
#    여기서 만든다. rsync 전에 만들어야 그대로 실려 간다.
echo "[1/5] 관측 설정 생성 (타깃 + 대시보드)"
mkdir -p infra/aws/monitoring/targets infra/aws/monitoring/dashboards
python3 - "$APP_IP" "$WORKER_IP" "$MYSQL_IP" "$KAFKA_IP" "$REDIS_IP" <<'PYEOF'
import json, sys
app, worker, mysql, kafka, redis = sys.argv[1:6]
d = "infra/aws/monitoring/targets"
json.dump([{"targets": [f"{app}:{p}" for p in (8080, 8083, 8084)]}], open(f"{d}/api.json", "w"))
json.dump([{"targets": [f"{worker}:8081"], "labels": {"app": "batch"}},
           {"targets": [f"{worker}:8082"], "labels": {"app": "consumer"}}], open(f"{d}/worker.json", "w"))
json.dump([{"targets": [f"redis://{redis}:{p}"], "labels": {"cluster": c}}
           for c, ports in (("A", (7001, 7002, 7003)), ("B", (8001, 8002, 8003))) for p in ports],
          open(f"{d}/redis.json", "w"))
# 🔑 노드가 5개다. 어느 계층이 먼저 포화하는지가 이 환경의 존재 이유라 하나도 빠뜨리면 안 된다.
json.dump([{"targets": [f"{ip}:9100"], "labels": {"node": n}}
           for n, ip in (("app", app), ("worker", worker), ("mysql", mysql),
                         ("kafka", kafka), ("redis", redis))], open(f"{d}/node.json", "w"))

# 대시보드는 ${DS_PROMETHEUS} 를 쓰는 export 판이다. 프로비저닝에는 실제 uid 가 필요하므로
# datasource.yml 에서 고정한 uid(prometheus)로 치환하고 import 전용 키를 걷어낸다.
src = json.load(open("doc/monitoring/dashboards/queue-platform-local.json"))
for k in ("__inputs", "__requires", "id"):
    src.pop(k, None)
out = json.dumps(src).replace("${DS_PROMETHEUS}", "prometheus")
open("infra/aws/monitoring/dashboards/queue-platform.json", "w").write(out)
PYEOF
echo "  타깃 4종(노드 5개 포함) + 대시보드 1개"

echo "[2/5] 소스 동기화"
for h in $ALL; do push "$h" & done; wait

echo "[3/5] 데이터 계층 기동 (redis·kafka 병렬 → mysql)"
# ⚠️ DATA_IP 는 각 노드가 자기 사설 IP 를 넣는다. announce-ip / advertised.listeners 가
#    이 값이라 다른 노드 IP 를 넣으면 클라이언트가 엉뚱한 곳으로 리다이렉트된다.
on "$REDIS" "cd ~/queue-platform && DATA_IP=$REDIS_IP docker compose -f infra/aws/data.yml up -d \
  redis-a-1 redis-a-2 redis-a-3 redis-b-1 redis-b-2 redis-b-3 node-exporter &&
  REDIS_IP=$REDIS_IP ./infra/aws/init.sh redis" &
on "$KAFKA" "cd ~/queue-platform && DATA_IP=$KAFKA_IP docker compose -f infra/aws/data.yml up -d \
  kafka-1 kafka-2 kafka-3 node-exporter &&
  until docker exec q-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server $KAFKA_IP:9092 --list >/dev/null 2>&1; do sleep 3; done &&
  KAFKA_IP=$KAFKA_IP ./infra/aws/init.sh kafka" &
wait
# 🔑 Prometheus·Grafana·redis-exporter 는 mysql 노드에 얹는다. 셋 중 가장 한가하고
#    (0.83코어), redis-exporter 는 multi-target 이라 Redis 와 같은 노드일 필요가 없다.
#    다만 prometheus.yml 이 익스포터를 localhost:9121 로 부르므로 **둘은 같은 노드여야 한다.**
on "$MYSQL" "cd ~/queue-platform && DATA_IP=$MYSQL_IP docker compose -f infra/aws/data.yml up -d \
  mysql prometheus grafana redis-exporter node-exporter &&
  until docker exec q-mysql mysqladmin ping -h127.0.0.1 -prootpw1234 >/dev/null 2>&1; do sleep 3; done"

echo "[4/5] 이미지 빌드 (app·worker 병렬. 최초 5~10분)"
on "$APP"    "cd ~/queue-platform && $DATAENV docker compose -f infra/aws/app.yml build" &
on "$WORKER" "cd ~/queue-platform && $DATAENV docker compose -f infra/aws/worker.yml build" &
wait

echo "[5/5] 앱 기동"
on "$WORKER" "cd ~/queue-platform && $DATAENV docker compose -f infra/aws/worker.yml up -d"
on "$APP" "cd ~/queue-platform && $DATAENV docker compose -f infra/aws/app.yml up -d &&
  for p in 8080 8083 8084; do until curl -sf localhost:\$p/actuator/health >/dev/null; do sleep 3; done; echo \"  :\$p UP\"; done"

cat <<EOF

완료.
  k6 대상    : $APP_IP (포트 8080 · 8083 · 8084)
  Grafana    : ssh -i ~/.ssh/queue-aws -L 3000:localhost:3000 ubuntu@$MYSQL
               열고 http://localhost:3000 (익명 Admin, 로그인 없음)
  Prometheus : 같은 방식으로 -L 9090:localhost:9090
EOF
