# 🔄 Queue Platform — 상세 흐름도

> **최종 업데이트**: 2026-08-17 (구현 대조 — Kafka 단일 토픽 §73, 슬라이스 절 폐기 §66 D2, 3-key Lua)
> **관련 문서**: DECISIONS.md §66-70·§73-§81, doc/ARCHITECTURE_ROADMAP.md, FRS v1.13

---

## Enqueue (Sprint 5-E — Bulk 단독)

> ⚠️ **2026-07-15 개정 (§70).** 개정 전 이 문서는 "임계값 1000 req/s로 일반 Lua ↔ Bulk Lua 자동 전환"
> 하이브리드 흐름이었다. 구현 과정에서 **하이브리드를 폐기**하고 모든 요청을 Bulk로 처리하도록 선회했다.
> `enqueue.lua`와 `SlidingWindowCounter`는 존재하지 않는다.

**핵심**:
- **모든 요청**이 Global Queue에 적재 → `BatchProcessor`가 주기적으로 drain → Bulk Lua 1회
- 경로가 하나뿐이라 순번 유일성 증명이 한 번으로 끝남 (하이브리드는 두 경로의 순번 일관성을 따로 증명해야 함)
- 대가: 저부하 요청도 배치 주기를 부담 (`drain-interval=20ms` → 평균 10ms). ✅ 재조정 완료(2026-08-27)

```mermaid
flowchart TD
    START(["POST /api/v1/queues/{queueId}/tokens\nX-API-Key 인증\n{ identifier }"])
    --> FILTER["① Filter Chain\nApiKeyAuthenticationFilter\nRateLimitFilter (초당 통제)"]
    --> CTRL["② QueueEngineController.enqueue()\nEnqueueRequest 파싱\nBean Validation"]
    --> SERV["③ QueueEngineService.enqueue()\nQueue 조회\nTenant 소유권 검증\nQueue 활성 상태 검증"]
    --> ENG["④ RedisQueueEngine.enqueue()\n(Producer)"]

    ENG --> OFFER["⑤ PendingEnqueue 생성\nglobalQueue.offer()\nfuture.get(30s) 대기"]

    OFFER -.->|"블로킹 대기"| COMPLETE

    SCHED["⑥ BatchProcessor (Consumer)\n@Scheduled drain-interval=20ms"]
    --> DRAIN["drain (최대 MAX_DRAIN=5000)\nqueueId별 groupBy"]
    --> CHUNK["CHUNK_SIZE=500씩 분할"]
    --> BULKLUA["⑦ enqueue_bulk.lua 실행\nKEYS[1]=queue:{queueId}:waiting\nKEYS[2]=queue:{queueId}:seq\nKEYS[3]=queue:{queueId}:tokens\n(Hash Tag — 세 키가 같은 slot 필수)\n\nfor i = 1..requestCount:\n  ZCARD ≥ maxCapacity?\n    → HGET tokens[identifier] 히트면 EXISTS (기존자는 정원과 무관)\n    → 미스면 FULL\n  INCR seq → score 발급\n  HSETNX tokens[identifier]='tokenId|issuedAt' → 신규? OK : EXISTS\n  (게이트는 이 Hash다 — waiting은 admit되면 빠진다)\n  신규면 ZADD waiting member=identifier score=seq\n  ZRANK → 순번(EXISTS인데 admit됐으면 -1)\n결과 array (입력과 동일 순서)"]
    --> COMPLETE["⑧ 위치(index)로 매칭하여\n각 future.complete(result)\n※ identifier는 중복 가능하므로 key로 쓰면 안 됨"]

    COMPLETE --> PUB["⑨ KafkaEnqueueEventPublisher.publish()\ntoken-lifecycle, key=tokenId (§73 D16)\n**동기** — 브로커 ack까지 최대 12s 대기\n실패 시 QE001(503) — 200이 안 나간다"]
    --> OK(["200 OK\n{ queueId, identifier, tokenId,\n  seq, rank(1-based), total, already }"])

    FILTER -->|"인증 실패"| E401(["401 UNAUTHORIZED"])
    FILTER -->|"Rate 초과"| E429(["429 RL_001 + Retry-After"])
    SERV -->|"큐 없음"| E404(["404 Q001 QUEUE_NOT_FOUND"])
    SERV -->|"소유권 불일치"| E403(["403 Q002 QUEUE_NOT_OWNED"])
    SERV -->|"PAUSED/DRAINING"| E503(["503 Q004 QUEUE_NOT_ACTIVE"])
```

