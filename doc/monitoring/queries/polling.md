# 조회 쿼리 — 폴링

> RunBook: [`doc/monitoring/runbook/polling.md`](../runbook/polling.md)
> 3자 대조는 [`queries/kafka-persistence.md` §3](kafka-persistence.md)에 한 벌만 둔다.

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

## 0. 준비

```bash
Q=q_xxx; SEQ=12345; TOK=tok_019...
export DB_PASSWORD=queueapp1234
alias MYR='mysql -h127.0.0.1 -P3307 -uqueueapp -p"$DB_PASSWORD" queue_platform -t'
alias RR='redis-cli -c -p 7001'    # 조회
alias RW='redis-cli -c -p 7001'    # 쓰기/설정
```

**위험 명령 금지**: `KEYS` · `FLUSHALL` · `ZRANGE key 0 -1` · `HGETALL`(대형 Hash) · `DEBUG SLEEP` · `--scan` 결과를 파이프 없이 통째로 받는 것(수백만 줄).

---

## 1. 특정 사용자 폴링이 왜 404인가 (4단계, 30초)

`poll_verify.lua`가 0을 반환하는 경로는 셋인데, **셋 중 어느 것이든 404가 아닐 수 있다** —
admit되면 `waiting`에서 빠지므로 검증은 실패하지만 응답은 `ready:true`다. ④를 반드시 같이 본다.

```bash
# ① seq에 해당하는 멤버가 있는가
ID=$(RR zrangebyscore "queue:{$Q}:waiting" $SEQ $SEQ); echo "identifier=[$ID]"

# ② tokens Hash에 항목이 있는가
VAL=$(RR hget "queue:{$Q}:tokens" "$ID"); echo "stored=[$VAL]"

# ③ tokenId가 일치하는가
echo "expect=${VAL%%|*}  actual=$TOK"

# ④ admit된 사람인가 (①~③이 전부 실패해도 여기 값이 있으면 404가 아니다)
RW get "queue:{$Q}:admit-by-token:$TOK"
RW zscore "queue:{$Q}:admitted" "$SEQ|$ID"
```

| 단계 | 빈 값/불일치일 때 |
|---|---|
| ① 비었다 | 그 seq의 대기 항목이 없다. **④를 먼저 보라.** ④도 비었고 큐 전체가 비었으면(`zcard` 0) **Redis 유실** ([`queries/kafka-persistence.md` §4](kafka-persistence.md)) |
| ② 비었다 | ZSet엔 있는데 Hash엔 없다 → **Lua 계약 파손. 에스컬레이션** (정상 경로에서는 발생 불가) |
| ③ 불일치 | 남의 seq를 조회한 것. **정상 거절이다** |
| ④ `admit-by-token`에 값이 있다 | **404가 아니라 `ready:true`가 나가야 정상이다.** 404가 나왔다면 회귀다 |
| ④ `admit-by-token`은 비었는데 `admitted`에 score가 있다 | **admitToken TTL 만료 → WAITING 복귀 대기 중.** 복귀 배치가 아직 안 집었다. 지금은 이것도 `TK001`이라 SDK가 종료해버린다 (§79 404 계약 — ErrorCode 미분리, **미해결**) |

---

## 2. 큐 전광판 (사용자가 보는 값의 원본)

```bash
RW get "queue:{$Q}:admit-watermark"    # lastAdmittedSeq. 없으면 0 (아무도 입장 안 함)
RW get "queue:{$Q}:pacing"             # 오버라이드. 없는 것이 정상 — 코드 기본 사다리가 쓰인다
RW exists "queue:{$Q}:seq"             # 0이면 /status가 404 (§79 D3 — 큐 실재 판정)

# 서버가 실제로 내려주는 값
curl -s "http://localhost:8080/api/v1/queues/$Q/status" | jq .data
```
`rank`는 **서버가 계산하지 않는다.** 클라이언트가 `mySeq − lastAdmittedSeq`로 구하고
`pacing` 표에서 간격을 고른다. 응답은 **30만 명 전원 동일**하며 WAS-local 캐시는 없다(§79 D1).

| 관찰 | 판정 |
|---|---|
| `admit-watermark`가 30초 뒤에도 동일 | Tenant가 그 사이 admit을 안 불렀다. **Platform 문제가 아니다** |
| `admit-watermark`가 **감소**했다 | **비정상.** `admit.lua`는 현재값보다 클 때만 쓴다 — 수동 `SET`이나 계약 파손 |
| 사용자마다 값이 미세하게 다르다 | **정상.** WAS마다 읽은 시점이 다르다. SDK가 `max()`로 clamp한다 |
| 순번이 실제보다 많이 남아 보인다 | **정상이고 의도된 것.** watermark는 취소·만료로 빠진 사람을 못 뺀다(항상 상한) |
| `pacing` 키가 없다 | **정상.** 평상시 큐 대부분이 이렇다 |
| `pacing` 키가 있는데 응답이 기본 사다리다 | **형식 오류.** 조용히 폴백한다(15만/s 경로라 로그를 못 남긴다). `상한:초` CSV + 마지막 `*:초` |

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

## 4. Redis 부하 — 개인 폴링 1건 = master 왕복 2회 / `/status` 1건 = 읽기 1회

```bash
RW info commandstats | grep -E 'cmdstat_(evalsha|mget|zrangebyscore|zadd|hget|hmset)'
RW info stats | grep instantaneous_ops_per_sec
RW slowlog get 10
```

