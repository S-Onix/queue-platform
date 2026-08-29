# RunBook — 폴링 (대기 상태 조회)

> 대상: **엔드포인트 2개** (DECISIONS §79로 분할됨)
> ① `GET /api/v1/queues/{queueId}/status` — 큐 전광판. **permitAll + Rate Limit 없음**
> ② `GET /api/v1/queues/{queueId}/tokens/{tokenId}?seq={mySeq}` — 개인 상태. **permitAll**
> ⚠️ `ka` 파라미터는 **무시된다**(§82 F안). 받기는 하지만 분기가 없다 — **폴링이 오면 언제나** `last-active`를 갱신한다.
>    분기가 있던 시절엔 `ka`를 안 붙이는 클라이언트의 **살아 있는 대기자가 회수됐다.** 파라미터는 하위호환으로만 남았다
> 쿼리 모음: [`doc/monitoring/queries/polling.md`](../queries/polling.md)
> 카테고리 체계: [`MONITORING_DESIGN.md` 1-2 / 2-2](../MONITORING_DESIGN.md)

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

## 30초 요약

```
① GET .../status                          ← 평상시 폴링의 대부분. 30만 명 전원 동일 응답
  └ (Rate Limit 없음 — 미등록 public 경로라 RateLimitFilter를 그냥 지나간다. 의도된 것)
  └ QueueEngineService.status()
      └ readStatus() : MGET admit-watermark, pacing, seq   ★Redis 왕복 1회 (읽기)
                       세 키가 같은 {queueId} 해시태그 = 같은 슬롯이라 1왕복이다
                       seq도 watermark도 없으면 → 404 Q001 (DB로 내려가지 않는다)
  응답: { lastAdmittedSeq, pacing: [[50,2],[1000,5],[5000,10],[10000,15],[null,20]] }
        rank 계산(mySeq − lastAdmittedSeq)도 간격 선택도 **클라이언트가 한다**

② GET .../tokens/{tokenId}?seq&ka         ← rank<=0 근처 + keepalive(30~60초 1회)에만
  └ RateLimitFilter.checkPollRateLimit() : Redis EVAL token-bucket  (cap 5, refill 1.0/s)  ★1회차 왕복
  └ QueueEngineService.poll()
      └ verifyWaiting() → poll_verify.lua : ZRANGEBYSCORE(waiting, seq, seq)  ★2회차 왕복 (master 쓰기)
                                            HGET(tokens, identifier) → tokenId 대조
                                            ZADD(last-active, now, seq) ← 항상. ka와 무관(§82 F안)
      └ 검증 실패 시에만 GET admit-by-token:{tokenId}   ★3회차 (admit된 사람 / 없는 토큰만)
  응답: { ready: false }  또는  { ready: true, admitToken }
```

**분할로 무엇이 바뀌었나 (용량 산정 시 반드시 둘을 함께 더할 것)**
- **HTTP 요청 수는 오히려 는다** — 한 사람이 두 엔드포인트를 나눠 부른다. 20k~23k rps 기준으로 잡아라.
- **줄어드는 것은 master EVAL이다.** 평상시 트래픽이 `EVAL`(write·master 고정)에서 `MGET`(read)으로 바뀐다.
- HTTP 요청 수를 실제로 깎는 것은 **CDN뿐**이다(Sprint 11). WAS-local 캐시는 **일부러 안 만들었다**(§79 D1).

**이 경로에서 가장 위험한 세 가지**
1. `queue:{q}:last-active` ZSet에 **`ZREM`도 `EXPIRE`도 전 소스에 0건**이다(`poll_verify.lua`가 `ZADD`만 한다). 읽는 코드도 0건 — 즉 지금은 쓰기만 하고 아무도 안 쓰는 데이터가 무한히 쌓인다.
2. ② permitAll + Rate Limit 키가 요청자 통제값(tokenId) → 요청 1건이 Redis master EVAL 2회를 확정 유발한다.
3. **① 은 인증도 Rate Limit도 없다.** 방어는 "미지 queueId가 Redis 1왕복 안에서 404로 끝난다"는 것뿐이고, L7 flood은 **CDN·WAF 소관**이다. 앱에서 막을 수단이 없다는 것을 알고 있어라.

---

### [증상] 사용자가 폴링해도 순위가 안 줄어든다