**처리량 상한**: 인스턴스당 `MAX_DRAIN / 주기` = **250,000 req/s** (5,000건 / 20ms).
⚠️ 2026-08-27 주기 변경으로 33배가 아니라 **50배** 커졌다 — 이 상한은 사실상 도달 불가라
스로틀로서는 죽은 상수다. ~~실제로 먼저 깨지는 것은 `getMaxCapacity`의 DB 조회다(미측정)~~
→ **측정됐고 해소됐다**(2026-09-02). 큐 20개·2,000 rps에서 그 조회가 초당 1,000회라 p99가
SLO를 넘겼고(평균 73.95ms), 캐시로 40.02ms가 됐다. `queue.enqueue.capacity-cache-ttl-ms` 기본 30,000.
유입이 이를 넘으면
globalQueue가 적체되어 30s 타임아웃으로 실패한다. WAS N대면 5,000×N.

### Enqueue 결정 근거

**확정 결정** (DECISIONS §66-70, ARCHITECTURE_ROADMAP 부록 A):
- D1: identifier는 Tenant가 제공 — 단 **형식은 UUIDv7로 좁혀졌다(§78)**. 이메일·순번 ID처럼
  추측 가능한 값은 금지(enqueue가 EXISTS 시 기존 `tokenId`·`seq`를 돌려주므로 자격 증명이 샌다).
  같은 사용자·같은 큐엔 **항상 같은 UUID를 재사용**해야 `HSETNX` 중복 방지가 성립한다
- D2: ZSet 하나 (`queue:{queueId}:waiting`)
- D3: ZRANK + ZCARD (별도 counter 없음)
- D4: Java (부하 측정) + Lua (원자 처리) 분리
- D5: Lua ZRANK 중복 방지
- D6: Lua ZCARD Capacity 검증
- ~~D7: enqueue.lua + enqueue_bulk.lua 2개~~ → **`enqueue_bulk.lua` 단독** (§70)
- ~~D8: 임계값 1000 req/s, 배치 100, 간격 10ms, 타임아웃 1s~~ → **하이브리드 폐기.** 배치 상수만 유지 (§70)
  - 현재: `MAX_DRAIN=5000`, `CHUNK_SIZE=500`, **`drain-interval=20ms`**, 타임아웃 30s
  - ✅ 주기는 **재조정 완료**(20ms, 원안 10ms의 2배). `CHUNK_SIZE`·타임아웃은 원안 이탈 유지
- **D9: score = `INCR queue:{queueId}:seq`** (신설, §70) — 단조증가·유일 보장
- **D10: Hash Tag 필수** (신설, §70) — `queue/QueueKeys.java`

### Cluster 환경에서 (Sprint 8+ 반영)

> ⚠️ 2026-07-15 개정 (§70). 개정 전엔 "Lua Script 단일 key만 사용 → CROSSSLOT 이슈 없음"이었으나,
> D9(seq 키) 도입으로 **2-key가 되어 전제가 깨졌다.**

- Lettuce가 key의 CRC16으로 자동 slot 계산 → 해당 slot 담당 Master로 자동 라우팅
- `enqueue_bulk.lua`는 **키 3개**(waiting + seq + tokens)를 사용 → **해시태그로 동일 slot 강제** (D10)
  - 해시태그 없으면: slot 7911 vs 11273 → 다른 Master → `CROSSSLOT` 에러
  - 해시태그 있으면: 셋 다 같은 slot → 같은 Master → 정상
  - `poll_verify.lua`도 3키다 (waiting + tokens + last-active, §74)
- Sentinel에선 무해하므로 **선제 적용 완료** (로컬 Cluster A에서 실제 스크립트 실행 검증)
- ~~이중 라우팅 (Sprint 12+): 태그가 shard로 이동 → `queue:{shard_X}:{queueId}:waiting`~~
  → 🔴 **철회** — 태그 기준은 `queueId`로 확정됐다(§70 D10). 키 이름에 shard를 넣으면 라우팅이 순환한다(`QueueKeys.java` javadoc · `DECISIONS §67` 머리말)

### Kafka 적재 (구현 완료 — DECISIONS §73)

