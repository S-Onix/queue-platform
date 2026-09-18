#!/usr/bin/env python3
"""Alertmanager webhook 싱크 — 받은 알람을 파일로 남긴다.

왜 필요한가: Slack 이 안 왔을 때 **"규칙이 안 떴나 / 통지가 실패했나"를 가르는 수단**이다.
🔧 AWS 에서는 Slack 통지가 실제로 온다(실측) — 그 구간이 미검증이라는 뜻이 아니다.
   이 싱크의 값은 URL 이 없는 환경(새 머신·CI·리허설)에서도 **통지 구간이 검증된다**는 것,
   그리고 payload 원문이 파일로 남아 묶음·라우팅·심각도 분기를 눈으로 볼 수 있다는 것이다.

🪤 2xx 를 돌려주지 않으면 Alertmanager 가 재시도하고
   alertmanager_notifications_failed_total 이 오른다 — 일부러 죽여 보면 그 카운터로 확인된다.

사용:
    python3 scripts/alert-sink.py                      # 19094, 기본 로그 경로
    PORT=29094 SINK_LOG=/tmp/a.log python3 scripts/alert-sink.py
"""
import json
import os
import sys
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("PORT", "19094"))
LOG = os.environ.get(
    "SINK_LOG",
    os.path.expanduser("~/queue-platform-infra/monitoring/alertmanager/received.log"))


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        # 🔑 파싱에 실패해도 2xx 를 준다 — 여기서 5xx 를 주면 Alertmanager 가 재시도해
        #    같은 알람이 로그를 채운다. 원문을 남기는 것이 목적이고 해석은 나중 일이다.
        try:
            body = json.loads(raw)
            alerts = body.get("alerts", [])
            summary = [f"{a.get('status')}:{a['labels'].get('alertname')}"
                       f"[{a['labels'].get('severity', '-')}]" for a in alerts]
            line = (f"{datetime.now(timezone.utc).isoformat()} "
                    f"receiver={body.get('receiver')} status={body.get('status')} "
                    f"n={len(alerts)} {' '.join(summary)}")
        except Exception as e:                      # noqa: BLE001 — 원문 보존이 우선
            line = f"{datetime.now(timezone.utc).isoformat()} PARSE_FAIL {e} {raw[:200]!r}"

        os.makedirs(os.path.dirname(LOG), exist_ok=True)
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(line + "\n")
            f.write("  " + raw.decode("utf-8", "replace") + "\n")
        print(line, flush=True)

        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"alert-sink ok\n")

    def log_message(self, *_):
        pass                                        # 접근 로그는 안 남긴다(위에서 직접 찍는다)


def demo():
    """자기 검사 — 파싱 로직이 Alertmanager payload 모양을 실제로 받아내는가."""
    import threading
    import urllib.request
    global LOG
    LOG = "/tmp/alert-sink-selfcheck.log"
    if os.path.exists(LOG):
        os.remove(LOG)
    srv = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=srv.handle_request, daemon=True).start()
    payload = {"receiver": "sink", "status": "firing",
               "alerts": [{"status": "firing",
                           "labels": {"alertname": "SelfCheck", "severity": "critical"}}]}
    req = urllib.request.Request(f"http://127.0.0.1:{srv.server_port}/alert",
                                 data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    assert urllib.request.urlopen(req, timeout=5).status == 200
    text = open(LOG, encoding="utf-8").read()
    assert "firing:SelfCheck[critical]" in text, text
    assert "receiver=sink" in text and "n=1" in text, text
    print("✅ self-check 통과 — payload 파싱·기록·2xx 응답 확인")


if __name__ == "__main__":
    if "--demo" in sys.argv:
        demo()
    else:
        print(f"alert-sink :{PORT} → {LOG}", flush=True)
        HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
