# RunBook — Rate Limit

> 대상: `RateLimitFilter` (queue-api). 필터 순서: `JwtAuthenticationFilter` → `ApiKeyAuthenticationFilter` → **`RateLimitFilter`**
> 쿼리 모음: [`doc/monitoring/queries/rate-limit.md`](../queries/rate-limit.md)
> 카테고리 체계: [`MONITORING_DESIGN.md` §보안 카테고리](../MONITORING_DESIGN.md)

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

## 30초 요약 — 세 갈래

| 경로 | 알고리즘 | 키 | 한도 | 코드 |
|---|---|---|---|---|
| 폴링 `GET /queues/*/tokens/*` | Token Bucket | `rl:poll:token:{tokenId}` | **cap 5, refill 1.0/s** (하드코딩) | `RateLimitFilter.java:121-134` |
| 인증 후 (X-API-Key / JWT) | Token Bucket | `rl:tenant:{tenantId}` | **상수** `TENANT_CAPACITY`/`TENANT_REFILL_PER_SEC` (전 테넌트 동일, §88) | `RateLimitFilter` |
| 인증 전 (signup/login/refresh) | Fixed Window | `rl:{action}:ip{ip}` | SIGNUP 5/분, LOGIN 10/분, REFRESH 30/분 | `:185-219` |
| `/actuator/**` | 적용 제외 | — | — | `:139-142` |

거부 시 HTTP **429** + `Retry-After` + 본문 `{"error":"RL001",...}`.
Redis 키 TTL: token-bucket은 **고정값이 아니라 한도에서 계산**된다(`token-bucket.lua`).
`max(60, min(3600, ceil(capacity / refillRate) + 60))` — 버킷이 full refill되면 그 상태는 키가
없을 때와 결과가 같으므로 그 시간만 버티면 된다. **폴링 65s, 테넌트 120s**(실측).
> ⚠️ 옛 판은 "Plan 4종 전부 120s"였다 — 등급제는 §88에서 폐기됐고 한도는 상수 하나다.
상·하한은 호출자 인자 방어용이다(refill 0 → 3600, refill 음수 → 60).
fixed-window는 `윈도우+1s`(`fixed-window.lua:33`).

---

### [증상] 신규 가입/로그인이 전 세계에서 429가 된다 (Sprint 11 ALB 도입 직후)

- **먼저 의심할 것**: `server.forward-headers-strategy: native` 누락. `RateLimitFilter.java:198`은 `request.getRemoteAddr()`만 쓰므로, LB 뒤에서는 **모든 요청의 IP가 LB 사설 IP 하나**가 된다 → `rl:signup:ip10.0.1.7` 단일 버킷 → **전 세계 신규 가입이 분당 5건.** 역방향 self-DoS.
- **1분 안에 확인**:
  ```bash
  # ① 설정 존재 여부
  grep -rn "forward-headers-strategy" queue-api/src/main/resources/application-prod.yml   # 없으면 확정
  # ② Redis에 버킷이 몇 개인지 (개수가 아니라 '몇 개뿐인지'가 핵심)
  redis-cli -c -p 7001 --scan --pattern 'rl:signup:ip*' -i 0.01 | head -20
  ```
  **`rl:signup:ip*` 키가 사실상 1~2개뿐인데 429가 쏟아지면 확정.** 정상이라면 요청 IP 수만큼 키가 있어야 한다.
- **정상 범위**: `rl:{action}:ip*` 고유 키 수 ≈ 해당 윈도우의 고유 클라이언트 IP 수. 1개 = 비정상.
- **원인별 분기**:
  - 키가 1개, 값이 LB 사설 IP(10.x/172.16-31.x/192.168.x) → forward-headers 누락 확정.
  - 키가 많은데 특정 키만 429 → 진짜 그 IP의 남용. 정상 동작.
  - 키가 많고 429도 산발적 → 정상.