- Enqueue 완료 후 Kafka `token-lifecycle` 발행 (**key = `tokenId`**, 18 파티션)
- `queue-consumer` 모듈의 `TokenLifecycleConsumer`가 배치로 DB INSERT (멱등)
- Redis 다운 시 enqueue 는 **QE001(503)** 으로 실패한다 — Redis 가 순번 게이트라 DB 에만 남는 토큰이 생기지 않는다

---

## Polling (유저 → Platform 직접, Jitter 적용)

> ⚠️ **이 절은 §79(2026-08-14) 이전의 검토안이다.** 현행 계약은 아래
> "클라이언트 Polling 구조 (JS SDK)" 절과 `FRS_final.md §6.3`을 보라.
> §79에서 바뀐 것: 엔드포인트 2분할 / 서버는 rank를 계산하지 않는다 /
> `QueueSnapshotCache`(Caffeine)는 **제거됐다**(2026-08-19).
> 아래 `/rank/:identifier`·`{rank, total, estimatedWaitSeconds}` 표기는 **채택되지 않았다.**
> ⛔ 그중 ETA(`estimatedWaitSeconds`)와 그 입력인 `avgWaitingTime`은 **§81이 정식 폐기**했다.
> 그림에서 노드만 도려내지 않는 이유: 이 다이어그램은 캐시·`/rank`·서버 지터까지 통째로
> 미채택이라 한 노드를 지워도 현행이 되지 않는다. **미채택 기록으로 통째로 남긴다.**

Sprint 5-E 이후 Polling 부하 최적화:
- **Jitter 적용** (JS SDK): 요청 시점 무작위 분산 (4-6초 랜덤)
- **Caffeine 캐싱** (Sprint 9+): 서버 측 rank 조회 캐시 (TTL 1-2초)
- **SSE 미도입** (본인 확정): Polling 방식 유지, 인프라 무변경

### 부하 감소 효과

일반 Polling vs Jitter Polling:
- 100만명 × 5초 = 초당 200,000 요청 (평균 동일)
- 일반 Polling: 스파이크 t=0, 5000, 10000ms (초당 500,000 피크)
- Jitter Polling: 4000-6000ms 균등 분산 (초당 200,000 안정)
- **피크 부하 40-50% 감소**

Caffeine 캐싱 조합 시:
- Rank 조회 80% 캐시 HIT (rank 변화 낮음)
- Redis 실제 요청 20%로 감소
- 초당 40,000 요청만 Redis 도달

```mermaid
flowchart TD
    POLL(["GET /api/v1/queues/:queueId/rank/:identifier\n유저가 직접 호출\nJS SDK가 setTimeout으로 Jitter 적용\n(4-6초 랜덤 재요청)"])
    --> AUTH["① 인증 확인\nJWT 또는 조회용 임시 토큰"]
    --> CACHE["② Caffeine 로컬 캐시 확인\n(Sprint 9+ 도입)\nkey: rank:{queueId}:{identifier}\nTTL: 1-2s"]

    CACHE -->|"HIT (80%)"| RESPCACHE(["200 OK 즉시 반환\n소요: 0.0001ms"])
    CACHE -->|"MISS (20%)"| RANK["③ Rank 조회 (Redis)\nZRANK queue:{queueId}:waiting {identifier}\nZCARD queue:{queueId}:waiting\n소요: 1-2ms"]

    RANK -->|"없음"| E404(["404 TK001 TOKEN_NOT_FOUND"])
    RANK -->|"조회됨"| CACHESET["④ Caffeine에 저장\nTTL 1-2s"]
    CACHESET --> ETA["⑤ ETA 계산\navgWaitingTime × rank"]
    --> RESP(["200 OK\n{ rank, total, estimatedWaitSeconds }\nJS SDK가 다음 요청 시점\nsetTimeout(4000~6000ms)로 예약"])

    RESP -.->|"클라이언트: setTimeout(jitter)"| POLL
    RESPCACHE -.->|"동일 재요청"| POLL
```

> **미채택.** 여기 있던 Rank Query 전용 서비스 + Caffeine 캐시 다이어그램은 삭제했다 —
> `QueueSnapshotCache`는 2026-08-19에 제거됐고 `/rank` 엔드포인트는 만들지 않았다.

---

## Admit → Verify → Complete

> **2026-08-17 개정 (§80).** 개정 전 이 절은 `admit_requests` INSERT → 워커 전달 → 3단계 처리
> (Lua pop → DB WAITING 확인 → 토큰 SET) 흐름이었다. **동기 + Lua 하나**로 바뀌었고
> `admit_requests` 테이블·Admit Worker·`verified-token`은 **전부 폐기**됐다.

