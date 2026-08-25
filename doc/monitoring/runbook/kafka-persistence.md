# RunBook — Kafka 비동기 영속화 (enqueue → tokens 테이블)

> 대상: `KafkaEnqueueEventPublisher`(queue-api) → 토픽 `token-lifecycle` → `TokenLifecycleConsumer`(queue-consumer, 8082) → MySQL `tokens`
> 쿼리 모음: [`doc/monitoring/queries/kafka-persistence.md`](../queries/kafka-persistence.md)
> 카테고리 체계: [`MONITORING_DESIGN.md` 2-3 / 4-6](../MONITORING_DESIGN.md)

> ## 🔴 이 문서의 `redis-cli` 대상 (2026-08-26 정정)
>
> **큐 상태 키는 Sentinel(6379/6380)에 없다.** 앱이 붙는 곳은 **독립 2 Cluster**다 —
> `Cluster A 7001-7008` · `Cluster B 8001-8008` (§75). 아래 명령은 전부 그 기준으로 고쳤다.
>
> ```bash
> redis-cli -c -p 7001 ...      # Cluster A   (-c 없으면 MOVED)
> redis-cli -c -p 8001 ...      # Cluster B
> ```
>
> 🪤 **6380에 치면 에러가 아니라 `0`이 나온다.** 키가 없으니 빈 값이고, 장애 중에는
> "큐가 비었다"로 읽힌다 — **조용히 틀린 답**이라 제일 위험하다.
>
> 🪤 **어느 클러스터인지는 큐마다 다르다.** `RedisClusterAssigner`가 **생성 시점의
> cluster1 메모리 사용률**(`used_memory/maxmemory ≥ 0.5`)로 정하고 `queues.redis_cluster_no`에
> 기록한다. **queueId 해시가 아니다** — 큐를 보고 추측하지 말고 그 컬럼을 조회하라:
> `SELECT redis_cluster_no FROM queues WHERE queue_id = '...'`

---

> 🔴 **`hlen tokens` 와 `zcard waiting` 은 같지 않은 것이 정상이다 (2026-08-26 정정).**
> `admit.lua`는 `ZPOPMIN waitingKey`로 **waiting에서만 빼고 `tokens` Hash는 일부러 남긴다**
> (중복 게이트 = `HSETNX`). 그래서 **admit이 한 번이라도 일어난 큐는 항상 `hlen > zcard`다.**
> 반대로 회수 3경로는 `HDEL tokens`를 하지만 DB 행은 남으므로 **`db > hlen`도 정상**이다.
>
> **차이를 유령 토큰으로 세지 마라.** 정본은 `ReconcileJob.gapOf()`이고 모집단이 다르다 —
> `ZCOUNT(waiting, ≤settledSeq)` − `COUNT(tokens WHERE status=0 AND seq ≤ settledSeq)`.
> 게이지 `queue_reconcile_ghosts`가 그 값이다. **여기 절차의 `hlen-db`와 게이지는 같은 수가 아니다.**

---

## 30초 요약

```
QueueEngineService.enqueue()
  └ result.isOk() 일 때만 publish()          ← EXISTS/FULL은 이벤트 없음 (정상)
      └ send(topic, key=tokenId, event).get(12s)
           acks=all, idempotence=true, 파티션 18개, RF 3, min.insync.replicas 2
                    ↓
queue-consumer  group-id=db-writer, auto-offset-reset=earliest
  └ @KafkaListener  List<EnqueueEvent> (max-poll-records 500)
      └ TokenPersistService.persist() @Transactional
          └ saveAllIfAbsent → @SQLInsert ... ON DUPLICATE KEY UPDATE token_id=token_id
      └ 제약 위반 시 이분 탐색으로 범인 1건만 token-lifecycle.DLT
```

