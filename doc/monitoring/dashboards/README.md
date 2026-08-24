# Grafana 대시보드 (로컬)

## 가져오기

Grafana(`http://localhost:3000`) → **Dashboards → New → Import → Upload JSON file**
→ `queue-platform-local.json` → 데이터소스로 **Prometheus** 선택 → Import.

파일 프로비저닝(`/etc/grafana/provisioning/dashboards/`)을 쓰지 않는 이유는 그쪽이 `sudo`를
요구하고, 이 대시보드는 **로컬 검증용**이라 사람이 필요할 때 올리면 되기 때문이다.

## 읽는 순서 — 위에서 아래로

### 1행 · 정합성 — **0이 아니면 사람이 봐야 한다**

| 패널 | 0이 아니면 |
|---|---|
| **좀비 대기자** | `waiting`엔 있는데 `tokens` Hash엔 없는 사람. `admit.lua`가 `HGET` 미스로 되돌려 놓은 고아다. 매 admit 주기마다 뽑혔다 되돌아가며 `count` 슬롯을 하나씩 먹고 **admit이 그를 지나가지 못한다** |
| **대사: 유령** | Redis엔 있고 DB엔 없다 = `ENQUEUED` 발행 유실(§73 D15). 100만건 실측에서 835건 발생한 그 갭 |
| **대사: 낡음** | DB는 활성인데 Redis엔 없다 = 종료 이벤트 유실 |

> ⚠️ **`sum`이 아니라 `max`다.** 이 셋은 claim 없는 순수 읽기라 `queue-batch`가 N대면
> **같은 값을 각자 보고한다.** `sum`으로 보면 인스턴스 수만큼 부풀어 보인다.

### 2행 · 서비스 흐름

폴링(`/tokens/*`)이 압도적으로 많은 게 **정상**이다 — 사용자당 enqueue 1회 vs 폴링 수십~수백 회.

`404`도 정상 신호일 수 있다: 회수된 토큰의 폴링, 만료된 admitToken의 verify.
`429`는 폴링 Rate Limit(`cap 5` / `refill 1.0/s`)이며 **탭을 여러 개 열면 한 버킷을 나눠 쓴다.**

**Kafka consumer lag**이 쌓이면 DB 적재가 밀려 "Redis엔 있고 DB엔 없는" 구간이 커진다 —
1행의 유령·낡음과 직결된다. 실측: 회수 중 앱을 끊자 큐마다 `-500`이 찍혔고 컨슈머 재기동
**40초 만에 0**으로 회복됐다.

### 3행 · 인프라

- **타깃 up** — 앱 3대를 안 띄우면 `25/28`이 정상이다. 그보다 낮으면 exporter나 인프라가 죽은 것
- **MySQL 복제 지연** — `@Transactional(readOnly)`가 Replica로 라우팅되므로 이 값이 커지면 **읽기가 낡은 데이터를 본다**
- **Hikari pending** — 0이 아니면 풀이 말랐다. verify가 Kafka를 기다리며 커넥션을 쥐던 문제(F-3)가 여기 보인다
- **Redis 메모리** — `maxmemory 1gb` / `noeviction`이라 차오르면 **evict가 아니라 쓰기 실패**다

## 전제

앱을 **local 프로필**로 띄우면 `management.endpoints.web.exposure.include: '*'`라 지표가 다 나온다.
prod 프로필은 `health, info`만 노출한다 — 그건 누락이 아니라 **의도된 결정**이며 이유가
`application-prod.yml` 주석에 있다(공개 포트 8080 + actuator가 Rate Limit 면제 + 관리 포트 미분리).

```bash
java -jar queue-{api,batch,consumer}/build/libs/*.jar --spring.profiles.active=local
```

exporter가 안 떠 있으면 3행이 비므로 `redis-exporter`·`mysqld-exporter-*`를 먼저 확인한다.