- **조치**:
  1. 즉시: 해당 버킷 키를 지워 창을 리셋한다(윈도우 만료까지 최대 60초 기다리는 것과 같지만 즉효).
     ```bash
     redis-cli -c -p 7001 --scan --pattern 'rl:signup:ip10.0.1.7:*' -i 0.01 | xargs -r redis-cli -c -p 7001 del
     ```
     되돌리기: 불필요(키가 다시 생성된다). **다만 이건 60초짜리 임시 조치다. 근본 해결은 설정 추가+재배포.**
  2. 근본: `application-prod.yml`에 `server.forward-headers-strategy: native` 추가 후 재배포. (**앱 코드 수정 아님** — 필터 주석 `:196-197`이 명시하는 의도된 방식이다.)
- **하면 안 되는 것**:
  - 한도를 5 → 5000으로 올려 급한 불을 끄는 것. 버킷이 하나라는 사실은 그대로라 brute-force 방어가 통째로 사라진다.
  - 애플리케이션 코드에서 `X-Forwarded-For`를 직접 읽도록 고치는 것. 검증 없는 XFF는 헤더 한 줄로 우회된다(과거 이 구현이었고 `getRemoteAddr()`로 되돌린 이력이 있다).

---

### [증상] 폴링이 429(RL001)를 계속 뱉는다

- **먼저 의심할 것**: **같은 tokenId를 여러 곳이 동시에 폴링하고 있다**(탭 3개 이상, 또는 SDK가
  `/status`의 `pacing` 표를 무시하고 자체 주기로 도는 것).
  refill 1.0/s에 `pacing` 최저 구간 2초(`PacingTier.DEFAULT`, rank ≤ 50)라 정상 클라이언트
  하나는 **초당 0.5건을 쓰고 1.0건을 회복**한다 — 버킷이 cap 5에 붙어 있는 게 정상이다.
  손익분기는 **초당 1.0건 = 같은 토큰으로 탭 2개**이고, 탭 3개(1.5/s)면 full에서 10초 뒤 429다.

  > 이전에는 refill 0.5/s여서 최소 폴링 간격과 소비·회복이 정확히 같았다(여유 0). 탭 하나가
  > 이미 경계선이라 재시도 한 번이 곧 429였다. 그 구조적 문제는 refill 상향 + 응답 지터로
  > 해소됐다 — **이 증상이 다시 보이면 한도 설계가 아니라 클라이언트 쪽을 먼저 의심하라.**
- **1분 안에 확인**:
  ```bash
  TOK=tok_019...
  redis-cli -c -p 7001 hgetall "rl:poll:token:$TOK"   # tokens(남은 토큰), lastRefillMillis
  redis-cli -c -p 7001 ttl  "rl:poll:token:$TOK"      # 폴링 키의 TTL 상한은 65. 65 근처면 최근 갱신됨
  ```
  **`tokens` 값이 1.0 미만이면 그 토큰은 지금 거부 상태.** 0에 붙어 있으면 지속 초과다.
- **정상 범위**: `tokens` ≥ 1.0, 정상 폴링 중이면 대개 cap 5에 붙어 있다. 429 비율은 **기준선 수집 필요** — **SDK가 붙은 실사용 3일치 429 비율을 재고, 그 값이 1%를 넘으면 한도 설계 자체를 재검토**해야 한다.
- **원인별 분기**:
  - 다수 tokenId가 동시에 429 → 여유가 0이던 옛 구조와 달리 지금은 **한도 설계가 아닌 다른 원인**을 먼저 봐라. SDK 배포판이 `pacing`을 안 지키거나, 재시도 루프가 백오프 없이 도는 경우다.
  - **`queue:{q}:pacing` 오버라이드를 방금 바꿨는가?** 최저 구간을 2초 밑으로 내리면 그 큐의 대기자 전원이 이 한도를 때린다. `redis-cli get "queue:{q}:pacing"` 으로 확인하고 되돌린다(`DEL` = 코드 기본 사다리).
  - 위와 함께 **그 큐가 admit 정체 중**이라면 → 전원이 `rank<=0`이 되어 개인 엔드포인트로 몰린 것이다. Tenant가 admitToken을 소비하지 못하면 §79 분할의 이득이 통째로 사라지고 트래픽이 전량 `EVAL`로 되돌아간다. 근본 원인은 Rate Limit이 아니라 admit 정체다.
  - 소수 tokenId만 429 → 그 클라이언트가 `pacing`을 무시하고 있다. SDK/브라우저 확인. 탭 3개 이상도 여기 해당한다.
  - 429인데 `rl:poll:token:*` 키가 없다 → 폴링 경로가 아니라 다른 경로의 429다. 응답 본문 `error` 확인(`RL001` vs `Q005` 정원 초과).
