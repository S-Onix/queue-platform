# 조회 쿼리 — 폴링

> RunBook: [`doc/monitoring/runbook/polling.md`](../runbook/polling.md)
> 3자 대조는 [`queries/kafka-persistence.md` §3](kafka-persistence.md)에 한 벌만 둔다.

## 0. 준비

```bash
Q=q_xxx; SEQ=12345; TOK=tok_019...
export DB_PASSWORD=queueapp1234
alias MYR='mysql -h127.0.0.1 -P3307 -uqueueapp -p"$DB_PASSWORD" queue_platform -t'
alias RR='redis-cli -p 6380'    # 조회
alias RW='redis-cli -p 6379'    # 쓰기/설정
```

**위험 명령 금지**: `KEYS` · `FLUSHALL` · `ZRANGE key 0 -1` · `HGETALL`(대형 Hash) · `DEBUG SLEEP` · `--scan` 결과를 파이프 없이 통째로 받는 것(수백만 줄).

---

## 1. 특정 사용자 폴링이 왜 404인가 (3단계, 30초)

`poll_verify.lua`가 0을 반환하는 경로는 셋뿐이다. 순서대로 재현한다.

```bash
# ① seq에 해당하는 멤버가 있는가
ID=$(RR zrangebyscore "queue:{$Q}:waiting" $SEQ $SEQ); echo "identifier=[$ID]"

# ② tokens Hash에 항목이 있는가
VAL=$(RR hget "queue:{$Q}:tokens" "$ID"); echo "stored=[$VAL]"

# ③ tokenId가 일치하는가
echo "expect=${VAL%%|*}  actual=$TOK"
```

| 단계 | 빈 값/불일치일 때 |
|---|---|
| ① 비었다 | 그 seq의 대기 항목이 없다. 큐 전체가 비었는지 `zcard`로 확인 → 0이면 **Redis 유실** ([`queries/kafka-persistence.md` §4](kafka-persistence.md)) |
| ② 비었다 | ZSet엔 있는데 Hash엔 없다 → **Lua 계약 파손. 에스컬레이션** (정상 경로에서는 발생 불가) |
| ③ 불일치 | 남의 seq를 조회한 것. **정상 거절이다** |

---

## 2. 큐 스냅샷 (사용자가 보는 값의 원본)

```bash
RR zrange "queue:{$Q}:waiting" 0 0 WITHSCORES    # frontSeq (맨 앞 순번)
RR zcard  "queue:{$Q}:waiting"                   # total
```
응답의 `rank`는 서버가 `seq - frontSeq`로 계산하고, 클라이언트에 내려가는 값은
**최대 2초 묵은 Caffeine 캐시**(`QueueSnapshotCache`, `expireAfterWrite 2s`, **WAS-local**)다.

| 관찰 | 판정 |
|---|---|
| `frontSeq`가 30초 뒤에도 동일 | **정상.** admit 미구현 → `ZREM` 0건 → 맨 앞은 절대 안 빠진다 |
| WAS별로 `total`이 다르다 | **정상.** 캐시가 WAS-local이라 최대 N종류의 값이 동시에 존재한다 |
| `total`이 감소했다 | 비정상. 누군가 `ZREM`/`DEL`을 실행했다는 뜻 |

---

## 3. `last-active` 누적 감시 (가장 먼저 OOM을 부르는 곳)

```bash
RR zcard "queue:{$Q}:last-active"
RR zcard "queue:{$Q}:waiting"
RR memory usage "queue:{$Q}:last-active"        # 표본 추정치(SAMPLES 5). 정확값 아님
RR memory usage "queue:{$Q}:last-active" samples 0   # 정확값. ⚠️ O(N) — 30만 멤버면 수백 ms 블로킹. replica에서만
```

| 지표 | 정상 | 이상 |
|---|---|---|
| `zcard last-active` ≤ `zcard waiting` | 정상 | **초과 = 좀비 누적.** `ZREM`·`EXPIRE`가 전 소스에 0건이라 되돌아오지 않는다 |
| 증가 속도 | 이벤트당 최대 `zcard waiting` 만큼 | 이벤트 100회면 같은 키에 waiting×100 멤버가 영구 적재 |