- **먼저 의심할 것**: 순위의 분모가 된 `queue:{q}:admit-watermark`가 안 오르고 있다. 이 값은 **Tenant가 admit을 호출할 때만** 전진한다 — Platform이 스스로 올리지 않는다. 즉 Tenant가 입장을 안 시키면 순위는 정상적으로 고정이다.
- **1분 안에 확인**:
  ```bash
  Q=q_xxx
  redis-cli -c -p 7001 get "queue:{$Q}:admit-watermark"
  sleep 30
  redis-cli -c -p 7001 get "queue:{$Q}:admit-watermark"
  ```
  **두 값이 같으면 그 사이 admit이 0건이었다는 뜻이다. Platform 문제가 아니라 Tenant가 안 뽑아간 것이다.**
- **정상 범위**: watermark는 **단조 증가**한다. `admit.lua`가 현재값보다 클 때만 쓰므로 절대 감소하지 않는다.
- **원인별 분기**:
  - watermark 불변 + `zcard waiting` 증가 → Tenant가 admit을 안 부르고 있다. Tenant 쪽 확인.
  - **watermark가 감소했다 → 비정상.** `admit.lua`의 조건부 갱신이 우회됐거나 누군가 수동 `SET`을 했다. 후퇴하면 사용자 화면의 순번이 **늘어난다**.
  - 사용자마다 순번이 미세하게 다르게 보인다 → **정상.** 세션 어피니티가 없어 WAS마다 읽은 시점이 다르다. SDK가 `wm = max(wm, 받은값)`으로 clamp하므로 화면은 후퇴하지 않는다.
  - 실제보다 순번이 많이 남은 것처럼 보인다 → **정상이고 의도된 것.** watermark는 admit할 때만 전진해서 중간에 취소·만료로 빠진 사람을 못 뺀다. 방향이 항상 "생각보다 빨리 입장"이라 수용한 값이다.
- **조치**: Platform 측 조치 없음. Tenant에게 admit 호출 상태를 확인한다.
- **하면 안 되는 것**:
  - 순위를 "고쳐주려고" `waiting` ZSet에서 앞쪽 멤버를 `ZREM`하는 것. 그 사람의 대기 자격이 사라지고 DB `tokens` 행과 어긋난다. 되돌릴 수도 없다(seq는 INCR이라 재사용 불가).
  - **화면을 앞당기려고 `admit-watermark`를 수동으로 올리는 것.** 전광판만 움직이고 실제 입장은 일어나지 않는다 — 전원이 `rank<=0`이 되어 개인 엔드포인트로 몰려가고(2초 간격), 거기서 admitToken이 없어 **전원 404**를 받는다. 부하와 장애를 동시에 만든다.

---

### [증상] Redis 메모리가 계속 증가한다 / `used_memory`가 1GB에 접근한다