- **조치**:
  - **한도 자체는 런타임 조정 불가.** capacity 5 / refill 1.0은 `RateLimitFilter`(`POLL_CAPACITY`, `POLL_REFILL_PER_SEC`)에 하드코딩돼 있다.
  - **대신 반대편을 늘릴 수 있다** — `queue:{q}:pacing`으로 폴링 간격을 늘리면 소비 속도가 줄어 429가 사라진다. 재배포가 필요 없는 유일한 레버다:
    ```bash
    redis-cli -c -p 7001 set 'queue:{q_xxx}:pacing' '50:4,1000:10,5000:20,10000:30,*:40'
    # 되돌리기: redis-cli -c -p 7001 del 'queue:{q_xxx}:pacing'
    ```
    ⚠️ 형식이 깨지면 **조용히** 기본 사다리로 돌아간다(핫패스라 로그가 없다). `/status` 응답으로 반영을 확인할 것.
  - 개별 사용자 구제가 필요하면 그 버킷만 지운다(즉시 5개 토큰 회복):
    ```bash
    redis-cli -c -p 7001 del "rl:poll:token:tok_019..."
    ```
    되돌리기: 불필요(다음 요청에서 재생성).
  - 광범위하면 사건으로 기록하고 상수 재조정(재배포)으로 넘긴다.
- **하면 안 되는 것**: `rl:poll:token:*` 전체를 `KEYS`로 뽑아 일괄 삭제하는 것. `KEYS`는 단일 스레드를 막고, 키가 수십만 개면 전면 장애가 된다. 꼭 필요하면 `redis-cli --scan --pattern 'rl:poll:token:*' -i 0.01`.

---

### [증상] Rate Limit이 아예 안 걸린다 (429가 0건)

- **먼저 의심할 것**: `RateLimitFilter.java:156-160` — Tenant 조회 실패 시 **`return true`로 통과시킨다.** 인증은 됐는데 Tenant 행이 없으면 그 테넌트는 무제한이 된다.
- **1분 안에 확인**:
  ```bash
  grep -c "Tenant not found for rate limit" <api-log>     # 0이 아니면 확정
  redis-cli -c -p 7001 --scan --pattern 'rl:tenant:*' -i 0.01 | wc -l
  ```
  **`Tenant not found` 로그가 있는데 429가 0이면 확정.**
  `rl:tenant:*` 키 수는 이 판정에 쓰지 마라 — TTL이 120s라 **키 수가 활성 테넌트 수보다 적은 것이 정상**이다.
- **정상 범위**: `Tenant not found for rate limit` 로그 0건. `rl:tenant:*` 키 수 = **최근 2분 내** 요청한 테넌트 수(TTL 120s).

  > ⚠️ **관측 창이 1시간 → 2분으로 줄었다.** TTL이 한도에서 계산되도록 바뀌면서 생긴 대가다.
  > 이 키 수는 이제 "오늘 활동한 테넌트"가 아니라 **"지금 트래픽이 흐르는 테넌트"**를 뜻한다.
  > 장기 활성 테넌트 수가 필요하면 Redis 키 개수가 아니라 접근 로그·DB를 봐라.
- **원인별 분기**:
  - `Tenant not found` 로그 있음 → 데이터 정합성 문제. `api_keys.tenant_id`가 가리키는 `tenants` 행이 없다.
  - 로그 없음 + `rl:tenant:*` 키도 정상 → 한도에 안 닿는 것뿐. 정상.
  - 로그 없음 + `rl:tenant:*` 키 0개 → 필터가 아예 안 타고 있다. `/actuator/` 로 시작하는 경로가 아닌지, Security 필터 체인 순서가 바뀌지 않았는지 확인.
  - 429는 나는데 메트릭에 안 보인다 → ✅ **`uri` 라벨은 정상으로 붙는다**(2026-08-28 실측, 위 추정은 틀렸다). `uri="/api/v1/tenants/login"` 처럼 경로별로 집계되므로 **`uri`로 분해해서 세라.** 안 보이면 라벨이 아니라 다른 원인이다.