```mermaid
flowchart TD
    SLOT(["Tenant\n슬롯 여유 생김"])
    --> ADMIT["POST /queues/:queueId/admit\n{ count: N, requestId }\nTenant → Platform"]

    ADMIT --> OWN["① queues 행 읽기\nTenant 소유권 검증\n※ tokens 행은 읽지 않는다"]

    OWN --> LUA["② EVAL admit.lua — 전 구간 원자 (Redis 밖 호출 0회)\n\nqueue:{queueId}:admit-idem:{requestId} 있으면 → REPLAY 반환\n\nZPOPMIN queue:{queueId}:waiting N → (identifier, seq) N쌍\n  ※ ZSet 하나(§66 D2) + score가 INCR 단조증가(§70 D9) → 이미 FIFO\n  ※ 거를 대상이 없어 ZRANGE+ZREM이 ZPOPMIN 한 명령이 됐다\nHGET queue:{queueId}:tokens {identifier} → 'tokenId|issuedAt'\n  ※ 미스/레거시면 ZADD로 원래 seq에 되돌리고 건너뛴다 — 안 되돌리면 대기열에서 사라진다\nSET queue:{queueId}:admit-by-token:{tokenId} PX 60000\nSET queue:{queueId}:admit-by-admit:{admitToken} PX 60000\n  ※ 이 둘과 admit-idem은 KEYS[] 선언 불가 → CROSSSLOT 검사가 안 걸린다\n  ※ 접두사는 Java(QueueKeys)가 만들어 ARGV로. Lua는 prefix .. tokenId 만 (§80)\nZADD queue:{queueId}:admitted {만료 epoch ms} '{seq}|{identifier}'\nwatermark 조건부 갱신 (현재값보다 클 때만, §79)\nqueue:{queueId}:admit-idem:{requestId} = 결과 payload"]

    LUA --> KAFKA["③ Kafka token-lifecycle 발행\nADMITTED × N (key=tokenId)\n→ Consumer: status 0→1, admit_token, admitted_at\n※ 건별 12초 블로킹. 첫 발행 실패면 나머지를 건너뛴다\n※ 건너뛴 분은 자동 복구되지 않는다 — 실패해도 200이라\n   Tenant가 재시도할 이유가 없다. 흔적은 ERROR 로그뿐 (§80)"]
    --> ARESP(["④ 200 OK\n{ admitted: [{tokenId, identifier, seq, admitToken}...] }\n\n보장: 대기열에서 빠졌고 admitToken을 쥐었다 (Redis 사실)\n미보장: tokens.status가 이미 1이다"])

    ARESP --> POLL["유저 다음 Polling 시\nadmitToken 수신"]
    --> USER["유저 → Tenant\nadmitToken 전달"]
    --> VERIFY["POST /queues/:queueId/admit-tokens/:admitToken/verify\nTenant → Platform\n유효성 확인 + COMPLETED 발행 (PR #48)\n※ 이 응답 시점이 완료다\nRedis·DB 직접 쓰기 0회 — 이벤트만 낸다"]

    VERIFY --> VK{"queue:{queueId}:admit-by-admit:{admitToken}\n유효?\n값에 tokenId와 identifier가 함께 들어 있다 (첫 구분자로만 분리)\n→ identifier가 손에 있으므로 DB를 안 읽는다 (§80)"}
    VK -->|"만료 or 무효"| VDB["DB Fallback\nSELECT WHERE admit_token=:admitToken\nAND status=ADMIT_ISSUED\nAND admitted_at > UTC_TIMESTAMP(3) - INTERVAL 60 SECOND\n(issued_at 아님 — 줄 선 시각은 2시간 전일 수 있다. §80)"]
    VDB -->|"없음"| E404(["404 INVALID_ADMIT_TOKEN (TK002)\n※ TTL 만료 후 verify는 404"])
    VDB -->|"있음"| VRESP
    VK -->|"유효"| VRESP(["200 OK\n{ valid: true, identifier }"])

    VRESP --> ALLOW["Tenant → 유저 입장 허용"]
    --> COMPLETE["POST /queues/:queueId/tokens/:tokenId/complete\n{ admitToken }\nTenant → Platform"]

    COMPLETE --> DB["DB 권위로 판정 (Redis 아님)\nUPDATE tokens SET status=2, completed_at=?\n WHERE token_id=? AND admit_token=?\n   AND status IN (0, 1)   ← 관대하게\n   AND admitted_at > now - 300초\n   (COMPLETE_VALID_WINDOW_SECONDS, §80 구현)\n\n0을 허용하는 이유: 컨슈머 랙으로 status가\n아직 0인 정상 입장자가 실재한다\n(§36으로 TTL 만료 복귀는 폐기됐다)"]
    DB -->|"0행"| FB["① findCompletedAt — 이미 COMPLETED면 200\n② Redis admit-by-admit 폴백 → cleanup + COMPLETED 발행 (WARN)\n※ 순서를 뒤집지 마라 — ReconcileJob이 완료 토큰을 EXPIRED로 확정한다"]
    FB -->|"둘 다 실패"| E404C(["404 INVALID_ADMIT_TOKEN (TK002)\n409를 따로 두지 않는다 — 원인이 무엇이든\nTenant가 할 일은 같다"])
    DB -->|"1행"| ZREM["Redis 정리 — cleanup_completed.lua (EVAL 1회, 원자)\nZREM admitted · DEL admit-by-token + admit-by-admit ← 무조건\n(회차마다 유일한 값이 키에 박혀 있다)\nHGET tokens {identifier} → tokenId 대조\n일치할 때만 ZREM waiting → HDEL tokens ← HDEL 마지막\n불일치 = 재-enqueue한 다음 회차다. 지우면 그 사람을 축출한다"]
    --> KAFKA2["Kafka COMPLETED 발행 (key=tokenId)\n※ 발행 실패는 삼킨다(로그만) — DB는 이미 status=2로 확정"]
    --> COK(["200 OK\n{ status: COMPLETED, completedAt }"])

    DB -->|"ZREM 실패 시"| FIX["재시도 없음 — 잔류분은 inactiveTtl 배치가 걷어간다"]

    LUA -->|"admitToken TTL 60초 초과"| BACK["종료 — 복귀 없음 (queue-batch, §36·§80)\nadmit_expire.lua: ZRANGEBYSCORE queue:{queueId}:admitted 0 now\n→ ZREM queue:{queueId}:admitted\n→ HGET queue:{queueId}:tokens {identifier} (tokenId·issuedAt 확보)\n→ HDEL queue:{queueId}:tokens {identifier} ← 중복 게이트 해제\n→ Kafka EXPIRED 발행\n※ waiting으로 ZADD 하지 않는다 — 재접속하면 맨 뒤다\n※ DB status는 1에 머문다 — EXPIRED 소비 가드가\n   IF(status=0,4,status)라 1에서는 no-op (complete 300초 창을 살리려는 의도)\n※ ShedLock 없음 — EVAL 자체가 claim (§80)\n※ 큐 목록은 DB queues에서 (Cluster SCAN은 노드별)"]
```

