# 🔄 Queue Platform — 상세 흐름도

> FRS v1.10 기준

---

## Enqueue

```mermaid
flowchart TD
    START(["POST /queues/:queueId/tokens\nTenant 서버 호출\n{ userId }"])
    --> AK["① API Key 검증\nSHA-256 → Redis 캐시 60s\n미스 시 DB fallback"]
    --> RL["② Rate limit 체크\nper-key 100rps"]
    --> QS["③ 큐 상태 확인\nACTIVE만 허용"]
    --> DUP["④ userId 중복 체크\nGET queue-user:{t}:{q}:{userId}"]

    DUP -->|"이미 있음"| IDEM(["200 기존 토큰 반환\n멱등 처리"])
    DUP -->|"없음"| CAP["⑤ 전체 용량 체크\n모든 슬라이스 ZCARD 합산\n(queue-count 제거 → ZCARD Pipeline)"]

    CAP -->|"≥ totalCapacity"| E429(["429\nQE_001_CAPACITY_EXCEEDED"])
    CAP -->|"여유 있음"| LUA["⑥ Bulk Lua Script 원자 실행\nINCRBY global-seq N → startSeq~endSeq\n슬라이스별 ZADD multi-member NX\nslice = (seq-1) % sliceCount"]

    LUA --> OK(["202 Accepted\n{ token, globalRank, estimatedWaitSeconds }\n즉시 응답 — DB INSERT는 Kafka 비동기 처리"])

    OK --> KAFKA["⑦ Kafka enqueue-events topic produce\n{ tokenId, queueId, tenantId, userId, seq, issuedAt }"]
    KAFKA --> CONSUMER["⑧ Kafka Consumer (Batch Server)\n1000건씩 Bulk INSERT → MySQL\nredis_sync_needed = 0 으로 초기화"]

    OK --> SIDE["⑨ 동기 처리 (Redis)\nSET queue-user 역인덱스 EX waitingTtl"]

    AK -->|"무효"| E401(["401 AK_001_UNAUTHORIZED"])
    RL -->|"초과"| E429B(["429 RL_001_KEY_LIMIT"])
    QS -->|"PAUSED/DRAINING"| E503(["503 QM_004_NOT_ACTIVE"])
```

> **Redis 다운 중 Enqueue 발생 시**
> Redis ZADD 실패 → `redis_sync_needed = 1`로 DB INSERT
> Kafka Consumer가 DB 저장 후 RedisHealthChecker가 복구 감지
> 복구 배치: `redis_sync_needed = 1` 토큰 → Sorted Set 재삽입

---

## Polling (유저 → Platform 직접)

```mermaid
flowchart TD
    POLL(["GET /queues/:queueId/tokens/:token\n유저가 직접 호출\ntoken으로 인증\n적응형 간격 (2~30초)"])
    --> TK["① token 유효성 확인\nRedis GET token-info:{tokenId}\n캐시 TTL = nextPollAfterSec + 2s\n미스 시 DB 조회 → 캐시 저장"]

    TK -->|"무효/만료"| E401(["401 TK_001_INVALID_TOKEN"])
    TK -->|"유효 (WAITING)"| RANK["② 전체 순위 계산\nLua Script\nZSCORE → mySeq\n모든 슬라이스 ZCOUNT 합산\nglobalRank = 합산 + 1"]

    RANK --> LA["③ SET token-last-active\nEX inactiveTtl 리셋"]
    --> ETA["④ HGET queue-stats avgWaitingTime\nestimatedWaitSeconds = globalRank × avgWaitingTime"]
    --> INTERVAL["⑤ nextPollAfterSec 계산\nposition > 500 → 30s\nposition > 100 → 10s\nposition > 10  → 5s\nposition ≤ 10  → 2s"]
    --> RESP(["200 OK\n{ globalRank, estimatedWaitSeconds\nnextPollAfterSec, ready: false, admitToken: null }"])
    RESP -->|"클라이언트: setTimeout(poll, nextPollAfterSec * 1000)"| POLL

    TK -->|"유효 (ADMIT_ISSUED)"| AT["⑥ Redis GET admit-token-by-token:{tokenId}\nadmitToken 조회"]
    --> ARESP(["200 OK\n{ globalRank: 1, ready: true\nadmitToken: at_xxx\nnextPollAfterSec: 2 }"])
    --> USER["유저 → Tenant\nadmitToken 전달"]
```