- **조치**: 고아 API Key를 찾아 무효화한다.
  ```sql
  -- Replica(3307)에서 먼저 조사
  SELECT k.id, k.tenant_id FROM api_keys k
  LEFT JOIN tenants t ON t.id = k.tenant_id
  WHERE t.id IS NULL;
  -- Master(3306)에서 조치. 되돌리기: status 원복
  UPDATE api_keys SET status = <REVOKED> WHERE id = <id>;
  ```
- **하면 안 되는 것**: `tenants`에 더미 행을 만들어 로그를 없애는 것. 원인(고아 키)이 남고 과금·소유권 검증이 어긋난다.

---

### [증상] 랜덤 tokenId 폴링 공격을 받고 있다 (permitAll 자원 증폭)

- **먼저 의심할 것**: 폴링 Rate Limit 키가 **공격자가 정하는 값**이다(`RateLimitFilter.java:125` — URI 마지막 세그먼트). 매 요청 다른 tokenId를 쓰면 버킷이 매번 새로 만들어져 **한도에 영원히 안 걸린다.** 그런데 요청 1건마다 Redis master EVAL 2회(token-bucket + poll_verify의 ZRANGEBYSCORE)는 확정 실행된다.
- **1분 안에 확인**:
  ```bash
  # ① 폴링 404율 (랜덤 tokenId는 거의 전부 404)
  curl -s 'http://localhost:9090/api/v1/query?query=sum(rate(http_server_requests_seconds_count{uri="/api/v1/queues/{queueId}/tokens/{tokenId}",status="404"}[1m]))/sum(rate(http_server_requests_seconds_count{uri="/api/v1/queues/{queueId}/tokens/{tokenId}"}[1m]))' | jq -r '.data.result[0].value[1]'
  # ② rl:poll:token:* 키 개수 증가율 (정상이면 활성 대기자 수에 수렴)
  redis-cli -c -p 7001 info keyspace
  ```
  **404 비율이 0.5(50%)를 넘고 `rl:poll:token:*` 키 수가 활성 대기자 수를 크게 초과하면 공격 의심.**
- **정상 범위**: 폴링 404 비율 < 0.05. `rl:poll:token:*` 키 수 ≈ (**최근 65초** 폴링한 고유 토큰 수). **정확한 임계값은 기준선 수집 필요 — 실사용 3일치 404 비율을 먼저 재라.**
- **원인별 분기**:
  - 404 비율 높음 + 소수 IP 집중 → 공격. 그 IP를 차단.
  - 404 비율 높음 + IP 분산 → 분산 공격이거나 SDK 버그(옛 tokenId 재사용).
  - 404 낮은데 Redis CPU만 높음 → 공격이 아니라 정상 트래픽 과다. [`runbook/polling.md`](polling.md).
- **조치**: **애플리케이션에는 IP 기반 차단 수단이 없다**(폴링 경로는 IP를 아예 보지 않는다). 상위 계층에서 막는다.
  ```bash
  # 임시: 방화벽 (되돌리기: -D 로 같은 규칙 삭제)
  sudo iptables -A INPUT -s <공격IP> -j DROP
  sudo iptables -D INPUT -s <공격IP> -j DROP
  ```
  프로덕션에서는 ALB/WAF 레벨에서 rate limit + IP 차단. **애플리케이션 Rate Limit으로는 구조적으로 못 막는다** — 키가 공격자 통제값이기 때문이다.
- **하면 안 되는 것**:
  - 폴링 엔드포인트에 인증을 급하게 붙이는 것(운영 중 변경). 정상 사용자 전원이 즉시 끊긴다.
  - `rl:poll:token:*` 를 `KEYS`로 세는 것. 키 수백만 개면 그 명령 하나로 Redis가 멈춘다.

---

### [증상] Redis에 `rl:*` 키가 수백만 개 쌓였다

