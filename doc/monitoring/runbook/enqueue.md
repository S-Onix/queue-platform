# RunBook — Enqueue (대기열 진입)

> 대상: `POST /api/v1/queues/{queueId}/tokens` (X-API-Key 인증)
> 쿼리 모음: [`doc/monitoring/queries/enqueue.md`](../queries/enqueue.md)
> 카테고리 체계: [`MONITORING_DESIGN.md` 4-2](../MONITORING_DESIGN.md)

## 30초 요약 — 이 경로에서 무슨 일이 일어나는가

```
HTTP 요청
  └ QueueEngineService.enqueue()          : queue 조회(DB) + 소유권/상태 검증
      └ RedisQueueEngine.enqueue()        : globalQueue.offer() 후 Future.get(30s) 블로킹  ← JVM 힙
          └ BatchProcessor @Scheduled(1000ms) : drain(최대 5000) → queueId groupBy → 500씩
              └ enqueue_bulk.lua          : ZCARD 1회 + 건당 INCR/HSETNX/ZADD/ZRANK
      └ KafkaEnqueueEventPublisher.publish() : send().get(12s)   ← OK 결과만
  └ 200 OK
```

핵심 상수 (전부 하드코딩, 설정으로 못 바꾼다):

| 값 | 위치 | 의미 |
|---|---|---|
| `fixedRate = 1000ms` | `BatchProcessor.java:48` | 배치 주기 = 요청당 평균 500ms 대기의 원인 |
| `MAX_DRAIN = 5000` | `BatchProcessor.java:29` | 한 사이클 처리 상한 |
| `CHUNK_SIZE = 500` | `BatchProcessor.java:32` | Lua 1회 호출 건수 |
| `MAX_WAIT_SECONDS = 30` | `RedisQueueEngine.java:47` | Future 타임아웃 → 초과 시 503(QE001) |
| `send-timeout-ms: 12000` | `queue-api/application.yml` | Kafka 발행 대기 |

---

### [증상] enqueue p50이 1초 근처에 붙어 있다 / 저부하인데도 느리다

- **먼저 의심할 것**: `BatchProcessor`의 `@Scheduled(fixedRate = 1000)`. 요청은 도착 즉시 처리되지 않고 다음 배치 사이클을 기다린다. 저부하에서도 기대값은 500ms(0~1000ms 균등).
- **1분 안에 확인**:
  ```bash
  curl -s 'http://localhost:9090/api/v1/query?query=histogram_quantile(0.5,sum(rate(http_server_requests_seconds_bucket{uri="/api/v1/queues/{queueId}/tokens",method="POST"}[5m]))by(le))' | jq -r '.data.result[0].value[1]'
  ```
  **1.0 근처(0.4~1.1)면 이 증상이 맞다. 2.0을 넘으면 다른 원인이다.**
- **정상 범위**: p50 0.4~1.1s, p99 ≤ 2.7s. (로컬 WSL2 10만 건 실측: p50 1,006ms / p99 2,690ms — `scratchpad/PR_DRAFT.md`. **이 수치는 WSL2 단일 머신 값이라 프로덕션 용량 산정 근거로 쓸 수 없다.**)
- **원인별 분기**:
  - p50 ≈ 1.0s, p99 ≈ 2.7s → **설계된 동작이다. 장애가 아니다.** 조치 불필요, 아래 "하면 안 되는 것" 참조.
  - p50 ≈ 1.0s인데 p99 > 5s → 한 사이클이 `MAX_DRAIN=5000`을 못 비우고 있다. 다음 증상 항목으로.
  - p50 > 2.0s → Redis 또는 Kafka 쪽. `[Redis master가 느리다]`·`[503만 나온다]` 항목으로.
- **조치**: 이 증상 자체에 런타임 조치는 없다. 상수가 코드에 박혀 있어 재배포 없이 바꿀 수 없다. 지연이 SLA를 깨는 상황이면 트래픽을 줄이거나(Tenant 쪽 호출 제한) 사건으로 기록하고 상수 재조정(후속 과제, DECISIONS §70 D8 이탈 항목)으로 넘긴다.
- **하면 안 되는 것**: p50 1초를 보고 "Redis가 느리다"고 판단해 Redis를 재기동하는 것. 원인은 애플리케이션 스케줄러이고, 재기동하면 `waiting` ZSet이 통째로 날아가(복구 경로 미구현) 진짜 장애가 된다.

