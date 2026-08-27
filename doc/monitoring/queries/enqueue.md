# 조회 쿼리 — Enqueue

> RunBook: [`doc/monitoring/runbook/enqueue.md`](../runbook/enqueue.md)
> 3자 대조(HTTP ↔ Redis ↔ MySQL)는 중복을 피해 [`queries/kafka-persistence.md` §3](kafka-persistence.md)에 한 벌만 둔다.

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

## 0. 준비 — 복붙 전에 이것부터

```bash
Q=q_xxx                                  # 예: q_bts2026
export DB_PASSWORD=queueapp1234          # local 기본값 (queue-consumer/application-local.yml)
alias MYR='mysql -h127.0.0.1 -P3307 -uqueueapp -p"$DB_PASSWORD" queue_platform -t'   # ← Replica(3307). 조회는 전부 여기로
alias MYW='mysql -h127.0.0.1 -P3306 -uqueueapp -p"$DB_PASSWORD" queue_platform -t'   # ← Master. 쓰기 전용
alias RR='redis-cli -c -p 7001'             # ← Replica. 조회는 전부 여기로
alias RW='redis-cli -c -p 7001'             # ← Master. 쓰기/설정 전용
```

**위험 명령 — 운영에서 절대 금지**
`KEYS` · `FLUSHALL` · `FLUSHDB` · `DEBUG SLEEP` · `HGETALL`(30만 필드) · `ZRANGE key 0 -1` · Master(3306)에 분석 SQL · `--scan` 결과를 파이프 없이 통째로 받는 것(수백만 줄).
Redis는 단일 스레드다. 위 명령 하나가 전 서비스를 멈춘다.

---

## 1. 큐 현재 상태 (Redis) — 가장 먼저 보는 4줄

```bash
RR zcard "queue:{$Q}:waiting"       # 대기 인원
RR get   "queue:{$Q}:seq"           # 발급된 순번 카운터 (INCR)
RR hlen  "queue:{$Q}:tokens"        # 발급된 토큰 수
RR zcard "queue:{$Q}:last-active"   # keepalive 기록 수
```

| 관계 | 정상 | 이상일 때 |
|---|---|---|
| `hlen tokens` == `zcard waiting` | **항상 같다** | 다르면 Lua 계약 파손 또는 부분 삭제 → 즉시 에스컬레이션 |
| `get seq` ≥ `zcard waiting` | 차이 = 중복 identifier(EXISTS) 횟수 | `seq` < `zcard`는 불가능. 나오면 `seq` 키가 삭제·재설정된 것 |
| `zcard last-active` ≤ `zcard waiting` | 정상 | **초과하면 좀비 누적**(삭제 경로 0건). [`runbook/polling.md`](../runbook/polling.md) 메모리 항목으로 |

> `waiting`·`seq`·`tokens`·`last-active` **넷 다 삭제·만료 경로가 코드에 없다.** 이 값들은 단조증가한다 — "며칠 뒤 얼마여야 정상"이라는 기준을 세울 수 없고, 세워도 안 된다.

---

## 2. 정원 대비 여유 (FULL 예측)

```bash
CAP=$(MYR -N -e "SELECT max_capacity FROM queues WHERE queue_id='$Q'")
CUR=$(RR zcard "queue:{$Q}:waiting")
echo "$CUR / $CAP = $(awk "BEGIN{printf \"%.2f\", $CUR/$CAP}")"
```

- **≥ 0.80** → 경고 (`MONITORING_DESIGN.md` 4-2 기준)
- **≥ 1.00** → enqueue가 429(Q005)로 거절 시작
- ✏️ **"감소하지 않는다(admit 미구현)"는 폐기된 서술이다.** admit과 회수 배치 3경로가 빼므로
  이 값은 **오르내린다.** 안 줄면 원인이 둘 — Tenant가 `admit`을 안 부르거나, 회수 배치가 밀렸거나.

---

## 3. 큐 맨 앞/뒤 확인 (범위를 반드시 좁혀라)

```bash
RR zrange    "queue:{$Q}:waiting" 0 4 WITHSCORES     # 앞 5명
RR zrevrange "queue:{$Q}:waiting" 0 4 WITHSCORES     # 뒤 5명
```
**`0 -1`(전체)은 금지.** 30만 멤버를 한 번에 직렬화하면 Redis가 수 초간 멈추고 네트워크 버퍼가 터진다.

- 앞 5명의 score(=seq)가 시간이 지나도 그대로 → ✏️ **더 이상 정상이 아니다.**
  admit이 앞에서부터 빼므로 정상이면 앞쪽 seq가 올라간다. 안 오르면 Tenant가 `admit`을 안 부르는 것이다.
  (`/status`의 `lastAdmittedSeq`가 멈춰 있는지로 교차 확인할 수 있다.)
- 뒤 5명의 score가 안 늘어남 → 유입이 끊겼거나 배치가 안 돈다

---

## 4. 배치가 살아 있는가 (Redis seq 증가율)

```bash
A=$(RR get "queue:{$Q}:seq"); sleep 10; B=$(RR get "queue:{$Q}:seq")
echo "10초간 처리: $((B-A))건  (초당 $(( (B-A)/10 )))"
```

| 값 | 판정 |
|---|---|
| 0 | 유입이 없거나 **배치 정지**. HTTP 유입 RPS와 대조하라(§5) |
| 1~50,000 | 정상 범위. 상한은 `MAX_DRAIN 5000/사이클 × 1사이클/초 × WAS 대수` |
| 유입 RPS × 10 미만 | 처리가 유입을 못 따라간다 → in-memory 적체 |

---

## 5. HTTP 측 지표 (PromQL — queue-api 8080만 수집됨)