> **왜 DB WAITING 확인이 없나 (§80)**
> 순번은 Redis에 먼저 쓰고 DB에는 Kafka를 거쳐 나중에 들어간다(§71 D11). 그 창에 들어온 정상
> 대기자를 "DB에 없으니 유령"으로 판정해 `ZREM`하면 **대기열에서도 빠지고 DB에도 없어 복구 근거가
> 사라진다.** 대기 중인지에 대한 **권위는 Redis**다.
> 대가는 좀비도 뽑힌다는 것(10자리 → 실입장 9명)이고, 이는 DB를 봐도 해결되지 않아 **관측**으로 다룬다.

> **멱등**
> `queue:{queueId}:admit-idem:{requestId}`가 결과 payload를 들고 있어 재시도는 **REPLAY**로 답한다.
> ⚠️ 이 키가 유실되면 **중복 admit을 감지할 수단이 없다** — `admit_requests`의 UNIQUE가
> 마지막 방어선이었는데 폐기했다(§80이 명시적으로 수용한 대가).

---

## 이탈 → EXPIRED

> 🔴 **이탈 전용 엔드포인트는 없다 (DECISIONS §82).** `DELETE /queues/:queueId/tokens/:tokenId`는
> 만들지 않는다. 유저가 취소 버튼을 누르든 탭을 닫든 네트워크가 끊기든, Platform이 관측하는
> 신호는 **"폴링이 멈춘다"** 하나뿐이다. 아래 TTL 만료 Batch의 `inactiveTtl` 경로가 그것을 잡는다.

