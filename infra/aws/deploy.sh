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

APP=$(pubip app); APP2=$(pubip app2); WORKER=$(pubip worker)
MYSQL=$(pubip mysql); KAFKA=$(pubip kafka); REDIS=$(pubip redis); OBS=$(pubip obs)
APP_IP=$(privip app); APP2_IP=$(privip app2); WORKER_IP=$(privip worker)
MYSQL_IP=$(privip mysql); KAFKA_IP=$(privip kafka); REDIS_IP=$(privip redis); OBS_IP=$(privip obs)
# 앱 컨테이너가 볼 주소. 셋을 한 덩어리로 넘긴다.
DATAENV="MYSQL_IP=$MYSQL_IP KAFKA_IP=$KAFKA_IP REDIS_IP=$REDIS_IP"

# 🔑 자격증명은 커밋하지 않는다. 레포 루트 .env(.gitignore 대상)에서 읽어 ssh 로 인라인 전달한다.
#    ⚠️ rsync 로 올리지 않는다 — 원격 디스크에 남을 이유가 없다(아래 --exclude).
#    compose 는 파일 전체를 파싱 시점에 치환하므로, 그 노드가 안 띄우는 서비스의 변수도 필요하다.
#    그래서 IP 와 함께 모든 compose 호출에 붙인다.
[ -f .env ] || { echo "레포 루트에 .env 가 없다. .env.example 을 복사해서 값을 채워라."; exit 1; }
set -a; . ./.env; set +a
: "${MYSQL_ROOT_PASSWORD:?.env 에 MYSQL_ROOT_PASSWORD 가 없다}"
: "${DB_PASSWORD:?.env 에 DB_PASSWORD 가 없다}"
: "${JWT_SECRET_CURRENT:?.env 에 JWT_SECRET_CURRENT 가 없다}"
SEC="MYSQL_ROOT_PASSWORD=$(printf %q "$MYSQL_ROOT_PASSWORD")"
SEC="$SEC DB_PASSWORD=$(printf %q "$DB_PASSWORD")"
SEC="$SEC JWT_SECRET_CURRENT=$(printf %q "$JWT_SECRET_CURRENT")"
echo "app=$APP  worker=$WORKER  mysql=$MYSQL  kafka=$KAFKA  redis=$REDIS  obs=$OBS"
echo "  사설: app=$APP_IP worker=$WORKER_IP mysql=$MYSQL_IP kafka=$KAFKA_IP redis=$REDIS_IP obs=$OBS_IP"

on() { ssh $SSHOPT "ubuntu@$1" "${@:2}"; }
push() {
  rsync -az --delete -e "ssh $SSHOPT" --exclude '.git' --exclude 'build' \
    --exclude '.gradle' --exclude 'node_modules' --exclude 'infra/aws/.terraform' \
    --exclude '.env' \
    ./ "ubuntu@$1:~/queue-platform/"
}

ALL="$MYSQL $KAFKA $REDIS $APP $APP2 $WORKER"

echo "[0/5] 인스턴스 준비 대기 (user_data 설치 완료까지)"
for h in $ALL; do
  until on "$h" 'docker info >/dev/null 2>&1 && command -v rsync >/dev/null' 2>/dev/null; do sleep 10; done
  echo "  $h 준비됨"
done

# 🔑 Prometheus 는 설정 파일에서 환경변수를 치환하지 않는다. 그래서 타깃을 file_sd 로 빼고
#    여기서 만든다. rsync 전에 만들어야 그대로 실려 간다.
echo "[1/5] 관측 설정 생성 (타깃 + 대시보드)"
mkdir -p infra/aws/monitoring/targets infra/aws/monitoring/dashboards
python3 - "$APP_IP" "$APP2_IP" "$WORKER_IP" "$MYSQL_IP" "$KAFKA_IP" "$REDIS_IP" "$OBS_IP" <<'PYEOF'
import json, sys
# 🪤 인자 개수와 언팩 개수를 **같이** 고쳐라. 하나만 고치면 node.json 에서 NameError 로 배포가 죽는다.
app, app2, worker, mysql, kafka, redis, obs = sys.argv[1:8]
d = "infra/aws/monitoring/targets"
json.dump([{"targets": [f"{h}:{p}" for h in (app, app2) for p in (8080, 8083, 8084)]}], open(f"{d}/api.json", "w"))
json.dump([{"targets": [f"{worker}:8081"], "labels": {"app": "batch"}},
           {"targets": [f"{worker}:8082"], "labels": {"app": "consumer"}}], open(f"{d}/worker.json", "w"))