---

### [증상] enqueue 지연이 계속 늘어난다 (p99가 10초, 30초로 밀린다)

- **먼저 의심할 것**: 유입이 `MAX_DRAIN=5000/초`를 넘어 in-memory `globalQueue`에 적체되고 있다. 이 큐는 무한이라 OOM 전까지 조용히 쌓인다.
- **1분 안에 확인**: **globalQueue 깊이를 노출하는 지표가 없다(미노출 — 지표 추가 필요).** 간접 확인:
  ```bash
  # 초당 요청 수(유입)
  curl -s 'http://localhost:9090/api/v1/query?query=sum(rate(http_server_requests_seconds_count{uri="/api/v1/queues/{queueId}/tokens",method="POST"}[1m]))' | jq -r '.data.result[0].value[1]'
  # 초당 실제 적재(처리) — Redis seq 증가율
  redis-cli -p 6380 get 'queue:{q_xxx}:seq'; sleep 10; redis-cli -p 6380 get 'queue:{q_xxx}:seq'
  ```
  **유입 RPS > 5000이면 확정 적체.** seq 증가분이 10초에 50,000 미만이면 처리가 유입을 못 따라간다.
- **정상 범위**: 유입 RPS ≤ 5000 (WAS 1대 기준. WAS N대면 각 JVM이 독립 `globalQueue`+독립 스케줄러라 총 처리 상한은 5000×N).
- **원인별 분기**:
  - 유입 RPS > 5000 → 처리 상한 초과. 진짜 과부하.
  - 유입 RPS < 5000인데 지연 증가 → Redis Lua가 느린 것. `[Redis master가 느리다]` 항목으로.
  - JVM heap 사용률이 함께 오르면 → `globalQueue` 적체가 힙을 먹는 중. 30초 후 `Future` 타임아웃이 터지며 503이 쏟아진다.
- **조치**:
  1. WAS를 늘린다(처리 상한이 대수에 비례). Stateless 전제이므로 인스턴스 추가만으로 됨.
  2. 그게 안 되면 Tenant 쪽 enqueue 호출을 막는다 — 해당 큐를 일시 정지:
     ```sql
     -- Master(3306). status 코드는 doc/STATE.md 참조
     UPDATE queues SET status = <PAUSED> WHERE queue_id = 'q_xxx';
     ```
     되돌리기: 같은 UPDATE로 ACTIVE 복원. `QueueEngineService.enqueue()`가 `isEnqueueable()`에서 503(Q004)로 거절하므로 신규 유입이 즉시 멎는다.
  3. 이미 `globalQueue`에 들어간 요청은 취소할 방법이 없다. 30초 뒤 503으로 정리된다.
- **하면 안 되는 것**: WAS를 재기동해 "메모리를 비우는" 것. `globalQueue`는 JVM 힙에만 있어 재기동 시 그 안의 요청이 **응답도 못 받고 증발**한다. 클라이언트는 타임아웃만 보고, Redis에도 DB에도 흔적이 없다.

---

### [증상] WAS가 죽었다 / 재기동했다 — 유실된 요청이 있는가

- **먼저 의심할 것**: `RedisQueueEngine.globalQueue`(`ConcurrentLinkedQueue`, JVM 힙)에 있던 요청. Redis에도 Kafka에도 DB에도 기록이 없다.
- **1분 안에 확인**: **관측 불가다.** 유실 건수를 알 수 있는 지표·로그가 없다(미노출 — 지표 추가 필요). 유일한 간접 증거는 클라이언트 측 타임아웃/커넥션 리셋 카운트다.
  ```bash
  # 죽기 직전 유입 RPS로 상한을 추정한다 (최대 1초분 + 처리 중이던 청크)
  curl -s 'http://localhost:9090/api/v1/query?query=sum(rate(http_server_requests_seconds_count{uri="/api/v1/queues/{queueId}/tokens",method="POST"}[1m])offset 2m)' | jq -r '.data.result[0].value[1]'
  ```
  **유실 상한 ≈ (유입 RPS × 1초) + 5000.** 유입 2,000 RPS였다면 최대 7,000건.
