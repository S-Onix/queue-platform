#!/usr/bin/env bash
# 로컬(WSL) Alertmanager + webhook 싱크를 세우고 **도달을 검증한다.**
#
# 이유: 로컬 Prometheus 는 규칙 23개를 평가하면서 `alerting:` 이 주석이라 발화한 알람이
#       아무 데도 가지 않았다(2026-09-19 실측). AWS 판에는 2026-09-17 부터 있다.
#
# 🪤 포트는 **19093/19094** 다 — 로컬 Kafka 브로커가 9093·9094 를 쓴다(실측). AWS 는 9093 이다.
# 🔴 Slack URL 은 레포에 없다. `.env` 의 SLACK_WEBHOOK_URL 을 읽어 600 권한 파일로 쓴다.
#    없으면 기동은 되고 통지만 실패한다 → 싱크로는 여전히 검증된다.
#
# 사용:
#   ./scripts/setup-alertmanager.sh install   바이너리·설정·systemd 유닛 배치
#   ./scripts/setup-alertmanager.sh verify    **알람이 실제로 도달하는지** 확인
#
# ✅ 2026-09-19 실측 (로컬):
#     Prometheus firing → Alertmanager 4개 도달 · 수신처(Slack 3 + webhook 3) 실패 0
#     prometheus_notifications_dropped_total=**1809** ← alerting 이 주석이던 동안 버려진 양
#
# 🪤 검증에서 두 번 잘못 읽었다. 둘 다 시스템이 아니라 **내 측정 방법**이 문제였다:
#     ① reload 직후 바로 재서 "도달 0" — 다음 평가 주기(15s)를 안 기다렸다
#     ② 같은 alertname 을 다시 넣어 "수신처 미도달" — group_interval(30s) 이 묶은 것이다
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$(pwd)

AM_DIR="$HOME/queue-platform-infra/monitoring/alertmanager"
PROM_YML="$HOME/queue-platform-infra/monitoring/prometheus/prometheus.yml"
AM_PORT=19093
SINK_PORT=19094
SINK_LOG="$AM_DIR/received.log"

ok(){ printf "  \033[32m✅\033[0m %s\n" "$*"; }
no(){ printf "  \033[31m🔴\033[0m %s\n" "$*"; }
wa(){ printf "  \033[33m⚠️\033[0m %s\n" "$*"; }