**구조적으로 못 막는 갭 하나**: Lua 성공 ↔ Kafka 발행 사이에 프로세스가 죽으면 "Redis엔 있고 DB엔 없는" 유령 토큰이 남는다. Redis-Kafka 간 분산 트랜잭션이 없어서다(DECISIONS §73 D15). **로컬 100만 건 테스트에서 835건 실측.**

> ✏️ **reconciliation은 이제 구현돼 있다**(`ReconcileJob`, 5분 주기 · PR #48). **탐지는 배치가 한다** — `queue_reconcile_ghosts` 게이지가 그 수다. **다만 복구는 여전히 사람 몫이다**: 배치는 유령 토큰을 세기만 하고 다시 만들지 않는다(그 값이 0이 아닌 것을 실제로 본 뒤에 붙이기로 했다). 그래서 아래 절차는 **"먼저 게이지를 보고, 0이 아닐 때만"** 쓰는 것으로 바뀐다.

---

### [증상] Redis엔 있는데 DB엔 없다 (유령 토큰) — 어떻게 발견하는가

- **먼저 의심할 것**: 발행 갭. queue-api 프로세스가 비정상 종료했거나, 발행 타임아웃(12s)이 났거나, VM이 정지(WSL2 suspend 등)했다가 재개했다.
- **1분 안에 확인** — 3자 대조 한 줄:
  ```bash
  Q=q_xxx
  redis-cli -c -p 7001 zcard "queue:{$Q}:waiting"; redis-cli -c -p 7001 hlen "queue:{$Q}:tokens"; redis-cli -c -p 7001 get "queue:{$Q}:seq"
  mysql -h127.0.0.1 -P3307 -uqueueapp -p queue_platform -N -e \
    "SELECT COUNT(*) FROM tokens WHERE queue_id='$Q' AND issued_at >= UTC_DATE()"
  ```
  > ⚠️ **`CURDATE()`가 아니라 `UTC_DATE()`다.** `tokens`의 시각 컬럼은 UTC인데 MySQL 세션
  > `time_zone`은 `+09:00`이다.
  >
  > **버그는 항상 나는 게 아니라 KST 00:00~09:00 창에서만 난다. 대신 그 창에서는 결과가
  > 통째로 0건이 된다.** 그 시간대에는 `CURDATE()`가 이미 오늘인데 `UTC_DATE()`는 아직 어제라,
  > `issued_at >= CURDATE()`가 **UTC로는 오늘 09:00(KST) 이후**를 요구한다 — 아직 오지 않은 시각이다.
  > (KST 09:00 이후에는 두 값이 같아져 버그가 사라진다. 그래서 낮에 검증하면 재현되지 않는다.)
  >
  > 결과가 0이면 Redis보다 DB가 적으니 **유령 토큰이 대량 발생한 것처럼 보인다** —
  > 이 항목이 잡으려는 바로 그 증상을, 절차가 새벽에만 스스로 만들어낸다.
  **판정 기준 (반드시 이 관계로 읽어라 — 넷이 전부 같은 게 아니다):**

  | 관계 | 정상 여부 |
  |---|---|
  | `hlen tokens` == `zcard waiting` | **항상 같아야 한다.** 다르면 Lua 파손 또는 부분 삭제 — 즉시 에스컬레이션 |
  | `get seq` ≥ `zcard waiting` | 정상. 차이 = 중복 identifier(EXISTS) 횟수. INCR한 seq를 버리기 때문(`enqueue_bulk.lua:62,66`) |
  | DB rows == `hlen tokens` | **정상이면 같다.** DB가 적으면 = **유령 토큰** |
  | DB rows > `hlen tokens` | 이전 이벤트의 잔여 행이 섞였다. `issued_at` 범위를 좁혀 다시 재라 |

  **`hlen - DB rows` 가 1 이상이면 유령 토큰 확정.**
- **정상 범위**: `hlen tokens - DB rows` = 0. 단, **consumer lag이 0으로 수렴하기 전에는 일시적으로 양수가 정상**이다 — lag을 먼저 확인하고 0인 상태에서 판정하라.
- **원인별 분기**:
  - lag > 0 → 아직 안 밀린 것. 유령 아님. `[lag이 줄지 않는다]` 항목으로.
  - lag = 0, DLT = 0, 그런데 DB가 적다 → **발행 갭 확정.** 어느 tokenId인지는 [`queries/kafka-persistence.md` §3](../queries/kafka-persistence.md) 의 차집합 스크립트로 뽑는다.
  - lag = 0, DLT > 0 → 격리된 항목이다. `[DLT에 쌓인다]` 항목으로.
- **먼저 볼 것**: `queue_reconcile_ghosts` 게이지(`ReconcileJob`, 5분 주기).
  이 값이 **0이면 손대지 마라** — 아래 수동 보정은 배치가 세지 못한 갭을 사람이 메우는 절차이지,
  평상시에 돌리는 것이 아니다. ⚠️ 배치에는 **정착 시간 300초**가 있어 방금 들어온 건은
  일부러 제외된다(컨슈머 지연을 갭으로 오탐하지 않으려고). 사고 직후라면 5분 기다렸다 다시 본다.
- **조치** (배치는 탐지만 한다 — 복구는 아직 수동이다):
  1. 차집합 스크립트로 누락 tokenId·identifier·seq·issuedAt을 파일로 뽑는다. Redis Hash 값이 `tokenId|issuedAt` 형식이라 **DB 재구성에 필요한 값이 전부 Redis에 있다**(`enqueue_bulk.lua:75`).
  2. 그 목록으로 `tokens`에 직접 INSERT한다. `ON DUPLICATE KEY`가 아니라 수동이므로 `issued_at`을 **밀리초까지 정확히** 맞춰야 한다 — 1ms만 어긋나도 `UNIQUE(token_id, issued_at)`이 다른 행으로 인식해 중복이 생긴다.
  3. 되돌리기: INSERT한 `token_id` 목록을 남겨두고, 잘못됐으면 `DELETE FROM tokens WHERE token_id IN (...) AND issued_at = '...'`.
- **하면 안 되는 것**:
  - 컨슈머 오프셋을 되감아 "다시 흘려보내는" 것으로 해결하려는 시도. **유령 토큰은 Kafka에 애초에 발행되지 않은 것**이라 몇 번을 replay해도 안 나온다. 오프셋 리셋은 이미 적재된 수십만 건을 재처리시켜 부하만 만든다(멱등이라 데이터는 안 깨지지만 무의미하다).
  - Redis 쪽을 지워 "맞추는" 것. 사용자는 그 순번으로 대기 중이다.

---

### [증상] consumer lag이 줄지 않는다 / 계속 증가한다

- **먼저 의심할 것**: DB 적재 속도. `rewriteBatchedStatements=true`가 빠지면 batch 500이 조용히 500번 왕복으로 퇴화한다(예외도 로그도 없다).
- **1분 안에 확인**:
  ```bash
  $KAFKA_HOME/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --describe --group db-writer
  ```
  **LAG 합계가 10,000을 넘고 30초 뒤 다시 재서 더 커졌으면 처리가 유입을 못 따라간다.**
  ```bash
  # 설정 즉시 확인 (JDBC URL에 있어야 한다)
  grep -n "rewriteBatchedStatements" queue-consumer/src/main/resources/application-*.yml
  ```
- **정상 범위**: 평상시 LAG 합계 0~수백. **부하 중 임계값은 기준선 수집 필요** — 유입 RPS × 목표 지연(초)로 잡아야 하는데 목표 지연이 아직 정의돼 있지 않다. **평상시 3일치 LAG p95를 먼저 재고 그 10배를 경고선으로 시작할 것.**
- **원인별 분기**:
  - `CONSUMER-ID`가 비어 있는 파티션이 있다 → 컨슈머가 붙지 않았다. queue-consumer 프로세스 확인.
  - 특정 파티션만 LAG이 크다 → 그 파티션에 독약 메시지가 있거나, 그 파티션 담당 컨슈머만 느리다.
  - 전 파티션 균등 LAG → 처리량 부족. 컨슈머 인스턴스를 늘린다 (**최대 18대 = 파티션 수**. 그 이상은 놀기만 한다).
  - `rewriteBatchedStatements`가 없다 → 재배포 필요. 즉시 조치 불가.
  - `max-poll-records`(500) ≠ `hibernate.batch_size`(500) → 배치가 쪼개진다. 두 값이 같은지 확인.
- **조치**:
  ```bash
  # 컨슈머 증설 (파티션 18개까지). 같은 group-id면 자동 재배분(CooperativeStickyAssignor)
  SERVER_PORT=8083 java -jar queue-consumer.jar
  ```
  되돌리기: 인스턴스 종료. 남은 컨슈머가 파티션을 다시 가져간다.
- **하면 안 되는 것**:
  - **오프셋을 latest로 리셋해 lag을 "없애는" 것.** 건너뛴 이벤트는 영원히 DB에 안 들어가고, 그게 곧 유령 토큰이 된다. lag은 지표이지 문제가 아니다.
  - `retention.ms`(7일) 안에 해결하지 않고 방치하는 것. 보관 기간을 넘기면 미소비 이벤트가 브로커에서 삭제된다 — **그 시점부터 복구 불가**.

---

### [증상] `token-lifecycle.DLT`에 메시지가 쌓인다

- **먼저 의심할 것**: `DataIntegrityViolationException`. 재시도 대상에서 제외돼 있어(`KafkaConsumerConfig.java`) 곧장 격리된다. FK 위반(`fk_tokens_queue` — queues에 없는 queue_id)이 가장 흔하다.
- **1분 안에 확인**:
  ```bash
  $KAFKA_HOME/bin/kafka-run-class.sh kafka.tools.GetOffsetShell \
    --bootstrap-server localhost:9092 --topic token-lifecycle.DLT | awk -F: '{s+=$3} END {print s}'
  ```
  **합계가 0이 아니면 조사 대상.** DLT는 자동으로 비워지지 않으므로 이 값은 누적이다 — 직전 값과 비교하라.
  ```bash
  grep "적재 불가 항목 격리" <consumer-log> | tail -20    # tokenId / queueId / index
  ```
- **정상 범위**: DLT 오프셋 증가량 = 0 / 시간당.
- **원인별 분기**:
  - 로그의 `queueId`가 `queues` 테이블에 없다 → FK 위반. 큐가 삭제됐는데 이벤트가 뒤늦게 도착.
  - 파티션 관련 에러(`Table has no partition for value`) → `tokens` 파티션 범위 밖의 `issued_at`. `p_future`가 있으므로 정상 상황에선 안 나야 한다.
  - `제약 위반이 났지만 범인을 특정하지 못했다` (WARN) → **DLT로 안 가고 정상 ack된 것이다.** 이분 탐색 중 전 건이 적재됐다는 뜻. 조치 불필요.
- **조치**:
  1. DLT 내용을 읽어 원인을 확정한다 (**consumer group 없이 읽어라** — 그룹을 붙이면 오프셋이 커밋된다):
     ```bash
     $KAFKA_HOME/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
       --topic token-lifecycle.DLT --from-beginning --max-messages 20 \
       --property print.headers=true
     ```
  2. 원인이 해소됐으면(예: 큐 재생성) DLT를 원본 토픽으로 되돌린다. **자동 도구가 없다** — 수동 재발행이 필요하다. 되돌리기: 재발행분은 멱등 적재라 중복돼도 안전하다.
- **하면 안 되는 것**:
  - DLT를 지워 "치우는" 것. 그 안의 이벤트가 DB에 없는 유일한 사본이다.
  - `--from-beginning` + `--group db-writer` 로 DLT를 읽는 것. 오프셋이 오염된다.

---

### [증상] 컨슈머가 리밸런스를 반복한다 / 같은 레코드가 두 번 처리된다

- **먼저 의심할 것**: `max.poll.interval.ms`(300,000ms = 5분) 초과. 500건 적재가 5분을 넘으면 브로커가 컨슈머를 추방하는데, 컨슈머 본인은 모른 채 적재를 계속해 **두 컨슈머가 같은 레코드를 동시에 쓴다**.
- **1분 안에 확인**:
  ```bash
  grep -cE "Member .* sending LeaveGroup|Attempt to heartbeat failed|rebalance" <consumer-log>
  ```
  **5분에 2회 이상 리밸런스면 비정상.** 정상 리밸런스는 인스턴스 증감 시에만 발생한다.
- **정상 범위**: 리밸런스 0회 / 시간 (인스턴스 변경이 없을 때).
- **원인별 분기**:
  - 배치 적재가 5분을 넘는다 → DB가 느리다. `hikaricp_connections_pending`, MySQL `processlist` 확인.
  - 이분 탐색이 돌고 있다 → 500건 중 1건 위반이면 log2(500) ≈ 9단계 × 트랜잭션. 여기에 DB가 느리면 5분 초과가 현실화된다.
  - 네트워크/GC → JVM heap, GC pause 확인.
- **조치**: 중복 처리 자체는 **데이터를 깨지 않는다** — `@SQLInsert`의 `ON DUPLICATE KEY UPDATE token_id = token_id`가 흡수한다(오프셋 5,000 되감기 재소비 테스트로 검증됨: 행 수 불변). 근본 원인(DB 지연)을 잡는다.
- **하면 안 되는 것**: `max.poll.interval.ms`를 무작정 키우는 것. 진짜 죽은 컨슈머의 감지가 그만큼 늦어져 lag이 조용히 쌓인다.

---

### [증상] enqueue가 503인데 Redis에는 정상 반영돼 있다

- **먼저 의심할 것**: Kafka 발행 실패. `QueueEngineService.java:52-54`에서 Lua는 이미 성공한 뒤에 publish가 호출되므로, 발행 실패 시 Redis에는 남고 응답은 503이 된다. **이 요청은 곧바로 유령 토큰이 된다.**
- **1분 안에 확인**:
  ```bash
  grep -c "enqueue 이벤트 발행 실패" <api-log>
  $KAFKA_HOME/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic token-lifecycle \
    | grep -c "Isr: [0-9]*,[0-9]*"    # ISR이 2개 이상인 파티션 수 — 18이어야 정상
  ```
  **ISR 2 미만인 파티션이 하나라도 있으면 `min.insync.replicas=2` 때문에 그 파티션 발행이 전부 실패한다.**
- **정상 범위**: ISR ≥ 2인 파티션 18/18. 발행 실패 로그 0건/5분.
- **원인별 분기**:
  - ISR 부족 → 브로커 장애. 브로커 3대 중 2대 이상이 살아야 한다.
  - ISR 정상인데 타임아웃 → 시한 사슬(`max.block 4s + delivery 8s = 12s` ≤ 어댑터 대기 12s) 확인. 값이 바뀌었으면 성공한 발행을 실패로 보고하게 된다.
  - 로그에 `TimeoutException`인데 네트워크 에러는 0 → **VM/컨테이너 정지 의심.** 100만 건 테스트의 1차 실패 1,410건이 이 원인이었다(WSL2 suspend 16.8분, DECISIONS §73). 판별: `/proc/uptime` vs 벽시계 차이, 로그의 시간 공백.
- **조치**: 브로커 복구. 복구 후 반드시 **3자 대조를 돌려 그 구간의 유령 토큰을 세고 수동 보정**한다(첫 항목).
- **하면 안 되는 것**: `min.insync.replicas`를 1로 내려 급한 불을 끄는 것. `acks=all`이 사실상 `acks=1`이 되어 리더 장애 시 조용히 유실된다.

---

### [증상] queue-consumer 지표가 Prometheus에 안 뜬다

- **먼저 의심할 것**: **엔드포인트가 아니라 수집 쪽이다.** `queue-consumer/build.gradle`에
  `io.micrometer:micrometer-registry-prometheus`가 있고 `application.yml`에
  `exposure.include: health,info,prometheus`도 있으므로 **8082에서 200이 나오는 것이 정상**이다.
  안 뜬다면 실가동 `prometheus.yml`에 consumer job이 등록돼 있는지를 먼저 본다
  (→ [증상] scrape 타깃이 DOWN, 및 `doc/INFRA_SETUP.md`).
- **1분 안에 확인**:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8082/actuator/prometheus   # 200 예상
  grep -c "micrometer-registry-prometheus" queue-consumer/build.gradle                  # 1 예상
  # 수집기가 이 타깃을 알고 있는가 (job명은 실가동 prometheus.yml 기준)
  curl -s 'http://localhost:9090/api/v1/targets' | grep -o '8082[^,]*' | head
  ```
- **정상 범위**: 200 + `kafka_consumer_fetch_manager_records_lag_max` 노출. **충족**(실측 200 / 메트릭 213줄).
- **원인별 분기**:
  - **200인데 Prometheus에 없다** → 가장 흔한 경우. 실가동 `prometheus.yml`에 consumer job이 없다. `doc/INFRA_SETUP.md`의 스니펫은 **문서일 뿐 실가동 파일이 아니다** — 실제 파일에 반영하고 reload해야 한다.
  - **404** → 의존성이 빠진 빌드가 배포됐다(재배포 필요). 현재 브랜치 기준으로는 정상 경로가 아니다.
  - 403/401 → Security(consumer엔 Security 없음, 해당 없음). 연결 거부 → 프로세스 미기동.
- **조치**: 실가동 `prometheus.yml`에 8082 job을 추가하고 reload. job명·라벨 키는 **기존 job과 통일**할 것 — 문서 스니펫과 실가동 파일이 다르면(`queue-api` vs `queue-platform-api`, `env` vs `environment`) 라벨 셀렉터가 갈려 대시보드 쿼리가 어긋난다. 그래도 안 보이면 lag은 `kafka-consumer-groups.sh` CLI로 확인한다.
- **하면 안 되는 것**: consumer 지표가 안 보인다고 "lag이 0이다"라고 가정하는 것. **지표가 없는 것과 문제가 없는 것은 다르다.** 특히 컨슈머가 죽으면 `kafka_consumer_*`는 0이 되는 게 아니라 **시계열 자체가 사라져** `rate()` 기반 알림이 전부 침묵한다 — 그때 유일하게 남는 신호가 `up{job=...} == 0`이다.

---

## 이 기능에서 관측이 비어 있는 것 (지표 추가 필요)

| 관측 대상 | 현재 상태 |
|---|---|
| 발행 성공/실패 카운터 | **미노출.** 로그만 |
| 발행 지연(publish latency) | **미노출** |
| 적재 건수 / 배치 크기 분포 | **미노출.** `log.debug`만 (`INFO` 레벨에선 안 찍힘) |
| DLT 유입 카운터 | **미노출.** Kafka 오프셋을 직접 세야 함 |
| consumer lag (PromQL) | **클라이언트 단위는 가능** — `kafka_consumer_fetch_manager_records_lag_max`. 단 **실가동 `prometheus.yml`에 consumer job 등록이 선행**이다. **그룹 단위 LAG 합계는 여전히 불가**(kafka_exporter 미설치) — 아래 주의 참조 |
| 유령 토큰 수(reconciliation) | ✅ **`queue_reconcile_ghosts`** (`ReconcileJob`, 5분). PromQL에서 **`sum`이 아니라 `max`** — batch N대가 같은 값을 각자 보고한다. 짝인 `queue_reconcile_stale`은 반대 방향(DB > Redis = 종료 이벤트 유실) |
