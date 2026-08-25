# 조회 쿼리 — Rate Limit

> RunBook: [`doc/monitoring/runbook/rate-limit.md`](../runbook/rate-limit.md)

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
export DB_PASSWORD=queueapp1234
alias MYR='mysql -h127.0.0.1 -P3307 -uqueueapp -p"$DB_PASSWORD" queue_platform -t'
alias MYW='mysql -h127.0.0.1 -P3306 -uqueueapp -p"$DB_PASSWORD" queue_platform -t'
alias RR='redis-cli -c -p 7001'    # 조회
alias RW='redis-cli -c -p 7001'    # 쓰기
```

**위험 명령 금지**: `KEYS rl:*` (키가 수백만 개면 그 한 줄로 Redis가 멈춘다) · `FLUSHDB`/`FLUSHALL` (같은 DB에 대기열 ZSet이 있다) · `--scan` 결과를 파이프 없이 통째로 받는 것(수백만 줄).

### 키 형식 요약

| 경로 | 키 | 한도 | TTL |
|---|---|---|---|
| 폴링 | `rl:poll:token:{tokenId}` (Hash) | cap 5, refill 1.0/s **하드코딩** | 65s |
| 인증 후 | `rl:tenant:{tenantId}` (Hash) | Tenant `Plan` | 120s (4종 동일) |
| signup | `rl:signup:ip{ip}:{윈도우번호}` (String) | 5 / 60s | 윈도우+1s |
| login | `rl:login:ip{ip}:{윈도우번호}` | 10 / 60s | 윈도우+1s |
| refresh | `rl:refresh:ip{ip}:{윈도우번호}` | 30 / 60s | 윈도우+1s |

> 키에 콜론이 없다: `"rl:" + action + ":ip" + ip` (`RateLimitKeys.java:13`). 패턴 매칭 시 `rl:signup:ip*` 로 쓸 것.

---

## 1. 특정 대상이 왜 429인가 (Token Bucket)

```bash
TOK=tok_019...;  TENANT=t_xxx
RR hgetall "rl:poll:token:$TOK"     # tokens(남은 토큰, 실수), lastRefillMillis
RR ttl     "rl:poll:token:$TOK"
RR hgetall "rl:tenant:$TENANT"
```

| `tokens` 값 | 판정 |
|---|---|
| ≥ 1.0 | 다음 요청 통과 |
| < 1.0 | **지금 거부 상태.** 1.0까지 회복에 `(1 - tokens) / refill` 초 소요 (폴링이면 refill 1.0/s → 1초 이내) |
| 0에 붙어 있음 | 지속 초과. 클라이언트가 한도보다 빠르게 폴링 중 |
| 키 없음 | 아직 요청이 없었거나 TTL 만료(폴링 65초 / Plan 120초 무요청) |

**TTL은 고정값이 아니다.** `max(60, min(3600, ceil(capacity / refillRate) + 60))` (`token-bucket.lua`).
버킷이 full refill되면 그 상태는 키가 없을 때와 결과가 같으므로 그 시간만 버티면 된다.
Plan 4종이 전부 `cap = refill×60` 비율이라 등급과 무관하게 120s로 같다.

**폴링 한도의 여유** (2026-08-12 이전에는 여유가 0이었다):
refill 1.0/s에 `pacing` 최저 구간 2초(`PacingTier.DEFAULT`)라 정상 클라이언트 하나는
**초당 0.5건 소비 / 1.0건 회복**으로 버킷이 cap 5에 붙어 있다. 손익분기는 **초당 1.0건 = 같은 토큰 탭 2개**.
과거에는 refill 0.5/s여서 소비와 회복이 정확히 같아, 재시도 한 번이 곧 429였다.

⚠️ **이 버킷이 세는 것은 개인 엔드포인트(`/tokens/{tokenId}`)뿐이다.** `/status`는 경로가
`isPollPath` 정규식에 안 걸려 `RateLimitFilter`를 그냥 지나간다 — **인증 0 + 제한 0**이며 §79가
알고 그렇게 뒀다(큐 단위 버킷은 30만 명이 공유해 남용자 1명이 전원을 429시킨다).

⚠️ **간격을 정하는 주체가 서버에서 클라이언트로 옮겨갔다.** 서버는 `pacing` 표만 내려주고
지터는 SDK가 건다. 즉 이 한도의 안전 여유는 **SDK가 표를 지킨다는 전제** 위에 있다.
`pacing` 최저 구간을 2초 밑으로 내리면 그 전제가 깨진다.

---

## 2. Fixed Window 현재 카운트 (signup/login/refresh)

```bash
IP=127.0.0.1; ACTION=signup; LIMIT=5
WIN=$(( $(date +%s%3N) / 60000 ))                 # 윈도우 크기 60,000ms 기준
RR get "rl:$ACTION:ip$IP:$WIN"
RR ttl "rl:$ACTION:ip$IP:$WIN"
```

| 값 | 판정 |
|---|---|
| `> LIMIT` (signup 5 / login 10 / refresh 30) | 이 윈도우에서 거부 중. `ttl`초 뒤 자동 회복 |
| `nil` | 이 윈도우 첫 요청 전 |
| `ttl` = -1 | **이상.** `fixed-window.lua:33`의 EXPIRE가 안 걸렸다 |

---

## 3. ★ LB 뒤 단일 IP 버킷 검출 (Sprint 11 self-DoS 조기 발견)

```bash
# 고유 IP 버킷 개수 — '몇 개인가'가 아니라 '1개뿐인가'를 본다
for A in signup login refresh; do
  N=$(RR --scan --pattern "rl:$A:ip*" -i 0.01 | sed 's/:[0-9]*$//' | sort -u | wc -l)
  echo "$A: 고유 IP 버킷 $N개"