| 관찰 | 판정 |
|---|---|
| `evalsha calls` 증가율 ≈ **개인 엔드포인트** RPS × 2 | 구조상 정상 (token-bucket 1회 + poll_verify 1회) |
| `evalsha calls` >> 개인 엔드포인트 RPS × 2 | 클라이언트가 `pacing`(기본 2/5/10/15/20초 + 클라이언트 지터)을 무시 |
| `mget calls` 증가율 ≈ `/status` RPS | 구조상 정상 (3키 1왕복) |
| `mget calls` 폭증 | `/status`는 **인증도 Rate Limit도 없다**(§79). 앱에서 막을 수단이 없으니 CDN·WAF로 간다 |
| `evalsha usec_per_call` | **기준선 수집 필요.** 평상시 폴링 3일치 p50/p95를 재고 p95×3을 경고선으로 |
| `slowlog` 신규 항목 | 0건/시간이 정상 |

> `poll_verify.lua`는 **항상** `ZADD`를 하므로 **쓰기다. replica로 뺄 수 없다.**
> (`ka` 분기는 §82 F안이 삭제했다 — 폴링이 곧 생존 신호다.)
> `token-bucket.lua`도 `HMSET`+`EXPIRE`라 쓰기다. 둘 다 master 부하로 직행한다.
> **`/status`의 `MGET`만이 순수 읽기**이며, §79가 엔드포인트를 쪼갠 이유가 이것이다 —
> 평상시 트래픽을 `EVAL`(master 고정)에서 빼냈다. 다만 replica로 실제로 보내려면
> `ReadFrom` 설정이 따로 필요하다(**미적용**).

### 4-1. 부하 급증 시 유일한 런타임 레버 — `pacing`

```bash
# 전원 폴링 간격 2배. 재배포도 SDK 갱신도 필요 없다
RW set "queue:{$Q}:pacing" '50:4,1000:10,5000:20,10000:30,*:40'
curl -s "http://localhost:8080/api/v1/queues/$Q/status" | jq .data.pacing   # 반영 확인 필수
RW del "queue:{$Q}:pacing"       # 되돌리기 = 코드 기본 사다리
```
**형식**: `상한:간격초` CSV, **마지막은 반드시 `*:초`**(그 이상 전부).
깨지면 조용히 기본 사다리로 폴백하므로 **반드시 응답으로 확인**할 것.
⚠️ 최저 구간을 2초 밑으로 내리지 마라 — 개인 엔드포인트 Rate Limit(cap 5, refill 1.0/s)과 맞물려 있다.

---

## 5. HTTP 측 지표 (PromQL)

```promql
# 개인 엔드포인트 RPS
sum(rate(http_server_requests_seconds_count{uri="/api/v1/queues/{queueId}/tokens/{tokenId}"}[1m]))

# /status RPS — 평상시 폴링의 대부분이 여기다. 용량 산정은 반드시 둘을 더한다
sum(rate(http_server_requests_seconds_count{uri="/api/v1/queues/{queueId}/status"}[1m]))

# /status 404 (미지 queueId) — 인증 없는 경로의 유일한 앱 측 flood 신호
sum(rate(http_server_requests_seconds_count{uri="/api/v1/queues/{queueId}/status",status="404"}[5m]))

# 404 비율 (TK001)
sum(rate(http_server_requests_seconds_count{uri="/api/v1/queues/{queueId}/tokens/{tokenId}",status="404"}[5m]))
/
sum(rate(http_server_requests_seconds_count{uri="/api/v1/queues/{queueId}/tokens/{tokenId}"}[5m]))

# p99 지연
histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/v1/queues/{queueId}/tokens/{tokenId}"}[5m])))
```

| 지표 | 정상 | 이상 |
|---|---|---|
| 404 비율(개인) | < 0.05 | > 0.50 → 랜덤 tokenId 공격 의심 ([`runbook/rate-limit.md`](../runbook/rate-limit.md)). **단, Tenant가 admitToken을 못 쓰는 사고 중에는 정상 대기자의 복귀 대기 404가 섞인다** (§1 ④) |
| `/status` 404 비율 | < 0.01 | 급증 = 미지 queueId 스캔. Redis 1왕복에서 끝나 DB는 안전하지만 **앱 측 제한이 없다** → CDN·WAF |
| p99 | **기준선 수집 필요.** 폴링 단독 부하 실측이 없다. 실사용 3일치 p99를 재라 (`doc/ROADMAP.md`의 "p99 < 50ms"는 **목표치이지 실측이 아니다**) | |

**429는 `uri` 라벨이 `UNKNOWN`으로 집계될 가능성이 높다** — RateLimitFilter가 DispatcherServlet 전에 응답을 끝내 URI 패턴이 확정되지 않는다. 429를 셀 때는 `uri` 없이 `status="429"`만 쓰라. (**실측 확인 필요 — 라벨 값을 한 번 눈으로 볼 것.**)

---

## 6. 시계 오차 (last-active score의 출처)

```bash
timedatectl show -p NTPSynchronized -p TimeUSec
RTS=$(redis-cli -c -p 7001 time | awk 'NR==1{s=$1} NR==2{printf "%d", s*1000+int($1/1000)}')
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
}' /tmp/rebuild_$Q.tsv | redis-cli -c -p 7001 --pipe
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
