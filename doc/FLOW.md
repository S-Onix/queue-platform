# 🔄 Queue Platform — 상세 흐름도

> FRS v1.5 기준

---

## Enqueue

```mermaid
flowchart TD
    START(["POST /tokens
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
    CAP -->|"여유 있음"| LUA["⑥ Lua Script 원자 실행
    INCR global-seq → seq
    slice = seq % sliceCount
    ZADD queue:{t}:{q}:{slice} NX {seq}
    SET token-status:{tokenId} WAITING EX waitingTtl"]

    LUA --> OK(["200 OK
    { token, rank, isFirst: false,
      message, estimatedWaitSeconds }"])
    OK --> ASYNC["⑦ 비동기
    INCR billing-count
    SET queue-user 역인덱스
    DB tokens INSERT"]

    AK -->|"무효"| E401(["401 AK_001_UNAUTHORIZED"])
    RL -->|"초과"| E429B(["429 RL_001_KEY_LIMIT"])
    QS -->|"PAUSED/DRAINING"| E503(["503 QM_004_NOT_ACTIVE"])
```

---

## Polling (유저 → Platform 직접)

```mermaid
flowchart TD
    POLL(["GET /tokens/{token}
    유저가 직접 호출
    token으로 인증"])
    --> TK["① token 유효성 확인
    DB 조회"]

    TK -->|"무효/만료"| E401(["401 TK_001_INVALID_TOKEN"])
    TK -->|"유효"| STATUS["② GET token-status:{tokenId}
    Redis 상태 조회"]

    STATUS --> RANK["③ 전체 순위 계산
    Lua Script
    ZSCORE → mySeq
    모든 슬라이스 ZCOUNT 합산
    rank = 합산 + 1"]

    RANK --> LA["④ SET token-last-active
    EX inactiveTtl 리셋"]
    --> ETA["⑤ HGET queue-stats avgWaitingTime
    estimatedWaitSeconds = rank × avgWaitingTime"]
    --> MSG["⑥ QueueMessageSource
    상태별 다국어 메시지 조회
    Accept-Language 헤더 기반"]
    --> RESP(["200 OK
    { rank, isFirst, message,
      estimatedWaitSeconds }"])

    RESP -->|"isFirst: false"| POLL
    RESP -->|"isFirst: true"| NOTIFY["유저 → Tenant
    나 ready야"]
```

> `token-status:{tokenId}` 가 `CAN_ENTER`이면 `isFirst: true` 반환.
> 클라이언트에게 상태값(CAN_ENTER)은 직접 노출하지 않음.

---

## CAN_ENTER 전이 흐름

```mermaid
flowchart TD
    TRIGGER["누군가 대기열에서 빠짐
    COMPLETED / CANCELLED / EXPIRED"]
    --> LUA["Lua Script 원자 실행
    ZREM queue:{t}:{q}:{slice} {tokenId}
    SET token-status:{tokenId} {newStatus} EX 300"]
    --> NEXT["ZRANGE queue:{t}:{q}:{slice} 0 0
    다음 1등 tokenId 조회"]

    NEXT -->|"없음"| DONE(["대기열 비어있음
    종료"])
    NEXT -->|"있음"| CE["SET token-status:{nextTokenId} CAN_ENTER EX 300
    DB status = CAN_ENTER 업데이트"]
    --> POLLING["다음 Polling 시
    isFirst: true 반환
    message: 입장 가능합니다."]
```

---

## Status 확인 → Admit

```mermaid
flowchart TD
    NOTIFY["유저 → Tenant
    ready 알림"]
    --> SLOT{"Tenant
    슬롯 여유?"}

    SLOT -->|"없음"| WAIT["유저에게 대기 안내
    10초 후 재시도"]
    WAIT --> NOTIFY

    SLOT -->|"있음"| STATUS["GET /tokens/{token}/status
    Tenant → Platform
    Admit 전 상태 확인"]

    STATUS --> CHECK{"isFirst: true?"}
    CHECK -->|"false 또는 EXPIRED"| SKIP["스킵
    다음 슬롯 날 때 재시도"]
    CHECK -->|"true"| ADMIT

    ADMIT(["POST /tokens/{token}/admit
    Tenant → Platform"])
    --> V1["① WAITING 또는 CAN_ENTER 확인
    + globalRank = 1 확인"]
    V1 -->|"아님"| E409(["409 QE_008_NOT_READY"])
    V1 -->|"확인"| DB["② DB status = COMPLETED
    먼저 (원자성 전략)"]
    --> ZREM["③ Redis ZREM
    나중"]
    --> CE["④ Lua Script
    다음 1등 CAN_ENTER 전이"]
    --> ETA_UPDATE["⑤ avgWaitingTime 갱신
    HINCRBYFLOAT waitingTimeSum
    HINCRBY waitingTimeCount
    HSET avgWaitingTime"]
    --> AOK(["200 OK
    { status: COMPLETED, completedAt }"])
    --> ALLOW["Tenant → 유저
    입장 허용"]

    DB -->|"ZREM 실패 시"| FIX["Batch 싱크 스케줄러
    5분 내 Redis 정합성 복구"]
```