---

## Admit → Verify → Complete

```mermaid
flowchart TD
    SLOT(["Tenant\n슬롯 여유 생김"])
    --> ADMIT["POST /queues/:queueId/admit\n{ count: N, requestId }\nTenant → Platform"]

    ADMIT --> IDEM{"admit-idem:{requestId}\n존재?"}
    IDEM -->|"있음 (중복)"| CACHED(["200 OK\n기존 결과 반환"])
    IDEM -->|"없음"| DBINS["DB admit_requests INSERT\nstatus=PENDING\n← 영속성 기준점"]

    DBINS --> KAFKA["Kafka enqueue-admit topic produce\n{ requestId, tenantId, queueId, count }"]

    KAFKA --> CONSUMER["Kafka Consumer (Admit Worker)\nDB PENDING 확인 → 멱등 체크\nstatus=PROCESSING 업데이트"]

    CONSUMER --> LUA["Lua Script\n슬라이스별 ZRANGE WITHSCORES\nLua 내부 score 정렬\n상위 N명 선택\nZREM multi-member"]
    --> FILTER["DB WAITING 상태 확인\n불일치 즉시 ZREM\n부족 시 최대 3회 추가 추출\n추가 추출 시 전체 재정렬 → FIFO 보장"]
    --> TOKEN["admitToken 발급\nSET admit-token-by-token:{tokenId} EX 60\nSET admit-token-by-admit:{admitToken} EX 60\nDB UPDATE ADMIT_ISSUED (100건씩)\nSET token-info 캐시 갱신\nDB admit_requests status=COMPLETED"]
    --> ARESP(["200 OK\n{ admitTokens: [{userId, admitToken}...] }"])

    ARESP --> POLL["유저 다음 Polling 시\nadmitToken 수신"]
    --> USER["유저 → Tenant\nadmitToken 전달"]
    --> VERIFY["POST /admit-tokens/:admitToken/verify\nTenant → Platform\n유효성 확인만 (상태 변경 없음)"]

    VERIFY --> VK{"admit-token-by-admit:{admitToken}\n유효?"}
    VK -->|"만료 or 무효"| VDB["DB Fallback 시도\nSELECT WHERE admit_token=:admitToken\nAND status=ADMIT_ISSUED\nAND issued_at > NOW()-60s"]
    VDB -->|"없음"| E404(["404 TK_002_INVALID_ADMIT_TOKEN"])
    VDB -->|"있음"| VFLAG["SET verified-token:{tokenId} EX 60\n중복 입장 방지 플래그"]
    VK -->|"유효"| VFLAG
    --> VRESP(["200 OK\n{ valid: true, userId }"])

    VRESP --> ALLOW["Tenant → 유저 입장 허용"]
    --> COMPLETE["POST /tokens/:token/complete\n{ admitToken }\nTenant → Platform\n입장 완료 통보"]

    COMPLETE --> CK{"ADMIT_ISSUED(1)\n상태?"}
    CK -->|"아님"| E409(["409 QE_006_INVALID_STATUS"])
    CK -->|"확인"| DB["DB status = COMPLETED(2)\n← 먼저 (원자성 전략)\nDB UPDATE WHERE status=1 → 1번만 성공"]
    --> ZREM["Redis ZREM\nDEL admit-token-by-token\nDEL admit-token-by-admit\nDEL token-info 캐시\nDEL verified-token\n← 나중"]
    --> AVG["avgWaitingTime 직접 갱신\nwaitingSeconds = completedAt - issuedAt\n이상치 필터: > waitingTtl × 0.8 제외\nHINCRBYFLOAT queue-stats waitingTimeSum\nHINCRBY queue-stats waitingTimeCount"]
    --> COK(["200 OK\n{ status: COMPLETED, completedAt }"])

    DB -->|"ZREM 실패 시"| FIX["Batch 10초 내\nZREM 재실행 (멱등)"]

    TOKEN -->|"admitToken TTL 60초 초과\nBatch 10초 주기 감지"| BACK["WAITING 복귀\nDB SELECT seq\nRedis ZADD {seq} {tokenId}\nDB UPDATE WAITING(0)\nDEL token-info 캐시\nDEL admit-token-by-token\nDEL admit-token-by-admit"]
```

