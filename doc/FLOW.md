# 🔄 Queue Platform — 상세 흐름도

> FRS v1.6 기준

---

## Enqueue

```mermaid
flowchart TD
    START(["POST /queues/:queueId/tokens
    Tenant 서버 호출
    { userId }"])
    --> AK["① API Key 검증
    SHA-256 → Redis 캐시 60s
    미스 시 DB fallback"]
    --> RL["② Rate limit 체크
    per-key 100rps"]
    --> QS["③ 큐 상태 확인
    ACTIVE만 허용"]
    --> DUP["④ userId 중복 체크
    GET queue-user:{t}:{q}:{userId}"]

    DUP -->|"이미 있음"| IDEM(["200 기존 토큰 반환
    멱등 처리"])
    DUP -->|"없음"| CAP["⑤ 전체 용량 체크
    모든 슬라이스 ZCARD 합산"]

    CAP -->|"≥ totalCapacity"| E429(["429
    QE_001_CAPACITY_EXCEEDED"])
    CAP -->|"여유 있음"| LUA["⑥ Bulk Lua Script 원자 실행
    INBYN global-seq N → startSeq~endSeq
    슬라이스별 ZADD multi-member NX
    slice = (seq-1) % sliceCount"]

    LUA --> OK(["200 OK
    { token, globalRank, estimatedWaitSeconds }"])
    OK --> ASYNC["⑦ 비동기 (Reactor 백그라운드)
    DB INSERT 100건 Bulk / 3회 재시도
    INCR billing-count
    SET queue-user 역인덱스"]

    AK -->|"무효"| E401(["401 AK_001_UNAUTHORIZED"])
    RL -->|"초과"| E429B(["429 RL_001_KEY_LIMIT"])
    QS -->|"PAUSED/DRAINING"| E503(["503 QM_004_NOT_ACTIVE"])
```

---

## Polling (유저 → Platform 직접)

```mermaid
flowchart TD
    POLL(["GET /queues/:queueId/tokens/:token
    유저가 직접 호출 (5초 간격)
    token으로 인증"])
    --> TK["① token 유효성 확인
    Redis GET token-info:{tokenId} (캐시 5초)
    미스 시 DB 조회 → 캐시 저장"]

    TK -->|"무효/만료"| E401(["401 TK_001_INVALID_TOKEN"])
    TK -->|"유효 (WAITING)"| RANK["② 전체 순위 계산
    Lua Script
    ZSCORE → mySeq
    모든 슬라이스 ZCOUNT 합산
    globalRank = 합산 + 1"]

    RANK --> LA["③ SET token-last-active
    EX inactiveTtl 리셋"]
    --> ETA["④ HGET queue-stats avgWaitingTime
    estimatedWaitSeconds = globalRank × avgWaitingTime"]
    --> RESP(["200 OK
    { globalRank, estimatedWaitSeconds
    ready: false, admitToken: null }"])
    RESP -->|"계속 대기"| POLL

    TK -->|"유효 (ADMIT_ISSUED)"| AT["⑤ Redis GET admit-token:{tokenId}
    admitToken 조회"]
    --> ARESP(["200 OK
    { globalRank: 1, ready: true
    admitToken: at_xxx }"])
    --> USER["유저 → Tenant
    admitToken 전달"]
```

---

## Admit → Verify → Complete

```mermaid
flowchart TD
    SLOT(["Tenant
    슬롯 여유 생김"])
    --> ADMIT["POST /queues/:queueId/admit
    { count: N, requestId }
    Tenant → Platform"]

    ADMIT --> IDEM{"admit-idem:{requestId}
    존재?"}
    IDEM -->|"있음 (중복)"| CACHED(["200 OK
    기존 결과 반환"])
    IDEM -->|"없음"| QUEUE["Redis List RPUSH
    admit-request-queue:{t}:{q}
    순서 보장 적재"]

    QUEUE --> WORKER["Queue 단위 워커 (풀 10개)
    BLPOP — 이전 완료 후 꺼냄"]
    --> LUA["Lua Script
    슬라이스별 ZRANGE WITHSCORES
    Lua 내부 score 정렬
    상위 N명 선택
    ZREM multi-member"]
    --> FILTER["DB WAITING 상태 확인
    불일치 즉시 ZREM
    부족 시 최대 3회 추가 추출"]
    --> TOKEN["admitToken 발급
    SET admit-token:{tokenId} EX 30
    DB UPDATE ADMIT_ISSUED (100건씩)
    SET token-info 캐시 갱신"]
    --> ARESP(["200 OK
    { admitTokens: [{userId, admitToken}...] }"])

    ARESP --> POLL["유저 다음 Polling 시
    admitToken 수신"]
    --> USER["유저 → Tenant
    admitToken 전달"]
    --> VERIFY["POST /admit-tokens/:admitToken/verify
    Tenant → Platform
    유효성 확인만 (상태 변경 없음)"]

    VERIFY --> VK{"admitToken
    유효?"}
    VK -->|"만료 or 무효"| E404(["404 TK_002_INVALID_ADMIT_TOKEN"])
    VK -->|"유효"| VRESP(["200 OK
    { valid: true, userId }"])

    VRESP --> ALLOW["Tenant → 유저 입장 허용"]
    --> COMPLETE["POST /tokens/:token/complete
    { admitToken }
    Tenant → Platform
    입장 완료 통보"]

    COMPLETE --> CK{"ADMIT_ISSUED
    상태?"}
    CK -->|"아님"| E409(["409 QE_006_INVALID_STATUS"])
    CK -->|"확인"| DB["DB status = COMPLETED
    ← 먼저 (원자성 전략)"]
    --> ZREM["Redis ZREM
    DEL admit-token
    DEL token-info 캐시
    ← 나중"]
    --> AVG["avgWaitingTime 갱신
    waitingSeconds = now - issuedAt"]
    --> COK(["200 OK
    { status: COMPLETED, completedAt }"])

    DB -->|"ZREM 실패 시"| FIX["Batch 10초 내
    ZREM 재실행 (멱등)"]

    TOKEN -->|"admitToken TTL 30초 초과
    Batch 감지"| BACK["WAITING 복귀
    DB SELECT seq
    Redis ZADD {seq} {tokenId}
    DB UPDATE WAITING
    DEL token-info 캐시"]
```