```bash
# 인스턴스 전체 메모리
RR info memory | grep -E 'used_memory_human|used_memory_peak_human|maxmemory_human|maxmemory_policy'
RR info keyspace
```

| 지표 | 정상 | 이상 |
|---|---|---|
| `used_memory / maxmemory` | ≤ 0.80 | > 0.80 경고 / **1.00 도달 시 `noeviction`이라 모든 쓰기가 `OOM command not allowed` 로 실패** (enqueue·폴링·Rate Limit 동시 정지) |
| `maxmemory` | 현재 실측 `1073741824`(1GB) | 변경 시 물리 메모리 여유 확인 |
| `maxmemory-policy` | `noeviction` | **`allkeys-*`로 바꾸면 대기열이 evict된다. 절대 금지** |

```bash
# 큰 키 상위 목록 — replica에서, 저부하 시간대에만
RR --bigkeys -i 0.1     # -i 로 슬립을 넣어 부하를 낮춘다. master 금지
```

---

## 4. Redis 부하 — 폴링 1건 = master 왕복 2회

```bash
RW info commandstats | grep -E 'cmdstat_(evalsha|zrangebyscore|zadd|hget|hmset)'
RW info stats | grep instantaneous_ops_per_sec
RW slowlog get 10
```

| 관찰 | 판정 |
|---|---|
| `evalsha calls` 증가율 ≈ 폴링 RPS × 2 | 구조상 정상 (token-bucket 1회 + poll_verify 1회) |
| `evalsha calls` >> 폴링 RPS × 2 | 클라이언트가 `nextPollAfterSec`(등급 2/5/10/15/20 + 지터 → 실제 2~25초)를 무시 |
| `evalsha usec_per_call` | **기준선 수집 필요.** 평상시 폴링 3일치 p50/p95를 재고 p95×3을 경고선으로 |
| `slowlog` 신규 항목 | 0건/시간이 정상 |

> `poll_verify.lua`는 `ka=1`일 때 `ZADD`를 하므로 **쓰기다. replica로 뺄 수 없다.**
> `token-bucket.lua`도 `HMSET`+`EXPIRE`라 쓰기다. 둘 다 master 부하로 직행한다.

---

## 5. HTTP 측 지표 (PromQL)

```promql
# 폴링 RPS
sum(rate(http_server_requests_seconds_count{uri="/api/v1/queues/{queueId}/tokens/{tokenId}"}[1m]))

# 404 비율 (TK001)
sum(rate(http_server_requests_seconds_count{uri="/api/v1/queues/{queueId}/tokens/{tokenId}",status="404"}[5m]))
/
sum(rate(http_server_requests_seconds_count{uri="/api/v1/queues/{queueId}/tokens/{tokenId}"}[5m]))

# p99 지연
histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/v1/queues/{queueId}/tokens/{tokenId}"}[5m])))
```

| 지표 | 정상 | 이상 |
|---|---|---|
| 404 비율 | < 0.05 | > 0.50 → 랜덤 tokenId 공격 의심 ([`runbook/rate-limit.md`](../runbook/rate-limit.md)) |
| p99 | **기준선 수집 필요.** 폴링 단독 부하 실측이 없다. 실사용 3일치 p99를 재라 (`doc/ROADMAP.md`의 "p99 < 50ms"는 **목표치이지 실측이 아니다**) | |

**429는 `uri` 라벨이 `UNKNOWN`으로 집계될 가능성이 높다** — RateLimitFilter가 DispatcherServlet 전에 응답을 끝내 URI 패턴이 확정되지 않는다. 429를 셀 때는 `uri` 없이 `status="429"`만 쓰라. (**실측 확인 필요 — 라벨 값을 한 번 눈으로 볼 것.**)

---

## 6. 시계 오차 (last-active score의 출처)

```bash
timedatectl show -p NTPSynchronized -p TimeUSec
RTS=$(redis-cli -p 6380 time | awk 'NR==1{s=$1} NR==2{printf "%d", s*1000+int($1/1000)}')
echo "was=$(date +%s%3N)  redis=$RTS"
# WAS 다수일 때는 각 WAS에서 date +%s%3N 을 동시에 찍어 비교한다
```