> **Kafka Consumer 장애 시**
> Consumer Offset 미커밋 → 재시작 시 미처리 메시지부터 재처리
> DB admit_requests PENDING 확인으로 멱등성 보장

---

## 이탈 → CANCELLED

```mermaid
flowchart TD
    DQ(["DELETE /queues/:queueId/tokens/:token\nTenant 서버 호출\n유저 대기 포기"])
    --> CHK["상태 확인"]

    CHK -->|"ADMIT_ISSUED(1)"| E409A(["409 QE_006_INVALID_STATUS\n입장토큰 발급 후 이탈 불가\nadmitToken TTL 60초 후\nWAITING 복귀 후 이탈 가능"])
    CHK -->|"WAITING 아님\n(COMPLETED/EXPIRED/CANCELLED)"| E409B(["409 QE_006_INVALID_STATUS"])
    CHK -->|"WAITING(0)"| ZREM["Redis ZREM\n뒤 순위 자동 당겨짐"]
    --> DB["DB status = CANCELLED(3)\ncancelledAt 기록"]
    --> DEL["DEL queue-user 역인덱스\nDEL token-info 캐시\n같은 userId 재Enqueue 가능 (맨 뒤)"]
    --> OK(["200 OK\n{ status: CANCELLED, cancelledAt }"])
```

---

## TTL 만료 Batch (10초 주기)

```mermaid
flowchart TD
    JOB(["TokenExpiryJob\n10초 주기"])
    --> ALIST["활성 큐 목록 조회\nACTIVE · PAUSED · DRAINING\n큐별 병렬 처리 (동시 10개 / 8초 타임아웃)\nbatch-lock:{t}:{q} NX EX 15 → 분산 처리"]

    ALIST --> W1 & W2 & W3

    W1["waitingTtl 체크 (WAITING)\nZRANGEBYSCORE\n0 ~ now_ms - waitingTtl_ms"]
    W2["inactiveTtl 체크 (WAITING)\nEXISTS token-last-active\n= 0 이면 비활동"]
    W3["admitToken TTL 체크 (ADMIT_ISSUED)\nEXISTS admit-token-by-token:{tokenId}\n= 0 이면 만료"]

    W1 -->|"WAITING_TTL(0)"| EXP["DB UPDATE EXPIRED(4)\nexpiredReason 기록\nRedis ZREM\nDEL token-info 캐시\n100건씩 순차 처리\nLIMIT 100 → Gap Lock 방지"]
    W2 -->|"INACTIVE_TTL(1)"| EXP

    W3 -->|"ADMIT_TOKEN_TTL(2)"| BACK["WAITING 복귀\nDB SELECT seq\nRedis ZADD {seq} {tokenId}\nDB UPDATE WAITING(0)\nDEL token-info 캐시\nDEL admit-token-by-token\nDEL admit-token-by-admit"]

    EXP --> DONE(["완료\n멱등: 상태 필터로 중복 처리 없음"])
    BACK --> DONE
```

---

## 슬라이스 구조 — 전체 순위 보장

```mermaid
flowchart LR
    subgraph GLOBAL["글로벌 순번 (Bulk)"]
        SEQ["global-seq:{t}:{q}\nINCRBY 500 → 1~500 블록 채번"]
    end

    subgraph SLICES["슬라이스 (maxCapacity=300,000 → 3개)\nslice = (seq-1) % sliceCount (라운드로빈)"]
        S0["queue:{t}:{q}:0\nseq 1,4,7,10..."]
        S1["queue:{t}:{q}:1\nseq 2,5,8,11..."]
        S2["queue:{t}:{q}:2\nseq 3,6,9,12..."]
    end

    subgraph RANK["전체 순위 계산 (내 seq=5)"]
        R["ZCOUNT slice:0 0~4 = 1\nZCOUNT slice:1 0~4 = 2\nZCOUNT slice:2 0~4 = 1\n합산 + 1 = 5등"]
    end

    subgraph COUNT["현재 인원 조회 (queue-count 제거)"]
        C["Pipeline ZCARD slice:0\nPipeline ZCARD slice:1\nPipeline ZCARD slice:2\n합산 = 현재 총 인원"]
    end

    subgraph DEQUEUE["Admit Dequeue (N명)"]
        D["슬라이스별 ZRANGE WITHSCORES\nLua 내부 score 정렬\n상위 N명 선택\nZREM multi-member"]
    end

    SEQ --> S0
    SEQ --> S1
    SEQ --> S2
    S0 --> R
    S1 --> R
    S2 --> R
    S0 --> C
    S1 --> C
    S2 --> C
    S0 --> D
    S1 --> D
    S2 --> D
```