---

## 이탈 → CANCELLED

```mermaid
flowchart TD
    DQ(["DELETE /tokens/{token}
    Tenant 서버 호출
    유저 대기 포기"])
    --> CHK["WAITING 또는 CAN_ENTER 상태 확인"]

    CHK -->|"아님"| E409(["409 QE_006_INVALID_STATUS"])
    CHK -->|"확인"| DB["DB status = CANCELLED
    cancelledAt 기록"]
    --> ZREM["Redis ZREM"]
    --> CE["CAN_ENTER 상태였으면
    다음 1등 CAN_ENTER 전이"]
    --> DEL["DEL queue-user 역인덱스
    DEL token-status:{tokenId}
    같은 userId 재Enqueue 가능"]
    --> OK(["200 OK
    { status: CANCELLED, cancelledAt }"])
```

---

## TTL 만료 Batch

### 스케줄러 1: 만료 처리 (30초 주기)

```mermaid
flowchart TD
    JOB(["TokenExpiryJob
    30초 주기"])
    --> ALIST["SCAN으로 활성 큐 목록 조회
    ACTIVE · PAUSED · DRAINING"]

    ALIST --> W1 & W2

    W1["waitingTtl 체크
    ZRANGEBYSCORE
    0 ~ now_ms - waitingTtl_ms"]
    W2["inactiveTtl 체크
    EXISTS token-last-active
    = 0 이면 비활동"]

    W1 -->|"WAITING_TTL"| EXP
    W2 -->|"INACTIVE_TTL"| EXP

    EXP["① DB status = EXPIRED
    expiredReason 기록 (먼저)
    ② Redis ZREM (나중)
    ③ DEL token-status:{tokenId}"]
    --> CE["CAN_ENTER 상태였으면
    다음 1등 CAN_ENTER 전이
    DB + Redis 모두 업데이트"]
    --> DONE(["완료
    멱등: WAITING / CAN_ENTER 상태만 처리"])
```

### 스케줄러 2: Redis 싱크 (5분 주기)

```mermaid
flowchart TD
    SYNC(["RedisSyncJob
    5분 주기"])
    --> DBQUERY["DB에서 최근 5분간
    상태 변경된 토큰 조회
    (COMPLETED / CANCELLED / EXPIRED)"]

    DBQUERY --> EACH["각 토큰마다
    GET token-status:{tokenId} 조회"]

    EACH --> CMP{"DB 상태와
    Redis 상태 일치?"}

    CMP -->|"일치"| PASS["패스"]
    CMP -->|"불일치"| FIX["Redis 정합성 복구
    ZREM (잔류 시)
    SET token-status 올바른 값으로 덮어쓰기"]

    FIX --> CECHECK{"CAN_ENTER가
    날아간 케이스?"}
    CECHECK -->|"아님"| DONE(["완료"])
    CECHECK -->|"맞음"| CE["ZRANGE 0 0
    다음 1등 조회
    CAN_ENTER 전이"]
    CE --> DONE
```

> DB가 Source of Truth. Redis 오류 발생 시 DB 기준으로 복구.
> 처리 순서: DB 먼저 → Redis 나중. Redis 실패 시 싱크 스케줄러가 5분 내 복구.

---

## 슬라이스 구조 — 전체 순위 보장

```mermaid
flowchart LR
    subgraph GLOBAL["글로벌 순번"]
        SEQ["global-seq:{t}:{q}
        INCR → 1,2,3,4,5,6..."]
    end

    subgraph SLICES["슬라이스"]
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

    SEQ --> S0
    SEQ --> S1
    SEQ --> S2
    S0 --> R
    S1 --> R
    S2 --> R
```