json.dump([{"targets": [f"redis://{redis}:{p}"], "labels": {"cluster": c}}
           for c, ports in (("A", (7001, 7002, 7003)), ("B", (8001, 8002, 8003))) for p in ports],
          open(f"{d}/redis.json", "w"))
# 🔑 노드가 5개다. 어느 계층이 먼저 포화하는지가 이 환경의 존재 이유라 하나도 빠뜨리면 안 된다.
json.dump([{"targets": [f"{ip}:9100"], "labels": {"node": n}}
           for n, ip in (("app", app), ("app2", app2), ("worker", worker), ("mysql", mysql),
                         ("kafka", kafka), ("redis", redis), ("obs", obs))], open(f"{d}/node.json", "w"))

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
on "$REDIS" "cd ~/queue-platform && DATA_IP=$REDIS_IP $SEC docker compose -f infra/aws/data.yml up -d \
  redis-a-1 redis-a-2 redis-a-3 redis-b-1 redis-b-2 redis-b-3 node-exporter &&
  REDIS_IP=$REDIS_IP ./infra/aws/init.sh redis" &
on "$KAFKA" "cd ~/queue-platform && DATA_IP=$KAFKA_IP $SEC docker compose -f infra/aws/data.yml up -d \
  kafka-1 kafka-2 kafka-3 node-exporter &&
  until docker exec q-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server $KAFKA_IP:9092 --list >/dev/null 2>&1; do sleep 3; done &&
  KAFKA_IP=$KAFKA_IP ./infra/aws/init.sh kafka" &
wait
# 🔴 **관측 4종은 obs 전용 노드로 옮겼다 (2026-09-18).** 예전엔 mysql 노드에 얹었는데
#    ("셋 중 가장 한가하다"가 근거였다), 8차에서 그 노드가 2 vCPU 인데 쿼리 시간 27,468초 대비
#    낼 수 있었던 CPU 가 18,360 CPU-s 여서 **최소 33%가 대기**였고 항목별 절대 초를 해석할 수
#    없게 됐다. Prometheus 가 같은 노드에 있어 **관측이 관측 대상을 오염**시키기도 했다.
#    🪤 redis-exporter 는 **Prometheus 와 같은 노드**여야 한다 — prometheus.yml 이
#       localhost:9121 로 부른다(multi-target 이라 Redis 노드일 필요는 없다).
# 🔴 Slack webhook 은 레포에 없다. .env 값을 노드에 파일로 쓴다 — 설정 파일에 박으면
#    PUBLIC 레포에 공개된다(2026-09-12 에 실제로 한 번 당했다). 비어 있어도 기동은 된다.
#
# 🪤 **chown 65534 가 핵심이다.** Alertmanager 컨테이너는 nobody(65534) 로 도는데 600 만
#    주면 소유자(ubuntu) 외에는 못 읽어 통지가 통째로 실패한다 — 그런데 **기동은 성공하고
#    알람도 Alertmanager 까지 정상으로 보인다.** 로그를 안 보면 모른다
#    (`permission denied`, reason="other"). 2026-09-17 로컬 검증에서 실제로 났다.
#    644 로 푸는 대신 소유자를 옮긴다 — 시크릿을 world-readable 로 만들 이유가 없다.
#
# 🔑 **실패해도 배포를 멈추지 않는다**(`|| echo`). set -e 가 걸려 있어 그냥 두면 알림용 파일
#    하나 때문에 실측 판 전체가 죽는다. 알림이 없는 것과 플랫폼이 안 뜨는 것은 무게가 다르다.
on "$OBS" "mkdir -p ~/queue-platform/infra/aws/monitoring && \
  printf '%s' '${SLACK_WEBHOOK_URL:-}' > ~/queue-platform/infra/aws/monitoring/slack_url && \
  chmod 600 ~/queue-platform/infra/aws/monitoring/slack_url && \
  sudo chown 65534:65534 ~/queue-platform/infra/aws/monitoring/slack_url" \
  || echo "⚠️  Slack webhook 파일 준비 실패 — 배포는 계속한다. 알림만 안 간다"