```mermaid
flowchart TD
    U(["유저 이탈\n취소 버튼 · 탭 닫음 · 네트워크 끊김"])
    --> STOP["브라우저가 폴링을 멈춘다\nqueue:{queueId}:last-active score가 늙는다"]
    --> WAIT["유예 창 = inactiveTtl (기본 300초)\nTenant가 큐마다 정한다"]

    WAIT -->|"창 안에 개인 폴링 재개(ka 무관)"| BACK["poll_verify.lua가 ZADD last-active\n(재-enqueue는 순번만 복원할 뿐\nlast-active를 갱신하지 않는다)"]
    WAIT -->|"창을 넘김"| JOB["TTL 만료 Batch가 회수\nlast-active member=seq → waiting에서 identifier 역산\nHGET tokens로 issuedAt 원본 확보(HDEL 전!)\nZREM waiting + ZREM last-active + HDEL tokens"]

    JOB --> EXP["Kafka EXPIRED 발행 (key=tokenId)\nDB status = EXPIRED(4)\nexpiredReason = INACTIVE(3)"]
    EXP --> BILL(["과금 대상 — 자리를 끝까지 점유했다 (§82)"])
    JOB --> NEW["이후 재-enqueue는 신규\n새 tokenId·새 seq → 맨 뒤\n과금 +1건"]
```

**`HDEL tokens`가 곧 중복 게이트를 푸는 행위다.** 그래서 `inactiveTtl`은 단순한 청소 주기가
아니라 **"몇 초까지 자리를 지켜줄 것인가"** 라는 유예 창이 된다.

---

## TTL 만료 Batch (10초 주기)

```mermaid
flowchart TD
    JOB(["TokenReclaimJob\n10초 주기\n※ TokenExpiryJob이라는 클래스는 없다 —\n   세 판정이 이 잡 하나에 들어갔다"])
    --> ALIST["큐 목록 조회 — queueRepository.findAll()\n※ 상태 필터 없음 · 순차 for 루프\n※ 락 없음 — EVAL 자체가 claim (§80)\n※ 큐당 CLAIM_LIMIT=500"]

    ALIST --> W1 & W2 & W3

    W1["waitingTtl 체크 (WAITING)\nZRANGE 앞부분 고정량 스캔\n→ HGET tokens의 issuedAt < cutoff\n※ score는 seq다 — 시간 cutoff로 ZRANGEBYSCORE 불가"]
    W2["inactiveTtl 체크 (WAITING)\nZRANGEBYSCORE queue:{queueId}:last-active\n0 ~ now_ms - inactiveTtl_ms\n(member=seq, score=마지막 ka 시각)"]
    W3["admitToken TTL 체크 (ADMIT_ISSUED) — claim-Lua (§80)\nZRANGEBYSCORE queue:{queueId}:admitted 0 now\n(score=만료 epoch ms, member='seq|identifier')\n※ EXISTS 스캔 폐기 — 만료된 키는 이미 사라져\n   무엇이 만료됐는지 알 수단이 없었다"]

    W1 -->|"WAITING_TTL(4)"| EXP["waiting_expire.lua / inactive_expire.lua\nZREM waiting + ZREM last-active + HGET/HDEL tokens\n→ Kafka EXPIRED 발행 → 컨슈머가 DB status=4\n※ DB를 직접 UPDATE하지 않는다 — 이벤트 경로다\n※ expiredReason을 이벤트에 싣는다 → 컨슈머가 expired_reason 컬럼에 적재 (§86)\n※ 큐당 상한으로 끊는다 (한 주기가 다른 큐를 굶기지 않게)"]
    W2 -->|"INACTIVE(3)"| EXP

    W3 -->|"ADMIT_TOKEN_TTL(2)"| BACK["종료 — 복귀 없음 (queue-batch, §36)\nadmit_expire.lua 한 스크립트: ZREM admitted → HGET/HDEL tokens\nKafka EXPIRED 발행\n※ waiting으로 되돌리지 않는다\n※ DB status는 1에 머문다 (소비 가드가 1에서 no-op)\n   → 잔류분은 ReconcileJob이 complete 창 300초 뒤 4로 정리"]

    EXP --> DONE(["완료\n멱등: 상태 필터로 중복 처리 없음"])
    BACK --> DONE
```

---

## ~~슬라이스 구조~~ — 폐기 (§66 D2)