---

## Kafka Topic 흐름

```mermaid
flowchart LR
    subgraph API["Queue Platform API"]
        E1["Enqueue 처리\nRedis ZADD 완료"]
        E2["admit 처리\nDB INSERT PENDING"]
    end

    subgraph TOPICS["Kafka"]
        T1["enqueue-events\n{ tokenId, queueId, tenantId\nuserId, seq, issuedAt }"]
        T2["enqueue-admit\n{ requestId, tenantId\nqueueId, count }"]
    end

    subgraph BATCH["Batch Server (Consumer)"]
        C1["EnqueueConsumer\n1000건씩 buffer\nMySQL Bulk INSERT\nRedis sync_needed=0"]
        C2["AdmitConsumer\nDB PENDING 확인\nZREM + admitToken 발급\nDB COMPLETED"]
    end

    E1 -->|"produce"| T1
    E2 -->|"produce"| T2
    T1 -->|"consume"| C1
    T2 -->|"consume"| C2
```

---

## 클라이언트 Polling 구조 (JS SDK + REST 직접 호출)

```mermaid
flowchart TD
    TENANT["Tenant 서버\n(REST 직접 호출)\nPOST /tokens → 대기토큰 발급\ntoken, queueId → 유저에게 전달"]
    --> CLIENT["브라우저 (JS SDK)\nqueue.startPolling()"]

    CLIENT --> POLL["JS SDK 내부\npoll() 실행"]
    --> REQ["GET /tokens/:token\nPlatform 직접 호출\n(API Key 없음 — 대기토큰 인증)"]
    --> PLATFORM["Queue Platform\n순위계산 + TTL갱신 + ETA\nnextPollAfterSec 계산"]
    --> RESP["응답\n{ globalRank, nextPollAfterSec, ready, admitToken }"]

    RESP --> READY{"ready?"}
    READY -->|"false"| TIMER["JS SDK\nsetTimeout(nextPollAfterSec × 1000)\n→ poll() 재호출\n탭 비활성화 시 자동 중단"]
    TIMER --> POLL

    READY -->|"true"| CB["onReady 콜백\nadmitToken 수신"]
    --> SEND["유저 → Tenant 서버: admitToken 전달"]
    --> TENANTCALL["Tenant 서버 (REST 직접 호출)\n① POST /verify 즉시 호출\n② valid 확인\n③ Tenant 내부 처리 (세션 등)\n④ POST /complete (3회 재시도)"]
```

> **역할 분리**
> nextPollAfterSec 계산: Platform 책임
> setTimeout / 탭 비활성화 처리: JS SDK 책임
> UI 업데이트: 클라이언트(Tenant) 책임
> verify 순서 강제 / complete 재시도: Tenant 서버 구현 책임 (OpenAPI 가이드)

---

## Tenant 서버 통신 vs Platform 직접 통신

| 통신 대상 | 시점 | 빈도 | 내용 |
|----------|------|------|------|
| Tenant 서버 | 진입 시 | 1회 | 슬롯 여유 확인, 대기토큰 수신 |
| Tenant 서버 | 입장 시 | 1회 | admitToken 전달, 세션 생성 |
| Tenant 서버 | 이탈 시 | 1회 | 취소 요청 |
| Platform (JS SDK) | 대기 중 | 2~30초마다 반복 | Polling (가장 빈번) |

> Polling이 가장 빈번한 통신인데 JS SDK가 Platform과 직접 처리.
> Tenant 서버는 진입/입장/이탈 3번만 관여 (REST 직접 호출).
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

    A & B & C --> REDIS["Redis\nglobal-seq INCRBY 원자\n→ seq 중복 없음"]
    A & B & C --> KAFKA["Kafka\nenqueue-events\nenqueue-admit"]
    A & B & C --> MYSQL["MySQL\nJPA + Virtual Thread"]
```

> **순서 보장**: global-seq INCRBY = Redis 싱글스레드 원자 연산
> 서버 여러 대가 동시 호출해도 seq 절대 중복 없음
> Tenant는 Load Balancer 주소만 알면 됨 (내부 서버 수 몰라도 됨)