---

## 이탈 → CANCELLED

```mermaid
flowchart TD
    DQ(["DELETE /queues/:queueId/tokens/:token
    Tenant 서버 호출
    유저 대기 포기"])
    --> CHK["상태 확인"]

    CHK -->|"ADMIT_ISSUED"| E409A(["409 QE_006_INVALID_STATUS
    입장토큰 발급 후 이탈 불가
    admitToken TTL 30초 후
    WAITING 복귀 후 이탈 가능"])
    CHK -->|"WAITING 아님
    (COMPLETED/EXPIRED/CANCELLED)"| E409B(["409 QE_006_INVALID_STATUS"])
    CHK -->|"WAITING"| ZREM["Redis ZREM
    뒤 순위 자동 당겨짐"]
    --> DB["DB status = CANCELLED
    cancelledAt 기록"]
    --> DEL["DEL queue-user 역인덱스
    DEL token-info 캐시
    같은 userId 재Enqueue 가능 (맨 뒤)"]
    --> OK(["200 OK
    { status: CANCELLED, cancelledAt }"])
```

---

## TTL 만료 Batch (10초 주기)

```mermaid
flowchart TD
    JOB(["TokenExpiryJob
    10초 주기"])
    --> ALIST["활성 큐 목록 조회
    ACTIVE · PAUSED · DRAINING
    큐별 병렬 처리 (동시 10개 / 8초 타임아웃)"]

    ALIST --> W1 & W2 & W3

    W1["waitingTtl 체크 (WAITING)
    ZRANGEBYSCORE
    0 ~ now_ms - waitingTtl_ms"]
    W2["inactiveTtl 체크 (WAITING)
    EXISTS token-last-active
    = 0 이면 비활동"]
    W3["admitToken TTL 체크 (ADMIT_ISSUED)
    EXISTS admit-token:{tokenId}
    = 0 이면 만료"]

    W1 -->|"WAITING_TTL"| EXP["DB UPDATE EXPIRED
    expiredReason 기록
    Redis ZREM
    DEL token-info 캐시
    100건씩 / 10ms 대기"]
    W2 -->|"INACTIVE_TTL"| EXP

    W3 -->|"ADMIT_TOKEN_TTL"| BACK["WAITING 복귀
    DB SELECT seq
    Redis ZADD {seq} {tokenId}
    DB UPDATE WAITING
    DEL token-info 캐시"]

    EXP --> DONE(["완료
    멱등: 상태 필터로 중복 처리 없음"])
    BACK --> DONE
```

---

## 슬라이스 구조 — 전체 순위 보장

```mermaid
flowchart LR
    subgraph GLOBAL["글로벌 순번 (Bulk)"]
        SEQ["global-seq:{t}:{q}
        INBYN 500 → 1~500 블록 채번"]
    end

    subgraph SLICES["슬라이스
    slice = (seq-1) % sliceCount (라운드로빈)"]
        S0["queue:{t}:{q}:0
        seq 1,4,7,10..."]
        S1["queue:{t}:{q}:1
        seq 2,5,8,11..."]
        S2["queue:{t}:{q}:2
        seq 3,6,9,12..."]
    end

    subgraph RANK["전체 순위 계산 (내 seq=5)"]
        R["ZCOUNT slice:0 0~4 = 1
        ZCOUNT slice:1 0~4 = 2
        ZCOUNT slice:2 0~4 = 1
        합산 + 1 = 5등"]
    end

    subgraph DEQUEUE["Admit Dequeue (N명)"]
        D["슬라이스별 ZRANGE WITHSCORES
        Lua 내부 score 정렬
        상위 N명 선택
        ZREM multi-member"]
    end

    SEQ --> S0
    SEQ --> S1
    SEQ --> S2
    S0 --> R
    S1 --> R
    S2 --> R
    S0 --> D
    S1 --> D
    S2 --> D
```