- **정상 범위**: 정상 종료(SIGTERM + graceful shutdown)라면 0이어야 하지만, **graceful shutdown 설정이 없다** (`server.shutdown: graceful` 미설정). 즉 정상 재배포에서도 유실 가능.
- **원인별 분기**: 이 유실은 **Redis에도 안 들어간 상태**라 아래 "유령 토큰"(Redis O / DB X)과 다르다. 3자 대조로는 안 잡힌다 — Redis·DB 어디에도 없기 때문에 대조는 "일치"로 나온다.
- **조치**: 서버 쪽 복구 수단이 없다. Tenant에게 "해당 시간대 enqueue 요청 중 응답을 못 받은 건은 재시도하라"고 통지한다. 재시도는 안전하다 — 같은 `identifier`로 다시 넣으면 `enqueue_bulk.lua`가 `HSETNX`로 EXISTS 처리하며 기존 tokenId·순번을 그대로 돌려준다(`enqueue_bulk.lua` EXISTS 분기).
- **하면 안 되는 것**: "Redis와 DB가 일치하니 유실 없음"이라고 결론짓는 것. 이 유실 유형은 3자 대조로 검출되지 않는다.

---

### [증상] enqueue가 503(QE001)만 반환한다

- **먼저 의심할 것**: `Future.get(30s)` 타임아웃과 Kafka 발행 실패가 **같은 에러코드를 쓴다**(`RedisQueueEngine.java:77,80,83` / `KafkaEnqueueEventPublisher.java:82,90`). 로그로만 구분된다.
- **1분 안에 확인**:
  ```bash
  # queue-api 로그에서 어느 쪽인지 판별
  grep -c "Enqueue timeout after 30s"        <api-log>   # → 배치/Redis 쪽
  grep -c "enqueue 이벤트 발행 실패"          <api-log>   # → Kafka 쪽
  grep -c "Failed to process chunk for queue" <api-log>   # → Lua 실행 실패
  ```
  **어느 한쪽이 0이 아니면 그쪽이 원인. 둘 다 0이면 `Result size mismatch`/`Result order mismatch`를 찾아라 — 이건 Lua 계약 파손이라 즉시 에스컬레이션 대상이다.**
- **정상 범위**: 세 로그 모두 5분간 0건. 503 응답률 = `sum(rate(http_server_requests_seconds_count{status="503"}[5m]))` 이 0.
- **원인별 분기**:
  - `Enqueue timeout` → 배치가 안 돈다. 스케줄러 스레드 확인 → `[배치가 아예 안 돈다]` 항목.
  - `발행 실패` → Kafka. [`runbook/kafka-persistence.md`](kafka-persistence.md) 로.
  - `Failed to process chunk` + 원인이 `OOM command not allowed` → Redis 메모리 상한. [`runbook/polling.md`](polling.md) 의 `last-active` 항목으로 (이 큐 키들엔 삭제 경로가 없다).
  - `Failed to process chunk` + `CROSSSLOT` → Cluster 전환 중 해시태그 파손. `QueueKeys` 확인.
- **조치**: 원인별 항목을 따른다. 공통 응급조치는 해당 큐 일시 정지(위 항목의 UPDATE).
- **하면 안 되는 것**: 503이 난다고 클라이언트에 무한 재시도를 지시하는 것. 발행 실패 503은 **Redis에는 이미 들어간 상태**라(순번 소비 완료) 재시도가 유령 토큰을 늘리진 않지만(EXISTS로 흡수), 부하는 그대로 늘어난다.

---

### [증상] 배치가 아예 안 돈다 (요청이 전부 30초 뒤 503)

- **먼저 의심할 것**: `getMaxCapacity()`가 던지는 예외(`BatchProcessor.java:175`). `queueRepository.findByQueueId()`가 **매 사이클마다 큐별로 DB를 친다**(캐시 없음). DB 커넥션이 없으면 이 조회가 막히고, 그 사이 `processBatches()`가 반환하지 못해 다음 사이클도 밀린다.
- **1분 안에 확인**:
  ```bash
  curl -s 'http://localhost:9090/api/v1/query?query=hikaricp_connections_pending' | jq -r '.data.result[]|"\(.metric.pool) \(.value[1])"'
  curl -s 'http://localhost:9090/api/v1/query?query=hikaricp_connections_active' | jq -r '.data.result[]|"\(.metric.pool) \(.value[1])"'
  ```
  **`pending > 0`이 지속되면 커넥션 고갈. `active`가 pool size(local 10 / prod master 50)에 붙어 있으면 확정.**