install_all() {
  echo "── 설정 배치 ──"
  mkdir -p "$AM_DIR/data"
  [ -x "$AM_DIR/bin/alertmanager" ] || { no "바이너리가 없다 — dl/alertmanager.tar.gz 를 풀어라"; exit 1; }
  cp doc/monitoring/alertmanager.local.yml "$AM_DIR/alertmanager.yml"
  "$AM_DIR/bin/amtool" check-config "$AM_DIR/alertmanager.yml" >/dev/null && ok "설정 유효"

  # 🔑 Slack URL — 없으면 빈 파일을 만든다. 파일이 없으면 Alertmanager 가 기동에 실패한다.
  if [ -f .env ] && grep -q '^SLACK_WEBHOOK_URL=' .env; then
    grep '^SLACK_WEBHOOK_URL=' .env | sed 's/^SLACK_WEBHOOK_URL=//' | tr -d '"'\''' > "$AM_DIR/slack_url"
    chmod 600 "$AM_DIR/slack_url"
    [ -s "$AM_DIR/slack_url" ] && ok "slack_url 채움 ($(wc -c < "$AM_DIR/slack_url") bytes)" \
                               || wa "slack_url 이 비었다 — 싱크로만 검증된다"
  else
    : > "$AM_DIR/slack_url"; chmod 600 "$AM_DIR/slack_url"
    wa ".env 에 SLACK_WEBHOOK_URL 이 없다 — 싱크로만 검증된다"
  fi

  # 🔴 Prometheus 의 alerting 블록. 주석이면 알람이 갈 데가 없다.
  if grep -qE '^\s*alerting:' "$PROM_YML"; then
    ok "prometheus alerting 블록 이미 활성"
  else
    python3 - "$PROM_YML" "$AM_PORT" <<'PY'
import sys, re
p, port = sys.argv[1], sys.argv[2]
s = open(p, encoding='utf-8').read()
block = (f"alerting:\n  alertmanagers:\n    - static_configs:\n"
         f"        - targets: ['localhost:{port}']   # 🪤 9093 이 아니다 — Kafka 가 쓴다\n")
# 주석 처리된 옛 블록을 지우고 scrape_configs 앞에 넣는다
s = re.sub(r"(?m)^#\s*alerting:\n(?:^#.*\n)*", "", s)
s = s.replace("scrape_configs:", block + "\nscrape_configs:", 1)
open(p, 'w', encoding='utf-8').write(s)
PY
    ok "prometheus alerting 블록 추가 → localhost:$AM_PORT"
  fi

  # systemd 유닛 — 기존 exporter 들과 같은 방식.
  # 🪤 sudo 에 비번이 필요하면 **nohup 으로 띄우고 넘어간다** — 검증을 막지 않는다.
  #    그 경우 재부팅 후 사라지므로, 사람이 비번을 줄 수 있을 때 `install` 을 다시 돌려라.
  if ! sudo -n true 2>/dev/null; then
    wa "sudo 비번 필요 — systemd 등록을 건너뛰고 nohup 으로 띄운다(재부팅 시 사라진다)"
    # 🔴 `pkill -f` 를 쓰지 마라 — 명령줄에 그 문자열이 든 **자기 셸을 죽인다**(실측 4회).
    #    comm 으로 거르면 bash 인 이 스크립트는 애초에 안 걸린다(tenant-demo/demo.sh 의 kill_py 와 같다).
    ps -eo pid,comm,args | awk '$2=="python3" && /alert-sink\.py/ {print $1}' \
      | xargs -r kill 2>/dev/null || true
    ps -eo pid,comm,args | awk '$2=="alertmanager" {print $1}' | xargs -r kill 2>/dev/null || true
    sleep 1
    PORT=$SINK_PORT SINK_LOG=$SINK_LOG nohup python3 "$REPO/scripts/alert-sink.py" \
      > "$AM_DIR/sink.log" 2>&1 &
    nohup "$AM_DIR/bin/alertmanager" --config.file="$AM_DIR/alertmanager.yml" \
      --storage.path="$AM_DIR/data" --web.listen-address="127.0.0.1:$AM_PORT" \
      --cluster.listen-address= > "$AM_DIR/alertmanager.log" 2>&1 &
    sleep 4
    curl -s -m 3 "http://localhost:$AM_PORT/-/ready" >/dev/null && ok "alertmanager 기동(nohup)" \
      || { no "alertmanager 안 뜸 — $AM_DIR/alertmanager.log 를 봐라"; tail -3 "$AM_DIR/alertmanager.log"; }
    curl -s -m 3 "http://localhost:$SINK_PORT/" >/dev/null && ok "alert-sink 기동(nohup)" || no "alert-sink 안 뜸"
    curl -s -XPOST http://localhost:9090/-/reload >/dev/null 2>&1 \
      && ok "prometheus reload" || wa "reload 실패 — 재기동이 필요하다"
    return 0
  fi

  sudo tee /etc/systemd/system/alertmanager.service >/dev/null <<EOF
[Unit]
Description=Alertmanager (queue-platform local)
After=network.target
[Service]
User=$USER
ExecStart=$AM_DIR/bin/alertmanager --config.file=$AM_DIR/alertmanager.yml \\
  --storage.path=$AM_DIR/data --web.listen-address=127.0.0.1:$AM_PORT \\
  --cluster.listen-address=
Restart=always
RestartSec=3
[Install]
WantedBy=multi-user.target
EOF
  sudo tee /etc/systemd/system/alert-sink.service >/dev/null <<EOF
[Unit]
Description=Alertmanager webhook sink (queue-platform local)
After=network.target
[Service]
User=$USER
Environment=PORT=$SINK_PORT
Environment=SINK_LOG=$SINK_LOG
ExecStart=/usr/bin/python3 $REPO/scripts/alert-sink.py
Restart=always
RestartSec=3
[Install]
WantedBy=multi-user.target
EOF
  sudo systemctl daemon-reload
  sudo systemctl enable --now alert-sink alertmanager >/dev/null 2>&1 || true
  sleep 3
  systemctl is-active --quiet alertmanager && ok "alertmanager 기동" || no "alertmanager 안 뜸"
  systemctl is-active --quiet alert-sink   && ok "alert-sink 기동"   || no "alert-sink 안 뜸"
  # 🪤 Prometheus 재기동이 아니라 reload 다 — 재기동하면 15분 알람 상태가 초기화된다.
  curl -s -XPOST http://localhost:9090/-/reload >/dev/null 2>&1 \
    && ok "prometheus reload" || wa "reload 실패 — --web.enable-lifecycle 이 없으면 재기동해야 한다"
}

verify() {
  echo "── 도달 검증 ──"
  local n
  n=$(curl -s -m 5 "http://localhost:$AM_PORT/api/v2/status" 2>/dev/null | grep -c '"cluster"' || true)
  [ "$n" -gt 0 ] && ok "Alertmanager 응답 (:$AM_PORT)" || { no "Alertmanager 무응답"; return 1; }

  # ① Prometheus 가 Alertmanager 를 알고 있는가
  local am
  am=$(curl -s -m 5 http://localhost:9090/api/v1/alertmanagers 2>/dev/null \
       | grep -o "$AM_PORT" | head -1 || true)
  [ -n "$am" ] && ok "Prometheus → Alertmanager 연결됨" || no "Prometheus 가 모른다 (reload 했나)"

  # ② 실제로 발화 중인 알람이 Alertmanager 까지 왔는가
  # 🪤 **바로 재면 0 이 나온다**(실측 2026-09-19). reload 직후에는 다음 평가 주기(15s)까지
  #    Prometheus 가 통지를 보내지 않는다 — 파이프가 죽은 게 아니라 아직 안 보낸 것이다.
  #    이걸 안 기다려서 "도달 0"을 결함으로 잘못 읽었다. 한 주기 이상 기다린다.
  local firing arrived
  firing=$(curl -s -m 5 http://localhost:9090/api/v1/rules \
    | python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]["groups"]; print(sum(1 for g in d for r in g["rules"] if r.get("state")=="firing"))')
  local waited=0
  while [ "$waited" -lt 40 ]; do
    arrived=$(curl -s -m 5 "http://localhost:$AM_PORT/api/v2/alerts" \
      | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))' 2>/dev/null || echo 0)
    [ "${arrived:-0}" -gt 0 ] && break
    sleep 5; waited=$((waited + 5))
  done
  [ "$waited" -gt 0 ] && echo "     (도달까지 ${waited}초 대기)"
  arrived=$(curl -s -m 5 "http://localhost:$AM_PORT/api/v2/alerts" \
    | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))' 2>/dev/null || echo 0)
  echo "     Prometheus firing $firing 개 → Alertmanager $arrived 개"
  # 🔑 alerting 블록이 주석이던 동안 버려진 통지 수. **이 값이 크면 "여태 갈 데가 없었다"는 증거다.**
  local dropped
  dropped=$(curl -s "http://localhost:9090/api/v1/query?query=prometheus_notifications_dropped_total" \
    | python3 -c 'import json,sys; r=json.load(sys.stdin)["data"]["result"]; print(r[0]["value"][1] if r else 0)' 2>/dev/null || echo 0)
  echo "     여태 버려진 통지 prometheus_notifications_dropped_total=$dropped"
  [ "$arrived" -gt 0 ] && ok "알람이 Alertmanager 에 도달" \
                       || wa "도달 0 — firing 이 0 이면 정상이다(아래 합성 알람으로 확인하라)"

  # ③ 수신처까지 갔는가 — 합성 알람을 직접 밀어 넣는다
  # 🪤 **이름을 매번 바꾼다.** 같은 alertname 을 다시 넣으면 group_interval(30s) 안에서는
  #    새 통지가 안 나간다 — 묶음이 제 일을 한 것이지 실패가 아니다.
  #    이걸 몰라 "수신처까지 안 갔다"를 두 번 잘못 읽었다(2026-09-19).
  local probe before after
  probe="SetupProbe$(date +%H%M%S)"
  before=$( [ -f "$SINK_LOG" ] && wc -l < "$SINK_LOG" || echo 0)
  "$AM_DIR/bin/amtool" --alertmanager.url="http://localhost:$AM_PORT" alert add \
    "$probe" severity=critical instance=localhost \
    summary='setup-alertmanager.sh 합성 알람' >/dev/null 2>&1 || true
  sleep 8
  after=$( [ -f "$SINK_LOG" ] && wc -l < "$SINK_LOG" || echo 0)
  if [ "$after" -gt "$before" ] && grep -q "$probe" "$SINK_LOG"; then
    ok "수신처 도달 확인 — 싱크 로그 $((after - before)) 줄 증가 · $probe 수신"
    grep -c "AppInstanceDown" "$SINK_LOG" >/dev/null && \
      ok "실제 알람도 수신됨 (AppInstanceDown $(grep -c AppInstanceDown "$SINK_LOG") 회)"
  else
    no "수신처까지 안 갔다 — alert-sink 상태와 실패 카운터를 봐라"
    curl -s http://localhost:9090/api/v1/query --data-urlencode \
      'query=alertmanager_notifications_failed_total' | head -c 200; echo
  fi
}

case "${1:-}" in
  install) install_all ;;
  verify)  verify ;;
  *) echo "사용: $0 {install|verify}"; exit 1 ;;
esac
