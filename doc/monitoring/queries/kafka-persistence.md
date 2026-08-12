# 조회 쿼리 — Kafka 비동기 영속화 / 정합성 대조

> RunBook: [`doc/monitoring/runbook/kafka-persistence.md`](../runbook/kafka-persistence.md)
> **§3의 3자 대조가 이 문서의 핵심이다.** 발행 갭(Redis O / DB X)을 발견하는 유일한 수단이고, reconciliation은 미구현이다.

## 0. 준비

```bash
Q=q_xxx
export DB_PASSWORD=queueapp1234
export KAFKA_HOME=${KAFKA_HOME:-/home/sonix/kafka_2.13-4.2.1}
BS=localhost:9092,localhost:9094,localhost:9096
alias MYR='mysql -h127.0.0.1 -P3307 -uqueueapp -p"$DB_PASSWORD" queue_platform -t'   # Replica. 조회 전용
alias MYW='mysql -h127.0.0.1 -P3306 -uqueueapp -p"$DB_PASSWORD" queue_platform -t'   # Master. 쓰기 전용
alias RR='redis-cli -p 6380'
```

### ⚠️ 시각(TZ) 함정 — 이걸 모르면 대조가 통째로 틀린다

`TokenLifecycleConsumer.toToken()`이 `LocalDateTime.ofInstant(instant, ZoneOffset.UTC)`로 변환하므로
**`tokens.issued_at` 컬럼에는 UTC 벽시계 값이 들어간다.** 그런데 JDBC URL은 `serverTimezone=Asia/Seoul`이고
MySQL **서버** `default-time-zone`은 여전히 `+09:00`이라, **셸에서 `mysql`로 붙으면**
`NOW()`/`CURDATE()`가 KST다 → UTC로 저장된 값과 **9시간 어긋난다.**

> 앱(JDBC)은 `forceConnectionTimeZoneToSession=true`로 세션이 UTC라 문제가 없다.
> **CLI만 다르다** — 아래 규칙은 사람이 직접 쿼리할 때의 이야기다. (DECISIONS §77)

```sql
-- ❌ 틀림: KST 기준. UTC로 저장된 값과 최대 9시간 어긋난다
WHERE issued_at >= CURDATE()
-- ✅ 맞음
WHERE issued_at >= UTC_DATE()
WHERE issued_at >= UTC_TIMESTAMP() - INTERVAL 1 HOUR
```

**위험 명령 금지**: `KEYS` · `FLUSHALL` · `HGETALL`(대형 Hash) · 컨슈머 그룹 오프셋 리셋 · 인덱스 없는 `tokens` 풀스캔 · Master(3306)에 분석 SQL.

---

## 1. Kafka 상태 (3줄)

```bash
# consumer lag
$KAFKA_HOME/bin/kafka-consumer-groups.sh --bootstrap-server $BS --describe --group db-writer

# 토픽 끝 오프셋 합계 (총 발행량)
$KAFKA_HOME/bin/kafka-run-class.sh kafka.tools.GetOffsetShell --bootstrap-server $BS \
  --topic token-lifecycle | awk -F: '{s+=$3} END {print "produced="s}'

# DLT 누적
$KAFKA_HOME/bin/kafka-run-class.sh kafka.tools.GetOffsetShell --bootstrap-server $BS \
  --topic token-lifecycle.DLT | awk -F: '{s+=$3} END {print "DLT="s}'
```

| 지표 | 정상 | 이상 |
|---|---|---|
| LAG 합계 | 평상시 0~수백 | **부하 중 임계값은 기준선 수집 필요.** 평상시 3일치 p95를 재고 그 10배를 경고선으로 시작 |
| `CONSUMER-ID` 빈 파티션 | 0개 | 1개라도 있으면 컨슈머 미배정 |
| DLT 증가량 | **0 / 시간** | 1건이라도 늘면 조사 |
| 파티션 수 | **18** | 다르면 순서 보장 전제가 깨진 것 |