- **정상 범위**: `hikaricp_connections_pending` = 0. `active` < pool size × 0.8.
- **원인별 분기**:
  - `pending > 0` → 커넥션 고갈. `open-in-view: false`는 이미 적용됨(`application.yml`)이므로 원인은 다른 긴 트랜잭션이다.
  - `pending = 0`인데 배치가 안 돔 → 스케줄러 스레드가 죽었거나 이전 사이클이 안 끝났다. `@Scheduled`는 기본 단일 스레드 풀(pool size 1)이라 **한 사이클이 blocking되면 이후 전부 정지**한다.
    ```bash
    jcmd $(pgrep -f queue-api) Thread.print | grep -A 20 "scheduling-1"
    ```
    `Thread.print` 결과가 `SocketRead`/`Lettuce`/`Hikari` 대기에 멈춰 있으면 그 자원이 범인.
  - 로그에 `Queue not found during batch processing: q_xxx` → 큐가 DB에서 삭제됐는데 요청이 계속 들어온다. **그 큐의 청크만 실패하고 다른 큐는 정상 진행된다**(`processChunk`가 예외를 잡는다).
- **조치**:
  - 커넥션 고갈이면 원인 세션을 찾아 죽인다:
    ```sql
    -- Master(3306)
    SELECT id, user, host, db, command, time, state, LEFT(info,120)
    FROM information_schema.processlist WHERE command <> 'Sleep' AND time > 10 ORDER BY time DESC;
    KILL <id>;   -- 되돌리기 없음. 대상이 DDL/백업이 아닌지 반드시 확인할 것
    ```
  - 스케줄러 스레드가 blocking이면 WAS 1대씩 순차 재기동(위 유실 경고를 감수). 트래픽을 먼저 빼고 재기동한다.
- **하면 안 되는 것**: 전 WAS를 동시에 재기동. in-memory 큐가 전부 증발하고 그동안 유입은 어디서도 안 받는다.

---

### [증상] Redis master CPU가 100%에 붙었다

- **먼저 의심할 것**: 큐 하나에 트래픽이 몰린 핫키. 키가 전부 `{queueId}` 해시태그라 **Cluster여도 슬롯 하나 = 마스터 한 대**에 집중된다(`QueueKeys.java:9-21`). Redis는 단일 스레드라 여기서 코어 1개가 상한이다.
- **1분 안에 확인**:
  ```bash
  redis-cli -p 6379 --stat        # instantaneous_ops_per_sec, 5초 관찰 후 Ctrl-C
  redis-cli -p 6379 info commandstats | grep -E 'evalsha|eval|zadd|zrangebyscore'
  ```
  **`calls`가 EVALSHA에 몰리고 `usec_per_call`이 1,000(=1ms)을 넘으면 Lua가 무겁다. `ops/sec`이 수만인데 CPU가 100%면 이미 상한.**
- **정상 범위**: `evalsha`의 `usec_per_call` — **기준선 수집 필요.** enqueue_bulk는 청크당 건수(1~500)에 따라 10배 이상 변동하므로 고정 임계값이 무의미하다. **평상시 트래픽 3일치의 p50/p95를 먼저 재고**, 그 p95의 3배를 임계로 잡을 것.
- **원인별 분기**:
  - `zrangebyscore` 호출 수가 `evalsha`에 비해 압도적 → enqueue가 아니라 **폴링**이 범인. [`runbook/polling.md`](polling.md).
  - `evalsha` 호출 수 ≈ 초당 배치 사이클 수 × 큐 수 × 청크 수 → enqueue가 맞다. 큐가 커질수록 `ZADD`/`ZRANK`의 O(log N)이 커지지만 30만에서도 log2 ≈ 18로 CPU 주범은 아니다. 호출 **횟수**를 봐라.
  - `slowlog`에 Lua가 잡히면:
    ```bash
    redis-cli -p 6379 slowlog get 10
    ```
- **조치**: Redis는 스케일업(코어 수를 늘려도 소용없음 — 단일 스레드)으로 못 푼다. 트래픽을 큐 단위로 나누는 것이 유일한 수단이고 이는 설계 변경이다. **즉시 조치는 해당 큐 일시 정지(위 UPDATE)뿐이다.**
- **하면 안 되는 것**:
  - `KEYS *` — 단일 스레드를 통째로 막는다. 이미 CPU 100%인 상황에서 실행하면 전면 장애.
  - `DEBUG SLEEP` — 진단 목적으로도 금지.
  - master에 조회 명령 날리기. 읽기는 **replica 6380/6381**로.