done
RR --scan --pattern 'rl:signup:ip*' -i 0.01 | head -10
```

| 결과 | 판정 |
|---|---|
| 고유 IP 수 ≈ 실제 클라이언트 IP 수 | 정상 |
| **1~2개**인데 429가 쏟아짐 | **`server.forward-headers-strategy: native` 누락 확정.** 전 요청이 LB 사설 IP 하나로 집계 |
| IP가 사설 대역(10.x / 172.16–31.x / 192.168.x) | LB/프록시 IP다. 같은 원인 |

```bash
# 설정 존재 확인 (없으면 확정)
grep -rn "forward-headers-strategy" queue-api/src/main/resources/
```

**즉시 조치** (60초짜리 임시방편):
```bash
RR --scan --pattern 'rl:signup:ip10.0.1.7*' -i 0.01 | xargs -r -n 200 redis-cli -c -p 7001 del
```
**근본 조치**: `application-prod.yml`에 `server.forward-headers-strategy: native` 추가 + 재배포. (앱 코드 수정 아님.)

---

## 4. Rate Limit이 꺼진 것처럼 보일 때

```bash
LOG=<queue-api 로그>
grep -c "Tenant not found for rate limit" $LOG     # 0이 아니면 그 테넌트는 무제한
RR --scan --pattern 'rl:tenant:*' -i 0.01 | wc -l
```

| 관찰 | 판정 |
|---|---|
| `Tenant not found` 로그 있음 | `RateLimitFilter.java:156-160`이 통과시킨다. **고아 API Key 확정** |
| `rl:tenant:*` 키 0개 + 트래픽 있음 | 필터가 아예 안 탄다. 경로가 `/actuator/`로 시작하는지, 필터 체인 순서가 바뀌지 않았는지 확인 |
| `rl:tenant:*` 키 수 ≈ 최근 **2분** 요청 테넌트 수 | 정상 (TTL 120s). 장기 활성 테넌트 수 용도로는 못 쓴다 |

```sql
-- 고아 API Key 찾기 (Replica). api_keys는 작아서 조인해도 안전
SELECT k.id, k.tenant_id, k.status
FROM api_keys k LEFT JOIN tenants t ON t.id = k.tenant_id
WHERE t.id IS NULL;

-- 조치 (Master). 되돌리기: status 원복
UPDATE api_keys SET status = <REVOKED> WHERE id = <id>;
```

```sql
-- Tenant 등급별 한도 확인 (Plan enum: FREE/STARTER/PRO/ENTERPRISE)
SELECT tenant_id, plan, status FROM tenants WHERE tenant_id = 't_xxx';
```

---

## 5. 키 개수·TTL 위생 점검

```bash
RR info keyspace                                     # db0: keys=..., expires=...
# 표본 50개의 TTL만 확인. 전수 스캔 금지
RR --scan --pattern 'rl:*' -i 0.01 | head -50 | \
  while read k; do echo "$(redis-cli -c -p 7001 ttl "$k") $k"; done | sort -n | head -10
```

| 관찰 | 판정 |
|---|---|
| TTL이 전부 양수 | 정상. 개수가 많아도 폴링 65초 / Plan 120초 내 자연 감소 |
| TTL `-1`인 `rl:` 키 존재 | **이상.** Lua의 EXPIRE가 안 걸렸다 → `RW script exists <sha>` 로 스크립트 확인 |
| `rl:poll:token:*` 수 >> 활성 대기자 수 | **랜덤 tokenId 공격 의심** (키가 공격자 통제값) |

```bash
# 대량 정리가 꼭 필요할 때 — --scan 사용. KEYS 절대 금지
RR --scan --pattern 'rl:poll:token:*' -i 0.01 | xargs -r -n 500 redis-cli -c -p 7001 del
```
되돌리기 불필요(전원 한도가 초기화되어 잠시 관대해질 뿐).

---

## 6. HTTP 측 지표 (PromQL)

```promql
# 429 총량 — uri 라벨을 걸지 마라
sum(rate(http_server_requests_seconds_count{status="429"}[5m]))

# 429 비율
sum(rate(http_server_requests_seconds_count{status="429"}[5m]))
/
sum(rate(http_server_requests_seconds_count[5m]))
```

| 지표 | 정상 | 이상 |
|---|---|---|
| 429 비율 | **기준선 수집 필요.** 폴링 한도가 경계값 설계라 정상 트래픽에서도 얼마가 나오는지 데이터가 없다. **SDK 붙은 실사용 3일치를 재라. 1%를 넘으면 한도 설계 자체를 재검토** | |
| 429 절대량이 0 | Rate Limit이 꺼졌을 가능성 → §4 | |

**주의 2가지**
1. `uri` 라벨이 `UNKNOWN`으로 집계될 가능성이 높다 — 필터가 DispatcherServlet 전에 응답을 끝낸다. (**실측 확인 필요.**)
2. HTTP 429는 **Rate Limit(RL001)과 정원 초과(Q005)가 공유**한다. 메트릭만으로 구분 불가 — 응답 본문의 `error` 필드로 봐야 한다.

---

## 7. Lua 스크립트 무결성 (드물지만 치명적)

```bash
RW info memory | grep -E 'used_memory_lua|number_of_cached_scripts'
RW info commandstats | grep -E 'cmdstat_(eval|evalsha|script)'
```
`cmdstat_eval`(EVALSHA가 아닌 EVAL)이 계속 증가하면 스크립트 캐시가 매번 미스 중이다 —
Redis 재기동/failover 직후에 잠깐 나타나면 정상, 지속되면 조사 대상.

**`SCRIPT FLUSH`는 실행하지 마라.** 전 WAS가 동시에 EVAL로 재등록하며 순간 부하가 튄다.