```bash
# ISR 상태 — min.insync.replicas=2 미만 파티션이 있으면 그 파티션 발행이 전부 실패한다
$KAFKA_HOME/bin/kafka-topics.sh --bootstrap-server $BS --describe --topic token-lifecycle \
  | awk -F'Isr: ' '/Partition:/ {split($2,b,"\t"); n=split(b[1],c,","); if (n < 2) print "ISR 부족: "$0}'
```
**출력이 한 줄이라도 있으면 즉시 조치.** 정상은 무출력.

---

## 2. DB 적재 상태 (Replica)

```sql
-- 큐별 적재 행수 / seq 범위 / 결번
-- idx_tokens_queue_status_issued (queue_id, status, issued_at) 프리픽스 사용
SELECT COUNT(*)                                AS db_rows,
       COUNT(DISTINCT token_id)                AS uniq_token,
       COUNT(DISTINCT seq)                     AS uniq_seq,
       MIN(seq)                                AS min_seq,
       MAX(seq)                                AS max_seq,
       MAX(seq) - MIN(seq) + 1 - COUNT(DISTINCT seq) AS seq_gap
FROM tokens
WHERE queue_id = 'q_xxx'
  AND issued_at >= UTC_DATE();          -- 파티션 프루닝 + TZ 주의
```

| 컬럼 | 정상 | 이상 |
|---|---|---|
| `db_rows` == `uniq_token` == `uniq_seq` | 같아야 한다 | 다르면 중복 적재 또는 seq 재사용 |
| `seq_gap` | **0** | > 0 → 결번 = 유령 토큰 후보. §3으로 |
| `min_seq` | 1 (큐 신규 생성 시) | 1이 아니면 앞부분이 통째로 누락됐거나 이전 이벤트 잔여 |

```sql
-- 적재 지연 감시: 최근 1분간 들어온 행수 (0이면 컨슈머 정지 의심)
SELECT COUNT(*) FROM tokens
WHERE issued_at >= UTC_TIMESTAMP() - INTERVAL 1 MINUTE;
```

---

## 3. ★ 3자 대조 (HTTP 응답 ↔ Redis ↔ MySQL)

### 3-1. 카운트 대조 — 30초 안에 끝난다

```bash
ZC=$(RR zcard "queue:{$Q}:waiting")
HL=$(RR hlen  "queue:{$Q}:tokens")
SQ=$(RR get   "queue:{$Q}:seq")
DB=$(MYR -N -e "SELECT COUNT(*) FROM tokens WHERE queue_id='$Q' AND issued_at >= UTC_DATE()")
printf "zcard=%s hlen=%s seq=%s db=%s   ghost=%s\n" "$ZC" "$HL" "$SQ" "$DB" "$((HL-DB))"
```

**판정표 — 넷이 전부 같은 게 아니다. 이 관계로 읽어라:**

| 관계 | 의미 | 정상? |
|---|---|---|
| `hlen` == `zcard` | OK 결과에서만 둘 다 쓰고, 지우는 코드는 0건 | **항상 같아야 한다.** 다르면 Lua 파손 → 에스컬레이션 |
| `seq` ≥ `zcard` | 차이 = EXISTS(중복 identifier) 발생 횟수. INCR한 seq를 버린다(`enqueue_bulk.lua:62,66`) | 정상. `seq < zcard`는 불가능 |
| `db` == `hlen` | `publish()`는 `result.isOk()`일 때만 호출된다(`QueueEngineService.java:52`) | **정상이면 같다** |
| `hlen - db` > 0 | **유령 토큰** (Redis O / DB X) | ⚠️ lag=0 확인 후에만 판정 |
| `hlen - db` < 0 | 이전 이벤트 행이 섞였다 | `issued_at` 범위를 좁혀 다시 |

> **판정 전에 반드시 `lag = 0`을 확인하라.** lag이 남아 있으면 `hlen > db`는 정상(아직 안 밀린 것)이다.

### 3-2. 유령 토큰 목록 뽑기 (어느 tokenId인가)