- **먼저 의심할 것**: `queue:{q}:last-active` ZSet. 폴링마다 `ZADD`한다(`ka`와 무관 — §82 F안).
  ⚠️ **"삭제도 만료도 없다"는 더 이상 사실이 아니다** — `inactive_expire.lua`·`waiting_expire.lua`가 회수하면서 `ZREM`한다(§82 · PR #45/#48).
  그래도 감시는 필요하다: **회수보다 유입이 빠르면** 여전히 쌓인다. seq는 INCR이라 재사용이 없어 멤버가 절대 겹치지 않는다.
- **1분 안에 확인**:
  ```bash
  redis-cli -c -p 7001 info memory | grep -E 'used_memory_human|maxmemory_human|maxmemory_policy'
  redis-cli -c -p 7001 zcard 'queue:{q_xxx}:last-active'
  redis-cli -c -p 7001 memory usage 'queue:{q_xxx}:last-active'    # 표본 추정치. 정확값 아님
  ```
  **`used_memory` / `maxmemory` 가 0.8을 넘으면 위험.** 현재 실측 설정: `maxmemory 1073741824`(1GB), `maxmemory-policy noeviction`, `appendonly yes`.
- **정상 범위**: `used_memory` ≤ 800MB (1GB의 80%). `zcard last-active` ≤ 해당 큐의 `zcard waiting` — **이 관계가 깨지면(last-active > waiting) 이미 좀비 멤버가 쌓인 것이다.**
- **원인별 분기**:
  - `zcard last-active` > `zcard waiting` → 삭제 경로 부재로 인한 누적. 확정.
  - `used_memory`가 크지만 `last-active`가 작다 → `waiting`/`tokens`가 큰 것. 이 셋도 삭제 경로가 없다(admit·TTL 미구현).
  - `rl:poll:token:*` 키가 수백만 개 → Rate Limit 키 폭증. [`runbook/rate-limit.md`](rate-limit.md) 로.
- **조치** — **`noeviction`이라 상한에 닿는 순간 모든 쓰기가 `OOM command not allowed` 로 실패한다. enqueue도 폴링도 동시에 죽는다.** 상한에 닿기 전에:
  1. 종료된 이벤트의 `last-active`를 지운다 (**진행 중 큐에는 쓰지 마라**):
     ```bash
     # last-active를 읽는 코드가 0건이므로 이 키만 지우는 것은 현재 기능에 영향이 없다
     redis-cli -c -p 7001 del 'queue:{q_종료된큐}:last-active'
     ```
     되돌리기: 없다. 다만 아무도 읽지 않는 데이터라 손실이 없다. **inactive_ttl 배치(미구현)가 들어온 뒤에는 이 판단이 뒤집힌다** — 그때는 지우면 안 된다.
  2. 그래도 부족하면 종료된 큐의 `waiting`/`tokens`도 지운다. `seq`는 남긴다(순번 재사용 방지). → [`runbook/enqueue.md`](enqueue.md) 의 FULL 항목 참조.
  3. 임시로 `maxmemory`를 올린다 (물리 메모리 여유가 있을 때만):
     ```bash
     redis-cli -c -p 7001 config set maxmemory 2gb     # 되돌리기: config set maxmemory 1gb
     # ⚠️ CONFIG REWRITE 하지 않으면 재기동 시 원복된다 (이 경우엔 그게 안전장치다)
     ```
- **하면 안 되는 것**:
  - `maxmemory-policy`를 `allkeys-lru` 등으로 바꾸는 것. **대기열 ZSet이 evict 대상이 되어 사용자의 순번이 조용히 사라진다.** `noeviction`은 의도된 선택이다 — 쓰기 실패가 데이터 증발보다 낫다.
  - `KEYS queue:*` 로 얼마나 쌓였는지 세는 것. 단일 스레드를 막는다. `SCAN`은 쓰되 `COUNT`를 100 이하로 하고 replica(6380)에서 한다.
  - `FLUSHALL` — Rate Limit·캐시·대기열이 전부 날아간다. 복구 경로 미구현.

---

### [증상] Redis master CPU가 폴링 트래픽에 비례해 100%로 간다

- **먼저 의심할 것**: 요청 1건 = **Redis master 왕복 2회 확정**. ① Rate Limit `token-bucket.lua` EVAL, ② `poll_verify.lua` EVAL(`ZRANGEBYSCORE` 포함). 둘 다 **쓰기라서 replica로 못 뺀다**(token-bucket은 HMSET+EXPIRE, poll_verify는 **항상** ZADD — §82 F안으로 ka 분기가 사라졌다).
- **1분 안에 확인**:
  ```bash
  redis-cli -c -p 7001 info commandstats | grep -E 'cmdstat_(evalsha|eval|zrangebyscore|zadd|hget)'
  redis-cli -c -p 7001 info stats | grep instantaneous_ops_per_sec
  ```
  **`evalsha`의 `calls` 증가율이 폴링 RPS의 2배면 정상 구조다.** `usec_per_call`이 급증했다면 ZSet이 커진 것.
- **정상 범위**: **기준선 수집 필요.** `poll_verify.lua`는 `ZRANGEBYSCORE`가 O(log N + M), M=1이라 30만에서도 이론상 가볍지만 **실측이 없다**. 평상시 폴링 부하 3일치의 `usec_per_call` p95를 재고 그 3배를 경고선으로 시작할 것.
- **원인별 분기**:
  - `evalsha calls` ≈ 2 × HTTP 폴링 수 → 구조상 정상. 트래픽이 많은 것.
  - `evalsha calls` >> 2 × 개인 엔드포인트 호출 수 → 클라이언트가 `pacing` 표를 무시하고 있다. `/status` 응답의 `pacing`(기본 2/5/10/15/20초 + 클라이언트 지터)을 SDK가 지키는지 확인.
  - `evalsha`는 그대로인데 **`mget` calls가 폭증** → `/status` 쪽이다. 인증도 Rate Limit도 없는 경로라 앱에서 막을 수단이 없다. CDN·WAF로 간다.
  - CPU는 높은데 ops/sec은 낮다 → 개별 명령이 무겁다. `slowlog get 10`.
  - 특정 큐만 → 핫키. 키가 전부 `{queueId}` 해시태그라 **Cluster여도 슬롯 하나에 집중된다.** 분산되지 않는다.
- **조치**:
  - 즉시 수단은 폴링 Rate Limit을 조이는 것뿐인데 **capacity/refill이 코드에 하드코딩**돼 있다(`RateLimitFilter.java:110,119`). 런타임 변경 불가.
  - 큐 단위로 트래픽을 끊으려면 해당 큐를 정지시켜 신규 유입을 막는다(기존 폴링은 계속된다).
  - **`pacing` 키로 전원 폴링 간격을 즉시 늘린다.** 재배포 없이 부하를 절반으로 깎는 유일한 런타임 레버다:
    ```bash
    # 전원 간격 2배. 되돌리기: DEL 하면 코드 기본 사다리로 돌아간다
    redis-cli -c -p 7001 set 'queue:{q_xxx}:pacing' '50:4,1000:10,5000:20,10000:30,*:40'
    redis-cli -c -p 7001 del 'queue:{q_xxx}:pacing'
    ```
    형식은 `상한:간격초` CSV이고 **마지막은 반드시 `*:초`**(그 이상 전부)다. 형식이 깨지면 조용히
    기본 사다리로 돌아가므로(로그 없음 — 15만/s 경로라 못 남긴다), 바꾼 뒤 `/status` 응답을 직접 확인할 것.
  - `/status`(`MGET`)는 읽기라 replica로 뺄 여지가 있으나 `ReadFrom` 설정이 필요하다 — 후속 과제.
- **하면 안 되는 것**:
  - Redis를 재기동해 CPU를 "리셋"하는 것. 대기열이 통째로 사라진다.
  - `DEBUG SLEEP`, `KEYS`, 큰 `SCAN COUNT` — 단일 스레드를 더 막는다.
  - master에 진단용 조회를 붙이는 것. 조회는 replica 6380/6381.

---

### [증상] 폴링이 404(TK001 TOKEN_NOT_FOUND)를 반환한다

- **먼저 의심할 것**: `poll_verify.lua`가 0을 반환하고 **`admit-by-token`도 비어 있는** 경우다. 검증 실패 경로는 셋(① seq에 해당하는 멤버 없음 ② tokens Hash에 항목 없음 ③ 저장된 tokenId와 불일치)인데, **셋 중 어느 것이든 admit된 사람일 수 있어** 곧바로 404를 주지 않는다 — `admit-by-token:{tokenId}`가 있으면 `ready:true`다.
- ⚠️ **404가 뭉개는 두 상황이 있다 (미해결, §79 404 계약).** 진짜 소멸(취소·만료)과 **admitToken TTL 만료 후 WAITING 복귀 대기 중**(복귀 배치 반영 전)이 둘 다 `TK001`이다. 후자는 백오프 후 재시도해야 하는데 SDK는 404를 종료 신호로 받는다. **Tenant가 admitToken을 대량으로 소비하지 못하는 사고 중에는 이 창의 404가 급증한다** — 그때의 404는 "잘못된 요청"이 아니다.
- **1분 안에 확인**:
  ```bash
  Q=q_xxx; SEQ=12345; TOK=tok_019...
  redis-cli -c -p 7001 zrangebyscore "queue:{$Q}:waiting" $SEQ $SEQ           # ① 비면 멤버 없음
  ID=$(redis-cli -c -p 7001 zrangebyscore "queue:{$Q}:waiting" $SEQ $SEQ)
  redis-cli -c -p 7001 hget "queue:{$Q}:tokens" "$ID"                          # ② 비면 Hash 없음 / ③ 값이 "tokenId|issuedAt"
  redis-cli -c -p 7001 get "queue:{$Q}:admit-by-token:$TOK"                     # 값이 있으면 404가 아니라 ready:true여야 한다
  redis-cli -c -p 7001 zscore "queue:{$Q}:admitted" "$SEQ|$ID"                  # 있는데 위가 비었다 = 복귀 대기 중(위 ⚠️)
  ```
  **② 결과의 `|` 앞부분이 요청의 tokenId와 다르면 ③ 불일치(= 남의 seq를 조회한 것). 정상 거절이다.**
- **정상 범위**: 404 비율 < 전체 폴링의 1%. **정확한 임계값은 기준선 수집 필요** — 정상 이탈(브라우저 새로고침 후 옛 seq 재사용)이 얼마나 되는지 데이터가 없다. **3일치 404 비율을 먼저 재라.**
- **원인별 분기**:
  - 특정 큐 전체가 404 → 그 큐의 Redis 키가 사라졌다. `EXISTS queue:{q}:waiting` 확인. 0이면 Redis 유실 사건이다(복구 경로 미구현).
  - 산발적 404 → 정상. 잘못된 seq/tokenId 조합.
  - 404가 급증 + `zcard waiting` = 0 + `get seq` 가 살아 있음 → **RDB 롤백/failover로 ZSet만 옛 상태로 돌아간 것**이 아니라 키가 지워진 것. `redisSeq < dbMaxSeq` 비교로 확정([`queries/kafka-persistence.md`](../queries/kafka-persistence.md) §4).
- **조치**: 큐 전체 404면 Redis 유실. DB `tokens`에서 해당 큐의 WAITING 행으로 ZSet·Hash를 재구성해야 하는데 **복구 도구가 미구현**이다. 수동 재구성 절차는 [`queries/polling.md` §5](../queries/polling.md) 참조.
- **하면 안 되는 것**: 404를 줄이려고 `poll_verify.lua`의 검증을 우회하는 것. tokenId 대조가 빠지면 seq(INCR이라 추측 자명)만으로 남의 대기 항목에 keepalive를 걸 수 있다.

---

### [증상] `last-active` 값이 미래/과거로 튄다 (또는 WAS별로 다르다)

- **먼저 의심할 것**: WAS 시계 오차. score의 출처가 `QueueEngineService`가 주입한 `clock.millis()`(WAS 로컬 시계)이고, Lua는 `TIME`을 쓰지 않는다(`poll_verify.lua:11`).
- **1분 안에 확인**:
  ```bash
  timedatectl show -p NTPSynchronized -p TimeUSec       # NTPSynchronized=yes 여야 정상
  # Redis 서버 시각과 WAS 시각 차이
  echo "redis=$(redis-cli -c -p 7001 time | head -1)000  was=$(date +%s%3N)"
  ```
  **차이가 1,000ms(1초)를 넘으면 조사. 5,000ms를 넘으면 즉시 조치.**
- **정상 범위**: WAS 간 시계 오차 < 1초. NTP 동기 상태 `yes`.
- **원인별 분기**:
  - `NTPSynchronized=no` → NTP 미동작. 확정.
  - WSL2 환경 → **VM suspend 후 시계 점프**가 흔하다. 100만 건 테스트 1차 실패(1,410건)의 원인이 이것이었다(DECISIONS §73). `/proc/uptime`이 벽시계 경과보다 크게 적으면 suspend가 있었던 것.
- **조치**: NTP 재동기.
  ```bash
  sudo systemctl restart systemd-timesyncd   # 되돌리기 불필요
  ```
  **현재는 `last-active`를 읽는 코드가 없어 실질 영향이 없다.** inactive_ttl 배치(미구현)가 들어오면 시계 오차가 곧 조기 EXPIRE(대기자 강제 이탈)로 직결된다 — 그 전에 NTP를 확실히 해둘 것.
- **하면 안 되는 것**: `date -s`로 수동 보정. 시계가 뒤로 점프하면 `last-active` score가 역행해 판정이 뒤집힌다. NTP에 맡겨라.

---

## 이 기능에서 관측이 비어 있는 것 (지표 추가 필요)

| 관측 대상 | 현재 상태 |
|---|---|
| 폴링 요청 수·지연 | `http_server_requests_seconds{uri="/api/v1/queues/{queueId}/tokens/{tokenId}"}` — **기본 메트릭으로 관측 가능** |
| 404(TK001) 사유별 분류 | **미노출.** Lua가 0/1만 반환해 세 경로를 구분할 수 없다 |
| `/status` 요청 수·지연 | `http_server_requests_seconds{uri="/api/v1/queues/{queueId}/status"}` — **기본 메트릭으로 관측 가능** |
| `/status` 404(미지 queueId) 비율 | 위 메트릭의 `status="404"` 로 관측 가능. **인증 없는 경로라 flood 탐지의 유일한 앱 측 신호다** |
| `pacing` 오버라이드 적용 여부 | **미노출.** 형식 오류가 조용히 기본값으로 떨어지므로(로그 없음) `/status` 응답을 직접 봐야 한다 |
| 404(TK001)의 두 상황 구분 | **불가.** 진짜 소멸과 WAITING 복귀 대기가 같은 코드다 (§79 404 계약 — ErrorCode 미분리) |
| `last-active` 크기 | **미노출.** ZCARD 직접 조회만 |
| keepalive 비율 | **의미 없음** — `ka` 분기가 사라져 모든 폴링이 keepalive다(§82 F안) |
| Redis 메모리 (PromQL) | **관측 가능** — `redis_memory_used_bytes` / `redis_memory_max_bytes`. redis_exporter는 멀티타깃 1프로세스로 설치돼 있고 job은 `redis`(3) · `redis-cluster`(16) · `redis-sentinel`(3) 셋이다 (2026-08-28 실측: 타깃 22개 전부 up, 시계열 19개) |