> 대기열을 `queue:{t}:{q}:{0..N}`으로 쪼개고 `ZCOUNT`를 합산해 순위를 내던 설계는 **폐기됐다.**
> 현행은 **ZSet 하나**(`queue:{queueId}:waiting`) + `INCR queue:{queueId}:seq`로 score를 발급한다
> (§66 D2 · §70 D9). 쪼개지 않으니 합산도, 슬라이스 간 정렬도 없다.
> 순위는 `ZRANK`(enqueue 응답) 또는 `rank = mySeq − lastAdmittedSeq`(폴링, §79)로 구한다.

---

## Kafka Topic 흐름

```mermaid
flowchart LR
    subgraph API["Queue Platform API"]
        E1["Enqueue 처리\nRedis ZADD 완료"]
        E2["admit 처리\nadmit.lua 원자 실행"]
    end

    subgraph TOPICS["Kafka"]
        T1["token-lifecycle (18 파티션)\nkey = tokenId\n{ tokenId, queueId, tenantId\nuserId, seq, issuedAt }"]
    end

    subgraph CONSUMER["queue-consumer (독립 앱)"]
        C1["TokenLifecycleConsumer\n배치 적재 → tokens INSERT (멱등)"]
    end

    E1 -->|"produce"| T1
    E2 -->|"produce (ADMITTED)"| T1
    T1 -->|"consume"| C1
```

> **키가 `tokenId`인 이유**: `queueId`로 잡으면 한 큐 30만 명이 통째로 한 파티션에 몰린다 (DECISIONS §73 D16).
> **토픽을 안 나누는 이유**: 순서 보장은 같은 토픽의 같은 파티션 안에서만 성립한다 (§73 D18).

---

## 클라이언트 Polling 구조 (JS SDK)

```mermaid
flowchart TD
    TENANT["Tenant 서버\n(REST 직접 호출, X-API-Key — SDK 아님)\nPOST /enqueue → 대기토큰 발급\n대기 페이지에 tokenId, seq 실어 전달"]
    --> CLIENT["브라우저 (JS SDK)\nqueue.startPolling()"]

    CLIENT --> POLL["JS SDK 내부\npoll() 실행"]
    --> REQ["GET /queues/:queueId/status\nPlatform 직접 호출 (인증 없음)\n30만 명 전원 동일 응답 → 캐시"]
    --> PLATFORM["Queue Platform\nMGET admit-watermark, pacing, seq\n(rank 계산 없음 / seq는 큐 실재 판정)"]
    --> RESP["응답\n{ lastAdmittedSeq, pacing }"]

    RESP --> CALC["JS SDK 계산\nrank = mySeq − lastAdmittedSeq\n간격 = pacing 표 + ±20% 지터"]
    CALC -->|"rank > 0"| TIMER["setTimeout(간격 × 1000)\n→ poll() 재호출\n탭 비활성화 시 자동 중단\n30~60초마다 개인 호출"]
    TIMER --> POLL

    CALC -->|"rank <= 0"| ME["GET /queues/:queueId/tokens/:tokenId?seq=&ka=\n(§74 소유권 검증)"]
    ME -->|"ready=false"| TIMER
    ME -->|"ready=true"| CB["onReady 콜백\nadmitToken 수신"]
    --> SEND["유저 → Tenant 서버: admitToken 전달"]
    --> TENANT2["Tenant 서버 (REST 직접 호출 — SDK 아님)\n① verify 즉시 호출\n② valid 확인\n③ Tenant 내부 처리\n④ complete 호출 (재시도는 Tenant 몫)"]
```

> **역할 분리**
> `pacing` 구간표·`lastAdmittedSeq` 제공: Platform 책임 (부하 제어 레버는 서버가 쥔다, §79)
> rank 계산 / setTimeout / 탭 비활성화 처리: JS SDK 책임
> UI 업데이트: 클라이언트(Tenant) 책임
> verify 순서 / complete 재시도: **Tenant 서버 책임** — 강제할 SDK가 없으므로 명세로 규정하고
> 서버가 위반을 방어한다 (DECISIONS §35)

---

## Tenant 서버 통신 vs Platform 직접 통신

| 통신 대상 | 시점 | 빈도 | 내용 |
|----------|------|------|------|
| Tenant 서버 | 진입 시 | 1회 | 슬롯 여유 확인, 대기토큰 수신 |
| Tenant 서버 | 입장 시 | 1회 | admitToken 전달, 세션 생성 |
| Platform (JS SDK) | 대기 중 | 2~30초마다 반복 | Polling (가장 빈번) |