```bash
# ── Redis 쪽: HSCAN 커서 루프. HGETALL 금지(대형 Hash면 Redis가 멈춘다)
OUT=/tmp/redis_tokens_$Q.tsv; : > "$OUT"
cur=0
while :; do
  res=$(RR hscan "queue:{$Q}:tokens" "$cur" count 500)
  cur=$(printf '%s\n' "$res" | head -1)
  printf '%s\n' "$res" | tail -n +2 | paste - - \
    | awk -F'\t' '{split($2,v,"|"); print $1"\t"v[1]"\t"v[2]}' >> "$OUT"
  [ "$cur" = "0" ] && break
done
sort -k2,2 "$OUT" -o "$OUT"
wc -l "$OUT"      # = hlen 이어야 한다

# ── DB 쪽
MYR -N -B -e "SELECT token_id FROM tokens WHERE queue_id='$Q' AND issued_at >= UTC_DATE()" \
  | sort > /tmp/db_tokens_$Q.txt

# ── 차집합: Redis에는 있고 DB에는 없는 것 = 유령 토큰
awk -F'\t' 'NR==FNR{d[$1];next} !($2 in d)' /tmp/db_tokens_$Q.txt "$OUT" > /tmp/ghost_$Q.tsv
wc -l /tmp/ghost_$Q.tsv          # 0이어야 정상
head -5 /tmp/ghost_$Q.tsv        # identifier <TAB> tokenId <TAB> issuedAt(epoch ms)
```

**출력 형식**: `identifier`, `tokenId`, `issuedAt(epoch millis)` — DB 재구성에 필요한 값이 전부 Redis에 있다(`enqueue_bulk.lua:75`가 `tokenId|issuedAt`으로 저장하는 이유).
`seq`가 추가로 필요하면:
```bash
while IFS=$'\t' read -r id tok ts; do
  s=$(RR zscore "queue:{$Q}:waiting" "$id")
  echo -e "$id\t$tok\t$ts\t${s%.*}"
done < /tmp/ghost_$Q.tsv > /tmp/ghost_full_$Q.tsv
```

### 3-3. 수동 보정 INSERT (reconciliation 미구현이므로 사람 손으로)

```bash
# epoch millis → UTC DATETIME(3) 문자열. ⚠️ 1ms만 어긋나도 UNIQUE(token_id, issued_at)가 다른 행으로 본다
TENANT=$(MYR -N -e "SELECT tenant_id FROM queues WHERE queue_id='$Q'")
awk -F'\t' -v q="$Q" -v t="$TENANT" '{
  printf "INSERT INTO tokens (token_id, queue_id, tenant_id, user_id, seq, status, issued_at) VALUES (%s,%s,%s,%s,%s,0,FROM_UNIXTIME(%s/1000)) ON DUPLICATE KEY UPDATE token_id=token_id;\n", \
    "\x27"$2"\x27", "\x27"q"\x27", t, "\x27"$1"\x27", $4, $3
}' /tmp/ghost_full_$Q.tsv > /tmp/fix_$Q.sql

head -3 /tmp/fix_$Q.sql          # ← 반드시 눈으로 확인하고 실행할 것
# MYW < /tmp/fix_$Q.sql
```

> ⚠️ `FROM_UNIXTIME()`은 **세션 타임존**을 따른다. 세션이 KST면 값이 9시간 어긋난다.
> 실행 전 `SET time_zone = '+00:00';` 을 스크립트 맨 위에 넣거나, 한 건을 먼저 넣고
> `SELECT issued_at FROM tokens WHERE token_id='...'` 로 Redis의 epoch와 일치하는지 확인하라.

**되돌리기**:
```sql
-- Master(3306). 넣은 token_id 목록을 보관해 두었다가
DELETE FROM tokens WHERE token_id IN ('tok_...','tok_...') AND issued_at >= UTC_DATE();
```

**하면 안 되는 것**: 유령 토큰을 컨슈머 오프셋 되감기로 해결하려는 시도. 이 이벤트는 **Kafka에 애초에 발행되지 않았다.** replay해도 안 나오고, 이미 적재된 수십만 건만 재처리된다.

---

## 4. Redis 유실 감지 (Redis < DB — 3자 대조의 반대 방향)

```bash
RS=$(RR get "queue:{$Q}:seq")
DS=$(MYR -N -e "SELECT COALESCE(MAX(seq),0) FROM tokens WHERE queue_id='$Q' AND issued_at >= UTC_DATE()")
echo "redisSeq=$RS dbMaxSeq=$DS"
RR exists "queue:{$Q}:seq"
```