- **먼저 의심할 것**: TTL은 정상적으로 걸려 있다(token-bucket **폴링 65s / 테넌트 120s**, fixed-window 윈도우+1s). 개수가 많다면 **실제로 그만큼의 고유 키가 만들어지고 있다**는 뜻 — 대부분 `rl:poll:token:{tokenId}`다(토큰마다 1개).
- **1분 안에 확인**:
  ```bash
  redis-cli -c -p 7001 info keyspace     # db0 keys=N, expires=M
  # TTL이 없는 rl 키가 있는지 표본 확인 (전수 스캔 금지)
  redis-cli -c -p 7001 --scan --pattern 'rl:*' -i 0.01 | head -50 | \
    while read k; do echo "$(redis-cli -c -p 7001 ttl "$k") $k"; done | sort -n | head
  ```
  **TTL이 `-1`(무기한)인 `rl:` 키가 하나라도 나오면 이상.** 정상은 전부 양수다.
- **정상 범위**: `rl:poll:token:*` 수 ≤ **최근 65초** 폴링한 고유 토큰 수. `expires` / `keys` 비율이 `rl:` 영역에서 1.0.
- **원인별 분기**:
  - TTL 전부 양수 + 개수 많음 → 정상 동작. 트래픽이 많은 것. **65초** 뒤 자연 감소.
  - TTL `-1` 존재 → `token-bucket.lua`의 `EXPIRE`가 안 걸린 경우. 스크립트 버전 확인(`SCRIPT EXISTS`).
    옛 스크립트는 `refillRate=0`에서 `EXPIRE` 인자 오류로 죽으면서 `HMSET`만 커밋돼 무기한 키를 남겼다.
    지금은 상·하한 클램프로 그 경로가 없다 — `-1`이 보이면 **구버전 스크립트가 캐시에 남아 있는 것**을 의심하라.
  - `rl:` 키가 메모리의 상당 부분을 차지 → [`runbook/polling.md`](polling.md) 의 메모리 항목과 함께 판단. **`noeviction`이라 상한에 닿으면 Rate Limit 쓰기도 실패하고, 실패하면 `redisTemplate.execute`가 예외를 던져 요청이 500이 된다.**
- **조치**: TTL이 정상이면 조치 불필요. 메모리 압박이면 만료가 빠른 것부터 자연 감소를 기다리고, 급하면 패턴 삭제:
  ```bash
  # ⚠️ --scan 을 쓸 것. KEYS 금지. COUNT는 100 이하
  redis-cli -c -p 7001 --scan --pattern 'rl:poll:token:*' -i 0.01 | xargs -r -n 500 redis-cli -c -p 7001 del
  ```
  되돌리기: 불필요(전원 한도가 초기화되어 잠시 관대해질 뿐).
- **하면 안 되는 것**:
  - `FLUSHDB`/`FLUSHALL` — 같은 DB에 대기열 ZSet과 API Key 캐시가 있다. 대기열은 복구 경로가 없다.
  - `KEYS rl:*` — 수백만 키를 한 번에 반환하며 그동안 Redis 전체가 멈춘다.

---

## 이 기능에서 관측이 비어 있는 것 (지표 추가 필요)

| 관측 대상 | 현재 상태 |
|---|---|
| 429 발생 수 (경로별) | ✅ **관측 가능.** `http_server_requests_seconds_count{status="429"}` 에 `uri` 라벨이 정상으로 붙는다(2026-08-28 실측) |
| 429 발생 수 (테넌트별/IP별) | **미노출.** 테넌트·IP 라벨이 없고 초과 로그가 `log.debug`라 **prod(INFO)에선 흔적이 안 남는다** |
| 429 사유 구분 (RL001 vs Q005) | **불가.** 둘 다 HTTP 429. 응답 본문으로만 구분 |
| Tenant별 Rate Limit 소진율 | **미노출** |
| `Tenant not found for rate limit` 발생 | 로그만(WARN). 메트릭 없음 |
| Rate Limit Redis EVAL 지연 | **미노출** |
| `MONITORING_DESIGN.md` §보안 카테고리 메트릭 | 전부 **미구현** (설계 단계) |