---

### [증상] enqueue가 429(Q005 QUEUE_FULL)를 반환한다

- **먼저 의심할 것**: 진짜 정원 초과가 아니라 **`waiting` ZSet에서 아무것도 빠지지 않아서**일 가능성이 높다. 전 소스에 `ZREM`이 0건이다(admit 미구현). 한 번 들어간 항목은 영원히 남는다.
- **1분 안에 확인**:
  ```bash
  redis-cli -p 6380 zcard 'queue:{q_xxx}:waiting'
  mysql -h127.0.0.1 -P3307 -uqueueapp -p queue_platform -e \
    "SELECT max_capacity FROM queues WHERE queue_id='q_xxx'"
  ```
  **ZCARD ≥ max_capacity면 FULL이 맞다.** 이때 ZCARD가 실제 "지금 기다리는 사람 수"와 같은지는 별개 문제다.
- **정상 범위**: ZCARD < max_capacity × 0.8을 경고선으로 (`MONITORING_DESIGN.md` 4-2). 다만 **줄어드는 경로가 없으므로 이 값은 단조증가한다** — 시간에 따른 절대 기준을 세울 수 없다.
- **원인별 분기**:
  - 이 큐의 이벤트가 이미 끝났는데 ZCARD가 그대로 → 좀비 WAITING. 청소 경로가 미구현이다.
  - 429(RL001)와 429(Q005)는 **같은 HTTP 상태 코드**다. 응답 본문의 `error` 필드로 구분: `Q005`=정원, `RL001`=Rate Limit.
- **조치**: 정원을 늘린다 (다음 배치 사이클부터 반영 — `getMaxCapacity()`가 매 사이클 DB를 읽으므로 캐시 무효화 불필요):
  ```sql
  -- Master(3306)
  UPDATE queues SET max_capacity = <새값> WHERE queue_id = 'q_xxx';
  -- 되돌리기: 원래 값으로 같은 UPDATE
  ```
  이벤트가 끝난 좀비 큐라면 큐 키를 통째로 지우는 것이 유일한 청소 수단이다. **되돌릴 수 없다. 진행 중인 이벤트에는 절대 쓰지 마라:**
  ```bash
  # 반드시 이벤트 종료 확인 후. seq는 지우면 순번이 1부터 재시작해 DB UNIQUE와 충돌 가능
  redis-cli -p 6379 del 'queue:{q_xxx}:waiting' 'queue:{q_xxx}:tokens' 'queue:{q_xxx}:last-active'
  # seq는 남긴다 (순번 재사용 방지)
  ```
- **하면 안 되는 것**:
  - `FLUSHALL` / `FLUSHDB` — 전 큐 + Rate Limit + 캐시가 동시에 날아간다.
  - `queue:{q}:seq` 삭제 — 순번이 1부터 다시 시작하고, `tokens` 테이블 `UNIQUE(token_id, issued_at)`은 막아주지 않으므로(token_id는 새로 발급됨) **같은 큐에 같은 seq를 가진 행이 두 벌** 생긴다.

---

## 이 기능에서 관측이 비어 있는 것 (지표 추가 필요)

| 관측 대상 | 현재 상태 |
|---|---|
| `globalQueue` 깊이 | **미노출.** `RedisQueueEngine.getGlobalQueue()`는 있으나 Gauge 미등록 |
| 배치 사이클 소요 시간 / drain 건수 | **미노출.** `BatchProcessor`에 Timer/Counter 없음 |
| 청크 실패 건수 | 로그만 (`Failed to process chunk`). 메트릭 없음 |
| enqueue 결과 분포(OK/EXISTS/FULL) | **미노출.** `MONITORING_DESIGN.md` 4-2의 `queue_token_enqueue_total`은 **미구현** |
| `queue_waiting_count` Gauge | **미구현.** ZCARD를 직접 조회하는 수밖에 없음 |

프로젝트 전체에서 `MeterRegistry` 사용처가 0건이다 — `MONITORING_DESIGN.md`의 커스텀 메트릭은 전부 설계 단계이며 코드에 없다.