| 조건 | 판정 |
|---|---|
| `exists seq` = 0 인데 DB에 행이 있다 | **Redis 전손.** 대기열이 통째로 사라졌다 |
| `redisSeq < dbMaxSeq` | **RDB 롤백 / failover로 옛 상태 복귀.** 순번이 재사용되며 DB와 충돌 시작 |
| `redisSeq ≥ dbMaxSeq` | 정상 |

복구 도구는 **미구현**이다. 발견 시 즉시 해당 큐를 정지시키고([`queries/enqueue.md` §9](enqueue.md)) 에스컬레이션하라 — 순번 재사용이 시작되면 되돌리기가 급격히 어려워진다.

---

## 5. 멱등성 검증 (재처리해도 안전한가 — 사후 확인용)

```sql
-- 중복 적재가 있었는지. ON DUPLICATE KEY가 흡수하면 0건이어야 한다
SELECT token_id, issued_at, COUNT(*) c
FROM tokens WHERE queue_id='q_xxx' AND issued_at >= UTC_DATE()
GROUP BY token_id, issued_at HAVING c > 1 LIMIT 20;
```
**0건이 정상.** (오프셋 5,000 되감기 재소비 테스트에서 행 수 불변 확인됨.)

---

## 6. 컨슈머 프로세스 진단

```bash
curl -s -o /dev/null -w 'consumer actuator: %{http_code}\n' http://localhost:8082/actuator/health
curl -s -o /dev/null -w 'consumer prometheus: %{http_code}\n' http://localhost:8082/actuator/prometheus
```
**둘 다 200이 정상이다.** `micrometer-registry-prometheus` 의존성과 `prometheus.yml`의 8082 job이 추가되어 컨슈머 지표를 PromQL로 볼 수 있다. 404가 나오면 의존성이 빠진 것이다.

> 단 **커스텀 메트릭은 여전히 0건**이다 — 적재 건수·DLT 유입·발행 성공/실패는 아래 "관측이 비어 있는 것"을 참고하라. 여기서 보이는 건 Spring Kafka·Hikari·JVM의 기본 지표뿐이다.

```bash
LOG=<queue-consumer 로그>
grep -c "적재 불가 항목 격리"                      $LOG   # DLT 격리. 0이 정상
grep -c "제약 위반이 났지만 범인을 특정하지 못했다"  $LOG   # WARN. 전 건 적재됨 → 조치 불필요
grep -cE "rebalance|LeaveGroup|heartbeat failed"  $LOG   # 5분에 2회 이상이면 비정상
```

```bash
# 설정 함정 두 개 (빠지면 조용히 성능이 죽는다)
grep -n "rewriteBatchedStatements" queue-consumer/src/main/resources/application-*.yml   # 반드시 있어야 함
grep -nE "max-poll-records|batch_size"  queue-consumer/src/main/resources/application.yml # 둘 다 500이어야 함
```

---

## 7. DLT 내용 조사 (오프셋을 오염시키지 않는 방법)

```bash
# ⚠️ --group 을 붙이지 마라. 붙이면 오프셋이 커밋되어 나중에 못 읽는다
$KAFKA_HOME/bin/kafka-console-consumer.sh --bootstrap-server $BS \
  --topic token-lifecycle.DLT --from-beginning --max-messages 20 \
  --property print.headers=true --timeout-ms 10000
```
헤더의 `kafka_dlt-exception-message`로 원인을 본다. FK 위반(`fk_tokens_queue`)이 가장 흔하다:
```sql
-- 해당 queue_id가 실제로 없는지 확인 (Replica)
SELECT queue_id FROM queues WHERE queue_id = 'q_xxx';
```

**절대 하면 안 되는 것**
- `kafka-consumer-groups.sh --reset-offsets --to-latest` — 건너뛴 이벤트는 영영 DB에 안 들어간다. lag은 지표이지 문제가 아니다
- DLT 토픽 삭제 — 그 안의 이벤트가 DB에 없는 유일한 사본이다
- `min.insync.replicas`를 1로 낮춰 발행 실패를 회피 — `acks=all`이 무력화되어 조용히 유실된다