| 지표 | 정상 | 이상 |
|---|---|---|
| `NTPSynchronized` | `yes` | `no` → `sudo systemctl restart systemd-timesyncd` |
| WAS 간 시각 차 | < 1,000ms | > 5,000ms → 즉시 조치 |
| `/proc/uptime` vs 벽시계 경과 | 일치 | 크게 적으면 **VM suspend 발생** (WSL2에서 흔함) |

```bash
# last-active score가 미래인지 확인 (미래 값이 있으면 시계 오차 확정)
NOW=$(date +%s%3N)
RR zrevrangebyscore "queue:{$Q}:last-active" "+inf" "$NOW" WITHSCORES LIMIT 0 5
```
**출력이 있으면 이상.** 정상은 무출력(모든 score가 현재보다 과거).

> 현재 `last-active`를 **읽는 코드는 0건**이라 실질 영향이 없다. inactive_ttl 배치(**미구현**)가 들어오면 시계 오차가 곧 조기 EXPIRE(대기자 강제 이탈)로 직결된다.

---

## 7. Redis 유실 시 대기열 수동 재구성 (복구 도구 미구현)

**⚠️ 이 절차는 검증된 적이 없다. 실행 전 반드시 큐를 정지시키고 백업(`BGSAVE`)하라.**

```bash
# ① Replica(3307)에서 재구성 대상 추출. status 0 = WAITING
# INTO OUTFILE 은 쓰지 마라 — secure_file_priv=/var/lib/mysql-files/ 라 /tmp 쓰기가 막힌다(실측)
MYR -N -B -e "
SET time_zone = '+00:00';
SELECT user_id, seq, token_id,
       UNIX_TIMESTAMP(issued_at) * 1000 + (MICROSECOND(issued_at) DIV 1000) AS issued_ms
FROM tokens
WHERE queue_id = '$Q' AND status = 0 AND issued_at >= UTC_DATE()
ORDER BY seq" > /tmp/rebuild_$Q.tsv
wc -l /tmp/rebuild_$Q.tsv
```
> ⚠️ `SET time_zone = '+00:00';` 이 없으면 9시간 어긋난다.
> **실측 확인**: 세션 기본 `@@time_zone`은 `+09:00`이고 `issued_at`에는 **UTC 벽시계**가 들어 있다
> (`TokenLifecycleConsumer.toToken()`이 `ZoneOffset.UTC`로 변환).

```bash
# ② Redis에 재삽입 (파이프 모드. 건별 왕복 금지)
awk -F'\t' -v q="$Q" '{
  printf "ZADD queue:{%s}:waiting %s %s\n", q, $2, $1
  printf "HSET queue:{%s}:tokens %s %s|%s\n", q, $1, $3, $4
}' /tmp/rebuild_$Q.tsv | redis-cli -p 6379 --pipe
# ⚠️ identifier(user_id)에 공백·개행이 있으면 inline 프로토콜이 깨진다.
#    identifier는 Tenant가 자유 지정하는 값이라 실제로 있을 수 있다 — 먼저 확인할 것:
#    awk -F'\t' '$1 ~ / |\r/ {print NR": ["$1"]"}' /tmp/rebuild_$Q.tsv    (출력 없어야 안전)

# ③ seq 카운터를 DB 최대값 이상으로 복원 — 이걸 빼먹으면 순번이 재사용된다
DS=$(MYR -N -e "SELECT MAX(seq) FROM tokens WHERE queue_id='$Q' AND issued_at >= UTC_DATE()")
RW set "queue:{$Q}:seq" "$DS"

# ④ 검증: 3자 대조를 다시 돌린다
```

**되돌리기**: 재구성 전 `BGSAVE`로 만든 RDB 파일. 그것 없이는 되돌릴 수 없다.
**복구 완전성의 한계**: DB에 없는 건(= 유령 토큰)은 재구성되지 않는다. 복구는 DB 신선도만큼만 된다.