```promql
# 유입 RPS
sum(rate(http_server_requests_seconds_count{uri="/api/v1/queues/{queueId}/tokens",method="POST"}[1m]))

# p50 / p99 지연
histogram_quantile(0.5, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/v1/queues/{queueId}/tokens",method="POST"}[5m])))
histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/v1/queues/{queueId}/tokens",method="POST"}[5m])))

# 상태 코드 분포 (503=QE001, 429=Q005 또는 RL001, 404=Q001)
sum by (status) (rate(http_server_requests_seconds_count{uri="/api/v1/queues/{queueId}/tokens",method="POST"}[5m]))
```

| 지표 | 정상 | 이상 |
|---|---|---|
| p50 | **≈ 주기/2 + 10~19ms** (drain 20ms → 20~30ms) | ≫ 주기/2 → 틱 밀림 · **≈1초면 옛 주기(1000ms)로 떠 있는 것** |
| p99 | **큐 40 기준** 200rps 32ms · 1,000rps 75ms · 2,000rps 104ms | 같은 유입·같은 큐 수에서 2배 이상 → 적체 |
| 5xx 비율 | 0 | > 0 → RunBook의 503 항목 |

> **이 수치는 WSL2 단일 머신 실측이다. 프로덕션 용량 산정 근거로 쓰지 마라.**
>
> 🔴 **2026-08-27에 drain 주기가 1000ms → 20ms로 바뀌었다.** 옛 판본의 "p50 0.4~1.1s 정상"을
> 그대로 따르면 **주기가 50배 밀린 장애를 정상으로 넘긴다.** 판정은 값이 아니라 `주기/2`와 대조해라.

```promql
# 커넥션 풀 (배치가 매 사이클 queues 테이블을 읽는다 — 캐시 없음)
hikaricp_connections_pending          # 정상 0. > 0 지속이면 배치가 막힌다
hikaricp_connections_active           # pool size(local 10 / prod 50)의 80% 미만
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}   # 0.85 초과 시 globalQueue 적체 의심
```

**`globalQueue` 깊이·배치 소요시간·drain 건수를 노출하는 지표는 없다(미노출 — 지표 추가 필요).**

---

## 6. Redis 부하 진단 (master. 읽기는 replica로)

```bash
RW --stat                                     # 5초 관찰 후 Ctrl-C
RW info commandstats | grep -E 'cmdstat_(evalsha|zadd|zrank|incr|hset)'
RW info stats  | grep -E 'instantaneous_ops_per_sec|keyspace_'
RW slowlog get 10                             # 10ms(기본) 초과 명령
RW info clients | grep -E 'connected_clients|blocked_clients'
```

| 지표 | 정상 | 이상 |
|---|---|---|
| `slowlog` 신규 항목 | 0건/시간 | Lua가 잡히면 청크 크기·ZSet 크기 확인 |
| `evalsha usec_per_call` | **기준선 수집 필요** — 청크 건수(1~500)에 비례해 10배 이상 변동. 평상시 3일치 p50/p95를 재고 p95×3을 경고선으로 | |
| `connected_clients` | Lettuce는 커넥션을 공유한다. WAS 대수 + 여유 정도 | 수천이면 커넥션 누수 |

---

## 7. 배치 실패 흔적 (로그 — 메트릭이 없으므로 로그가 유일한 증거)

```bash
LOG=<queue-api 로그 경로>
grep -c "Enqueue timeout after 30s"          $LOG   # → 배치/Redis 정지
grep -c "Failed to process chunk for queue"  $LOG   # → Lua 실행 실패
grep -c "Result size mismatch"               $LOG   # → Lua 계약 파손. 0이 아니면 즉시 에스컬레이션
grep -c "Result order mismatch"              $LOG   # → 동상
grep -c "Queue not found during batch"       $LOG   # → 삭제된 큐로 요청이 계속 옴
grep -c "enqueue 이벤트 발행 실패"            $LOG   # → Kafka. kafka-persistence.md 로
```
**전부 0이어야 정상.** `Result * mismatch`는 청크 전체를 실패시키는 심각 오류다.

---

## 8. DB 쪽 큐 메타 (Replica)

```sql
-- Replica(3307). queues는 작아서 풀스캔해도 안전
SELECT queue_id, tenant_id, status, max_capacity
FROM queues WHERE queue_id = 'q_xxx';

-- 큐가 많을 때: 정원 대비 위험 큐 목록은 DB만으로는 못 만든다.
-- 대기 인원이 Redis에만 있기 때문 → §2를 큐별로 반복해야 한다.
```

---

## 9. 응급 조치 명령 (되돌리는 방법까지)

```sql
-- 유입 차단: 큐 일시 정지 (Master 3306). 다음 요청부터 503(Q004)
UPDATE queues SET status = <PAUSED> WHERE queue_id = 'q_xxx';
-- 되돌리기
UPDATE queues SET status = <ACTIVE> WHERE queue_id = 'q_xxx';

-- 정원 확대 (다음 배치 사이클부터 반영 — getMaxCapacity가 매 사이클 DB를 읽는다)
UPDATE queues SET max_capacity = <새값> WHERE queue_id = 'q_xxx';
-- 되돌리기: 원래 값으로 같은 UPDATE
```

status 코드 값은 `doc/STATE.md` 참조.

```bash
# 종료된 이벤트의 큐 키 정리 — ⚠️ 되돌릴 수 없다. 진행 중 큐에 절대 금지
RW del "queue:{$Q}:waiting" "queue:{$Q}:tokens" "queue:{$Q}:last-active"
# queue:{$Q}:seq 는 남긴다. 지우면 순번이 1부터 재시작해 DB와 충돌한다
```