on "$MYSQL" "cd ~/queue-platform && DATA_IP=$MYSQL_IP $SEC docker compose -f infra/aws/data.yml up -d \
  mysql node-exporter &&
  until docker exec q-mysql mysqladmin ping -h127.0.0.1 -p$MYSQL_ROOT_PASSWORD >/dev/null 2>&1; do sleep 3; done" &
on "$OBS" "cd ~/queue-platform && DATA_IP=$OBS_IP $SEC docker compose -f infra/aws/data.yml up -d \
  prometheus grafana alertmanager redis-exporter node-exporter"
wait

echo "[4/5] 이미지 빌드 (app·worker 병렬. 최초 5~10분)"
on "$APP"    "cd ~/queue-platform && $DATAENV $SEC docker compose -f infra/aws/app.yml build" &
on "$APP2"   "cd ~/queue-platform && $DATAENV $SEC docker compose -f infra/aws/app.yml build" &
on "$WORKER" "cd ~/queue-platform && $DATAENV $SEC docker compose -f infra/aws/worker.yml build" &
wait

echo "[5/5] 앱 기동"
on "$WORKER" "cd ~/queue-platform && $DATAENV $SEC docker compose -f infra/aws/worker.yml up -d"
# 🔴 **병렬로 띄운다.** 순차면 app 의 health 대기에서 시간을 다 쓰고 app2 차례가 오지 않는다 —
#    2026-09-16 에 실제로 그랬다. 새 이미지는 구워졌는데 컨테이너는 옛 것으로 남았고,
#    겉보기엔 "배포 성공"이었다(앱이 떠 있으니 health 도 200 이다).
#    🪤 이 종류의 결함은 **초록으로 보인다** — 두 노드의 컨테이너 생성 시각을 비교해야 드러난다.
for H in "$APP" "$APP2"; do
  on "$H" "cd ~/queue-platform && $DATAENV $SEC docker compose -f infra/aws/app.yml up -d &&
    for p in 8080 8083 8084; do until curl -sf localhost:\$p/actuator/health >/dev/null; do sleep 3; done; echo \"  :\$p UP\"; done" &
done
wait

cat <<EOF

완료.
  k6 대상    : $APP_IP · $APP2_IP (각 포트 8080 · 8083 · 8084 = api 6대)
  관측 터널  : ssh -i ~/.ssh/queue-aws -o ExitOnForwardFailure=yes \\
                 -L 3300:localhost:3000 -L 9390:localhost:9090 -L 9393:localhost:9093 ubuntu@$OBS
               Grafana http://localhost:3300 (익명 Admin) · Prometheus :9390 · Alertmanager :9393
               🔑 대상은 **obs 노드**다(2026-09-18 분리). EIP 가 붙은 노드도 obs 라 주소가 고정이다.
               🔑 3000/9090 이 아니라 3300/9390 이다 — 로컬이 그 포트를 이미 쓰고 있어서,
                  Slack 알람 링크도 3300 으로 박혀 있다(alertmanager.yml).
               🪤 ExitOnForwardFailure 가 없으면 포워딩이 실패해도 SSH 는 경고 한 줄만 찍고
                  접속을 유지한다 → 브라우저에 **로컬 Grafana** 가 떠서 AWS 로 착각한다.
                  판별: http://localhost:3300/api/health 가 11.3.1 이면 AWS, 13.x 면 로컬.
EOF