> Polling이 가장 빈번한 통신인데 JS SDK가 Platform과 직접 처리.
> Tenant 서버는 진입/입장 **2번만** 관여 (이탈은 API가 없다 — §82).
> 이것이 "유저가 Platform에 직접 Polling" 원칙의 실제 구현.

---

## 수평 확장 구조

```mermaid
flowchart TD
    USER["유저/Tenant"]
    --> LB["Load Balancer\nNginx (로컬) / AWS ALB (운영)\nleast_conn 분산"]

    LB --> A["API Server A\nSpring MVC + Virtual Thread"]
    LB --> B["API Server B\nSpring MVC + Virtual Thread"]
    LB --> C["API Server C\nSpring MVC + Virtual Thread"]

    A & B & C --> REDIS["Redis\nINCR queue:{queueId}:seq 원자\n→ seq 중복 없음"]
    A & B & C --> KAFKA["Kafka\ntoken-lifecycle (key=tokenId)"]
    A & B & C --> MYSQL["MySQL\nJPA + Virtual Thread"]
```

> **순서 보장**: `INCR queue:{queueId}:seq` = Redis 싱글스레드 원자 연산
> 서버 여러 대가 동시 호출해도 seq 절대 중복 없음
> Tenant는 Load Balancer 주소만 알면 됨 (내부 서버 수 몰라도 됨)

---

## Cluster 라우팅 (현행)

큐를 만들 때 **큐 단위로** 두 클러스터 중 하나를 고르고, 그 큐의 키 4종은 전부 같은 클러스터에
같은 slot으로 들어간다(해시태그, `QueueKeys`).

- 배정 기준은 **cluster1의 메모리 사용률**이다 — 0.5 이상이면 cluster2 (`RedisClusterAssigner`).
- 클러스터 안에서는 Lettuce가 slot으로 자동 라우팅한다. 개발자 개입이 없다.

> 🔴 여기 있던 "Sprint 12+ 이중 라우팅" 다이어그램은 삭제했다. 키 형식 `queue:{shard_X}:...`와
> Tenant Tier(VIP/Premium) 분기는 **둘 다 철회된 설계**다(태그는 shard로 이동하지 않았고,
> 등급제는 §88에서 사라졌다). 계획으로 읽히면 되돌리기 어려운 키 변경을 부른다.


## Enqueue 순서 보장 제약 (Sprint 5-E 확정)

**본인 확정 사항**: WAS에 enqueue되는 순서는 완전히 보장하지 않음

### 이유

여러 WAS에서 동시 요청 시:
```
Client A → WAS 1 → Redis (도착 t=100ms)
Client B → WAS 2 → Redis (도착 t=90ms)

Redis 저장 순서: B, A (도착 순)
실제 요청 순서: A, B (사용자 관점)

결과: Redis 도착 순서 = Enqueue 순서
      사용자 요청 순서와 다를 수 있음
```

### 이 제약의 이점

**분산 처리 가능**:
- WAS 확장 자유 (Sticky Session 불필요)
- 각 WAS 독립 처리
- 병렬화 가능

**부하 분산**:
- 로드 밸런서 자유
- 확장성 확보

**성능 최적화**:
- 각 WAS 로컬 처리
- 대기 없음
- 처리량 극대화

**Kafka 적재 (구현 완료, §73)**:
- DB INSERT만 비동기 — 발행은 응답 전에 동기로 기다린다
- 같은 tokenId는 같은 파티션이라 상태 전이 순서가 보장된다

### 실무 관행

Ticketmaster, 인터파크 등:
- 밀리초 단위 정확한 순서 X
- 대략적 순서 제공 (사용자 인식 무관)
- 대량 처리 우선

**결론**: Redis 도착 순서 = Enqueue 순서만 보장, 사용자 요청 순서 ≠ Enqueue 순서 (제약 사항으로 명시)

---

## 부하 감소 요약

> 여기 있던 Before/After 표와 Sprint별 최적화 목록은 삭제했다. 측정 출처가 없는 추정치였고,
> 네 항목 중 셋이 이미 죽었다(Caffeine 미채택 · Kafka 도입 완료 · 이중 라우팅 철회).
> **실측은 `doc/perf/ENQUEUE_TUNING.md`에 있다.**

