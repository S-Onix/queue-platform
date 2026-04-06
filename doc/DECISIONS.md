# Queue Platform — 설계 결정 문서

> FRS v1.4 기준 | Entity 설계 / 보안 / 복구 전략 / 아키텍처

---

## 1. 기술 스택 결정 — WebFlux + R2DBC 유지

### 논의 배경
- R2DBC 레퍼런스 부족 (JOIN 쿼리, 트랜잭션, 연관관계 매핑)
- Virtual Thread + JPA 전환 검토

### 결정: WebFlux + R2DBC 유지

| 항목 | 내용 |
|------|------|
| API 서버 | Spring WebFlux + R2DBC 유지 |
| xxxxxxxxxx flowchart LR    subgraph GLOBAL["글로벌 순번 (Bulk)"]        SEQ["global-seq:{t}:{q}        INBYN 500 → 1~500 블록 채번"]    end​    subgraph SLICES["슬라이스    slice = (seq-1) % sliceCount (라운드로빈)"]        S0["queue:{t}:{q}:0        seq 1,4,7,10..."]        S1["queue:{t}:{q}:1        seq 2,5,8,11..."]        S2["queue:{t}:{q}:2        seq 3,6,9,12..."]    end​    subgraph RANK["전체 순위 계산 (내 seq=5)"]        R["ZCOUNT slice:0 0~4 = 1        ZCOUNT slice:1 0~4 = 2        ZCOUNT slice:2 0~4 = 1        합산 + 1 = 5등"]    end​    subgraph DEQUEUE["Admit Dequeue (N명)"]        D["슬라이스별 ZRANGE WITHSCORES        Lua 내부 score 정렬        상위 N명 선택        ZREM multi-member"]    end​    SEQ --> S0    SEQ --> S1    SEQ --> S2    S0 --> R    S1 --> R    S2 --> R    S0 --> D    S1 --> D    S2 --> Dmermaid#mermaidChart251{font-family:sans-serif;font-size:16px;fill:#333;}@keyframes edge-animation-frame{from{stroke-dashoffset:0;}}@keyframes dash{to{stroke-dashoffset:0;}}#mermaidChart251 .edge-animation-slow{stroke-dasharray:9,5!important;stroke-dashoffset:900;animation:dash 50s linear infinite;stroke-linecap:round;}#mermaidChart251 .edge-animation-fast{stroke-dasharray:9,5!important;stroke-dashoffset:900;animation:dash 20s linear infinite;stroke-linecap:round;}#mermaidChart251 .error-icon{fill:#552222;}#mermaidChart251 .error-text{fill:#552222;stroke:#552222;}#mermaidChart251 .edge-thickness-normal{stroke-width:1px;}#mermaidChart251 .edge-thickness-thick{stroke-width:3.5px;}#mermaidChart251 .edge-pattern-solid{stroke-dasharray:0;}#mermaidChart251 .edge-thickness-invisible{stroke-width:0;fill:none;}#mermaidChart251 .edge-pattern-dashed{stroke-dasharray:3;}#mermaidChart251 .edge-pattern-dotted{stroke-dasharray:2;}#mermaidChart251 .marker{fill:#333333;stroke:#333333;}#mermaidChart251 .marker.cross{stroke:#333333;}#mermaidChart251 svg{font-family:sans-serif;font-size:16px;}#mermaidChart251 p{margin:0;}#mermaidChart251 .label{font-family:sans-serif;color:#333;}#mermaidChart251 .cluster-label text{fill:#333;}#mermaidChart251 .cluster-label span{color:#333;}#mermaidChart251 .cluster-label span p{background-color:transparent;}#mermaidChart251 .label text,#mermaidChart251 span{fill:#333;color:#333;}#mermaidChart251 .node rect,#mermaidChart251 .node circle,#mermaidChart251 .node ellipse,#mermaidChart251 .node polygon,#mermaidChart251 .node path{fill:#ECECFF;stroke:#9370DB;stroke-width:1px;}#mermaidChart251 .rough-node .label text,#mermaidChart251 .node .label text,#mermaidChart251 .image-shape .label,#mermaidChart251 .icon-shape .label{text-anchor:middle;}#mermaidChart251 .node .katex path{fill:#000;stroke:#000;stroke-width:1px;}#mermaidChart251 .rough-node .label,#mermaidChart251 .node .label,#mermaidChart251 .image-shape .label,#mermaidChart251 .icon-shape .label{text-align:center;}#mermaidChart251 .node.clickable{cursor:pointer;}#mermaidChart251 .root .anchor path{fill:#333333!important;stroke-width:0;stroke:#333333;}#mermaidChart251 .arrowheadPath{fill:#333333;}#mermaidChart251 .edgePath .path{stroke:#333333;stroke-width:2.0px;}#mermaidChart251 .flowchart-link{stroke:#333333;fill:none;}#mermaidChart251 .edgeLabel{background-color:rgba(232,232,232, 0.8);text-align:center;}#mermaidChart251 .edgeLabel p{background-color:rgba(232,232,232, 0.8);}#mermaidChart251 .edgeLabel rect{opacity:0.5;background-color:rgba(232,232,232, 0.8);fill:rgba(232,232,232, 0.8);}#mermaidChart251 .labelBkg{background-color:rgba(232, 232, 232, 0.5);}#mermaidChart251 .cluster rect{fill:#ffffde;stroke:#aaaa33;stroke-width:1px;}#mermaidChart251 .cluster text{fill:#333;}#mermaidChart251 .cluster span{color:#333;}#mermaidChart251 div.mermaidTooltip{position:absolute;text-align:center;max-width:200px;padding:2px;font-family:sans-serif;font-size:12px;background:hsl(80, 100%, 96.2745098039%);border:1px solid #aaaa33;border-radius:2px;pointer-events:none;z-index:100;}#mermaidChart251 .flowchartTitleText{text-anchor:middle;font-size:18px;fill:#333;}#mermaidChart251 rect.text{fill:none;stroke-width:0;}#mermaidChart251 .icon-shape,#mermaidChart251 .image-shape{background-color:rgba(232,232,232, 0.8);text-align:center;}#mermaidChart251 .icon-shape p,#mermaidChart251 .image-shape p{background-color:rgba(232,232,232, 0.8);padding:2px;}#mermaidChart251 .icon-shape rect,#mermaidChart251 .image-shape rect{opacity:0.5;background-color:rgba(232,232,232, 0.8);fill:rgba(232,232,232, 0.8);}#mermaidChart251 .label-icon{display:inline-block;height:1em;overflow:visible;vertical-align:-0.125em;}#mermaidChart251 .node .label-icon path{fill:currentColor;stroke:revert;stroke-width:revert;}#mermaidChart251 :root{--mermaid-alt-font-family:sans-serif;}Admit Dequeue (N명)전체 순위 계산 (내 seq=5)슬라이스slice = (seq-1) % sliceCount (라운드로빈)글로벌 순번 (Bulk)global-seq:{t}:{q}INBYN 500 → 1~500 블록 채번queue:{t}:{q}:0seq 1,4,7,10...queue:{t}:{q}:1seq 2,5,8,11...queue:{t}:{q}:2seq 3,6,9,12...ZCOUNT slice:0 0Unsupported markdown: del4 = 2ZCOUNT slice:2 0~4 = 1합산 + 1 = 5등슬라이스별 ZRANGE WITHSCORESLua 내부 score 정렬상위 N명 선택ZREM multi-member | Spring MVC (JPA 검토 가능) |
| 이유 | Polling 10,000명 → 2,000 rps. Event loop non-blocking이 핵심 어필 포인트 |

### Virtual Thread 전환 시 문제
```
WebFlux 버리면 "왜 Virtual Thread?" 설명이 약해짐
"수만 명 동시 Polling" 포트폴리오 정체성 상실
```

### R2DBC 어려운 부분 해결 방향

| 문제 | 해결 |
|------|------|
| 연관관계 매핑 | ID 참조만. 조인 필요 시 별도 쿼리 조립 |
| 복잡한 JOIN | `@Query` 네이티브 SQL 또는 `DatabaseClient` |
| 트랜잭션 | `ReactiveTransactionManager` 사용 |

---

## 2. ID 전략 — 이중 ID 분리

### 결정

| 필드 | 타입 | 역할 | 사용 위치 |
|------|------|------|----------|
| `id` | `Long` | DB 내부 PK — 조인/FK | DB 내부에서만 |
| `tenantId` | `String` | 외부 식별자 | API 응답, Redis Key |

### 이유
```
Long PK 외부 노출 시:
  GET /tenants/1 → 몇 번째 가입자인지 추측 가능
  GET /tenants/2 → enumeration 공격 가능

String 랜덤 ID:
  "t_abc123" → 순서 추측 불가, 내부 구조 은닉
```

### Redis Key에 외부 식별자 사용
```
✅ queue:t_abc123:q_xyz789:0   (String 외부 식별자)
❌ queue:42:17:0               (Long PK — DB 구조 노출)
```
Long PK 사용 시 DB 마이그레이션 때 Redis Key 구조도 변경 필요 → 강결합

---

## 3. DATETIME(3) 전체 적용

### 결정
모든 timestamp 컬럼을 `DATETIME(3)` (밀리초 단위) 으로 통일

### 이유 — Redis 장애 복구 정확도

```
초 단위 DATETIME:
  200 rps → 1초에 200명 유입 → score 동일 → 순서 보장 불가

밀리초 단위 DATETIME(3):
  200 rps → 1ms에 0.2명 유입 → 충돌 확률 ≈ 0.2명/ms
  → FIFO 사실상 완전 복구
```

### 복구 코드
```java
// issuedAt → Sorted Set score
double score = token.issuedAt()
    .toInstant(ZoneOffset.UTC)
    .toEpochMilli();
```

### 동점자 처리
극소수 동점자 → tokenId lexicographic 순 → 대기열 서비스 특성상 허용 범위

---

## 4. API Key 설계

### 역할
서버 간 통신 인증 — "이 요청이 인증된 Tenant 서버에서 온 것"을 증명

### 인증 주체별 분리

| 호출 주체 | 인증 수단 | 이유 |
|----------|----------|------|
| Tenant 서버 | X-API-Key | 서버 간 통신 — 장기 자격증명 |
| 유저 | token | Polling 전용 — API Key 불필요 |
| Tenant 개발자 | JWT | 관리 콘솔 — 단기 인증 |

### 보안 3중 레이어

| 레이어 | 방법 | 효과 |
|--------|------|------|
| 전송 보안 | HTTPS | 헤더 암호화, 중간 노출 없음 |
| 저장 보안 | SHA-256 hash만 저장 | DB 털려도 원본 역산 불가 |
| 남용 방지 | per-key 100 rps | 탈취 시 피해 범위 제한 |

### SHA-256 선택 이유 (BCrypt 아닌)

| | 비밀번호 | API Key |
|---|---|---|
| 입력 | 사람이 타이핑 → 예측 가능 | 랜덤 256bit → 엔트로피 충분 |
| 해시 속도 | 느려야 함 (brute force 방어) | 빨라야 함 (요청마다 계산) |
| 알고리즘 | BCrypt | SHA-256 |

### 분실 처리
```
SHA-256은 단방향 — 복호화 불가
→ 원본 복구 불가능
→ Revoke 후 재발급이 유일한 방법
→ Tenant 계정(tenantId)에는 영향 없음
  (API Key는 tenantId에 종속된 자격증명)

Stripe, GitHub, AWS 동일 방식:
  "이 키는 생성 시 한 번만 표시됩니다"
```

### DB 테이블이 필요한 이유

| 기능 | Redis만으로 가능? | 이유 |
|------|-----------------|------|
| 인증 속도 | ✅ (캐시) | 60s TTL, DB QPS ≈ 0 |
| Revoke | ❌ | 취소할 대상 목록이 없음 |
| 목록 조회 | ❌ | Redis는 단건 조회만 가능 |
| 장애 복구 | ❌ | Redis 재시작 시 소멸 |

→ Redis는 캐시(속도), DB는 원본(관리)

### 이상 트래픽 감지
```
Rate limit(100 rps) → 1차 차단
모니터링 → Prometheus + Grafana 운영 레이어
  http_requests_total{status="429"} 메트릭 수집
  (별도 감지 로직 구현은 포트폴리오 범위 초과)
```

---

## 5. Redis 장애 복구 전략

### 복구 가능 항목

| 항목 | 복구 여부 | 방법 |
|------|----------|------|
| WAITING 토큰 목록 | ✅ 완전 복구 | DB tokens WHERE status='WAITING' |
| 큐 설정 | ✅ 완전 복구 | DB queues 테이블 |
| 대기열 순서 | ✅ 사실상 완전 | issued_at.toEpochMilli() → score |
| userId 역인덱스 | ✅ 재구성 가능 | WAITING 토큰에서 재구성 |
| global-seq | ⚠️ 근사 복구 | 최대 score를 seq로 설정 |
| 비활동 TTL | ❌ 복구 불가 | 전원 inactiveTtl 리셋 |
| avgWaitingTime | ❌ 복구 불가 | ETA null 반환 |

### 복구 순서
```
1단계: queue-meta Hash 재구성 (큐 설정)
2단계: WAITING 토큰 → Sorted Set 재구성 (issued_at 밀리초 → score)
3단계: global-seq 재구성 (최대 score)
4단계: queue-user 역인덱스 재구성 (userId → tokenId)
```

### 면접 답변
> "Redis 장애 시 DB를 원본으로 대기열을 재구성합니다.
> DATETIME(3) 밀리초 단위로 issued_at을 저장하므로
> 200 rps 기준 1ms 내 충돌 확률이 0.2명 수준으로
> FIFO를 사실상 완전 복구할 수 있습니다.
> 비활동 TTL과 avgWaitingTime은 복구 불가하며
> 각각 inactiveTtl 리셋, ETA null로 처리합니다."

---

## 6. tenantId 비정규화 (tokens 테이블)

### 결정
`tokens` 테이블에 `tenant_id` 컬럼 추가 (queues 테이블의 tenant_id 복사)

### 이유
```
비정규화 전: Batch TTL 탐색 시
  tokens JOIN queues ON tokens.queue_id = queues.queue_id
  WHERE queues.tenant_id = ?

비정규화 후:
  tokens WHERE tenant_id = ? AND status = 'WAITING'
  → 조인 제거, 인덱스 단순화
```

Batch가 30초마다 전체 WAITING 토큰을 탐색하는 구조에서 조인 비용 제거가 중요

---

## 7. 인덱스 설계 근거

| 인덱스 | 대상 쿼리 |
|--------|----------|
| `token_id + status` | Polling 인증 — 가장 빈번 (2,000 rps) |
| `queue_id + status + issued_at` | Batch TTL 만료 탐색 (30초 주기) |
| `queue_id + user_id + status` | userId 중복 체크 보조 |
| `key_hash` (unique) | API Key 인증 DB fallback |
| `tenant_id + name` (unique) | Tenant 내 큐 이름 중복 방지 |
| `tenant_id + status` (queues) | 활성 큐 목록 조회 |

---

## 8. Rich Domain Model + Hexagonal Architecture

### 결정
```
Service → 얇게 (흐름 조합만)
Domain Entity → 두껍게 (비즈니스 규칙 집중)
```

### Anemic vs Rich Domain 비교

```java
// ❌ Anemic — Service가 모든 걸 판단
public Mono<AdmitResult> admit(AdmitCommand command) {
    return tokenRepository.findByTokenId(command.token())
        .flatMap(token -> {
            if (token.getStatus() != WAITING) {  // 판단이 Service에
                return Mono.error(new QueueException(INVALID_STATUS));
            }
            token.setStatus(COMPLETED);          // 상태 변경이 Service에
            token.setCompletedAt(LocalDateTime.now());
            return tokenRepository.save(token);
        });
}

// ✅ Rich Domain — Service는 흐름만
public Mono<AdmitResult> admit(AdmitCommand command) {
    return tokenRepository.findByTokenId(command.token())
        .flatMap(token ->
            queueRedis.getGlobalRank(token)
                .map(token::admit)       // 판단 + 상태 변경이 Token 안에
                .flatMap(tokenRepository::save)
        )
        .map(AdmitResult::from);
}
```

### 도메인 Entity 책임

| Entity | 메서드 | 역할 |
|--------|--------|------|
| `Token` | `admit()` | WAITING 확인 + COMPLETED 전환 |
| `Token` | `cancel()` | WAITING 확인 + CANCELLED 전환 |
| `Token` | `expire(reason)` | WAITING 확인 + EXPIRED 전환 |
| `Token` | `isAdmittable(rank)` | rank=1 + WAITING 판단 |
| `Token` | `waitingSeconds()` | issuedAt ~ completedAt 계산 |
| `Queue` | `isEnqueueable()` | ACTIVE 상태 판단 |
| `Queue` | `isCapacityExceeded(count)` | maxCapacity 초과 판단 |
| `Queue` | `assignSlice(seq)` | seq % sliceCount 계산 |
| `ApiKey` | `isActive()` | ACTIVE 상태 판단 |
| `ApiKey` | `revoke()` | REVOKED 전환 |

### 패키지 구조 (queue-domain)

```
queue-domain
├── domain/
│   ├── Token.java          ← 핵심 도메인. 비즈니스 규칙 집중
│   ├── Queue.java
│   ├── Tenant.java
│   └── ApiKey.java
├── service/                ← 얇음. 흐름 조합만 (5~10줄 수준)
│   ├── AdmitService.java
│   ├── EnqueueService.java
│   └── PollingService.java
├── port/
│   ├── in/                 ← UseCase 인터페이스 (Adapter In이 호출)
│   │   ├── AdmitUseCase.java
│   │   ├── EnqueueUseCase.java
│   │   └── PollingUseCase.java
│   └── out/                ← Repository 인터페이스 (Domain이 정의, Infrastructure가 구현)
│       ├── TokenRepositoryPort.java
│       ├── QueueRepositoryPort.java
│       └── QueueRedisPort.java
└── exception/
    ├── QueueException.java
    └── ErrorCode.java
```

### 의존성 방향 원칙

```
Controller → UseCase(interface) ← Service → Port(interface) ← Adapter
    (api)      (domain/in)       (domain)    (domain/out)    (infrastructure)

핵심: 모든 화살표가 Domain을 향함
      Infrastructure가 Domain을 의존 (역방향 절대 금지)
      queue-domain은 Spring 의존 금지 (build.gradle에서 exclude)
```

### Gradle 모듈 의존성

```groovy
// queue-api
dependencies {
    implementation(project(":queue-domain"))
    implementation(project(":queue-infrastructure"))
}

// queue-infrastructure
dependencies {
    implementation(project(":queue-domain")) // Port 인터페이스 구현
}

// queue-domain
dependencies {
    implementation(project(":queue-common")) // Spring 의존 없음
}
```

---

## 9. Admit = Dequeue + 통계 갱신

### 결정
별도 Dequeue API 없음. Admit 한 번에 세 가지 처리.

```
Admit ──┬──▶ DB COMPLETED  (상태 확정 — 먼저)
        ├──▶ Redis ZREM    (Dequeue — 나중)
        └──▶ avgWaitingTime 갱신 (통계 — 마지막)
```

### 순서가 중요한 이유

```
ZREM 먼저 → DB 실패:
  대기열에서 제거됐는데 WAITING 상태 → 유저 영원히 대기 ❌

DB 먼저 → ZREM 실패:
  COMPLETED 기록됨 → Batch 30초 내 ZREM 재실행 (멱등) ✅
  Tenant 입장: 유저 이미 입장 허용 → 서비스 이용 중 → 피해 없음
  Platform 입장: Sorted Set에 잔류 → 30초 내 정리

avgWaitingTime 마지막인 이유:
  Admit 확정 후에야 정확한 대기시간(issuedAt ~ completedAt) 계산 가능
  다음 유저 ETA 계산에 사용 → 실제 Admit 데이터만 반영해야 정확
```

### ZREM 실패 시 잔류 상황

```
다음 유저가 rank=1 수신 → Tenant에 입장 요청
→ Tenant: GET /tokens/:token/status
  → status = COMPLETED (이미 입장됨) → ready: false
  → 스킵 후 다음 슬롯 대기
→ Batch 30초 내 ZREM 재실행 → 정리 완료

실제 피해: 없음
DB가 진실의 원본 (Source of Truth)
Redis 잔류 = 단순 정리 지연 (최대 30초)
```

---

## 10. rank=1 중복 불가 보장

### 정상 흐름
```
Sorted Set score 가장 낮은 1명 = rank 1
ZREM으로 제거 → 다음 score가 자동으로 rank 1
→ 항상 1명만 rank=1
```

### 동시 Admit 방어
```
두 명이 동시에 같은 token으로 Admit 호출
→ DB UPDATE WHERE status='WAITING' (1번만 성공)
→ 먼저 성공한 쪽만 COMPLETED
→ 나머지 → 409 QE_008_NOT_READY
```

### 면접 포인트
> "정상 흐름에서 rank=1은 항상 1명입니다.
> ZREM 실패 시 일시적 잔류가 가능하지만
> Status 확인 API로 COMPLETED 여부를 사전 검증하고
> Batch 30초 내 자동 정리되므로 실제 피해는 없습니다."

---

## 11. 재입장 재시도 로직

### 결정
Platform 관여 없음. Tenant ↔ 유저 클라이언트 사이의 문제.

```
Platform 역할: globalRank=1 → ready:true 반환 (끝)
Tenant 역할:   슬롯 여유 판단 → 없으면 유저에게 "대기" 응답
유저 클라이언트: Tenant 응답 보고 재시도
```

### Tenant 구현 선택지

| 방법 | 장점 | 단점 |
|------|------|------|
| 클라이언트 Polling (10초 간격) | 구현 단순, Platform 변경 없음 | Tenant 서버 부하 |
| SSE (Tenant → 유저 푸시) | 슬롯 생기면 즉시 입장, UX 좋음 | Tenant 구현 복잡 |
| Webhook | Tenant 완전 제어 | 유저 측 엔드포인트 필요 |

### 설계 원칙과의 일치
```
Platform이 슬롯 여유를 판단하면:
  → Platform이 Tenant 내부 구조에 의존 → 커플링 ❌

Tenant가 판단하고 Platform에 Admit 호출:
  → Platform은 순서만 관리 ✅
  → 설계 원칙 "Platform은 순서만 관리" 준수
```

---

## 12. Admit 방식 전면 변경

### 기존 방식
```
유저 rank=1 → 유저가 Tenant에 "ready" 알림
Tenant → Platform POST /tokens/:token/admit (1명씩)
→ COMPLETED
```

### 변경 방식
```
Tenant → Platform POST /queues/:queueId/admit { count: N }
Platform → 앞 N명 입장 토큰(admitToken) 발급 (TTL 30초)
유저 → Polling으로 admitToken 수신
유저 → Tenant에 admitToken 전달
Tenant → Platform POST /admit-tokens/:admitToken/verify
→ COMPLETED
```

### 변경 이유
```
기존: 유저가 직접 "나 1등이에요" 알림 → 1명씩 처리
변경: Tenant가 처리 가능한 인원만큼 Pull → N명 한번에

Backpressure 패턴 적용:
  Publisher  = 대기열
  Subscriber = Tenant (request(N) = admit { count: N })
  → Tenant가 소화 가능한 만큼만 요청 → 과부하 방지
```

---

## 13. 입장 토큰(admitToken) 설계

### TTL = 30초

```
근거:
  Polling 주기(5초) + 네트워크(1~2초) + 유저 행동(3~5초) + 여유 ≈ 20초
  → 30초 (여유분 포함)

최대 1000명 기준:
  Tenant가 1000명 발급 → 각 유저에게 전달 → 30초 안에 verify
  → 충분한 시간
```

### 만료 시 우선순위 유지

```
admitToken TTL 30초 초과
  → WAITING 복귀
  → seq(Sorted Set score) 그대로 유지
  → 다음 admit 호출 시 앞순서면 재발급

이유:
  유저 귀책 아닌 네트워크 지연으로 만료 가능
  순위 박탈은 UX상 불합리
  우선순위 유지 → 자연스러운 재시도
```

### Redis Key
```
admit-token:{admitToken}  String  TTL 30s
  value: tokenId
  → verify 시 조회 → tokenId 매핑
```

---

## 14. admit 요청 순서 보장 — Redis List 큐잉

### 문제
```
Tenant가 "100명" 처리 중에 "1000명" 추가 요청
→ 순서 보장 필요
→ 이전 완료 후 다음 처리 필요
```

### 해결: Redis List + BLPOP 워커

```
Tenant 요청 → RPUSH admit-request-queue:{tenantId}:{queueId}
워커 → BLPOP (완료 후 다음 꺼냄)
→ 이전 요청 완료 후 다음 요청 처리 보장
```

### 멀티 서버 환경
```
서버 A, B 둘 다 BLPOP 대기
→ Redis BLPOP은 하나의 서버에만 전달
→ 자동으로 단일 처리 보장
→ 별도 락 불필요
```

---

## 15. Token 상태 추가 — ADMIT_ISSUED

### 변경
```
기존: WAITING → COMPLETED
변경: WAITING → ADMIT_ISSUED → COMPLETED
              → WAITING (admitToken 만료 시, 순위 유지)
```

### expiredReason 추가
```
기존: WAITING_TTL / INACTIVE_TTL
추가: ADMIT_TOKEN_TTL (ADMIT_ISSUED 상태에서 30초 초과)
```

### 면접 포인트
> "admitToken 만료 시 EXPIRED가 아닌 WAITING으로 복귀하는 이유는
> 네트워크 지연 등 유저 귀책이 아닌 사유로 만료될 수 있기 때문입니다.
> seq를 유지함으로써 우선순위를 보존하고
> 다음 admit 호출 시 자동으로 재발급됩니다."

---

## 16. 대용량 처리 — DB

### INSERT (Enqueue)
```
묶음 크기: 100건
재시도: 3회 (backoff 100ms → 최대 1초)
최종 실패: Redis List insert-retry-queue 보관 → Batch 재처리

병목 확인 방법:
  SHOW PROCESSLIST
  SHOW STATUS LIKE 'Threads_connected'
  Prometheus: r2dbc_pool_pending_connections

Bulk INSERT:
  INSERT INTO tokens VALUES (tok1,...),(tok2,...),...(tok100,...)
  → DB 왕복 횟수 1/100로 감소
```

### SELECT (Polling) — Read/Write 분리
```
10만명 × 5초 간격 = 20,000 rps → DB 한계 초과

해결:
  Read Replica → Polling SELECT
  Master       → INSERT/UPDATE

token Redis 캐싱:
  SET token-info:{tokenId} {status, queueId} EX 5
  상태 변경 시 즉시 갱신 (TTL 기다리지 않음)
  → DB QPS ≈ 0 (캐시 히트 시)

캐시 TTL = 5초
  근거: Polling 주기 = 5초 → 다음 Polling에서 최신 반영
```

### UPDATE (Admit / Batch)
```
청크 크기: 100건
청크 간 대기: 10ms (서비스 쿼리에 DB 양보)
순서: 순차 처리 (concurrency=1)

Batch UPDATE:
  Flux.fromIterable(tokenIds)
      .buffer(100)
      .delayElements(Duration.ofMillis(10))
      .flatMap(chunk -> bulkUpdate(chunk), 1)
```

---

## 17. 대용량 처리 — Redis

### Enqueue Bulk — 500건 묶음
```
Adaptive Batching:
  대기 요청 < 100건  → 즉시 처리
  100~500건          → 100건 묶음
  > 500건            → 500건 묶음

Lua Script:
  INCRBY N → seq 블록 한번에 채번
  슬라이스별 ZADD multi-member → ZADD 횟수 = 슬라이스 수로 고정

문제 및 해결:
  INCRBY + ZADD 실패 → Lua 원자적 → 전체 실패 → 구멍 없음
  멀티 서버 INCRBY 충돌 → INCRBY 원자 연산 → 겹치지 않음
  Lua 블로킹 → Adaptive batching으로 크기 조절
```

### Batch 주기
```
10초 (변경: 30초 → 10초)
  5초 vs 10초:
    5초:  불일치 확률 낮음, Batch 부하 높음
    10초: 불일치 소폭 증가, Batch 부하 낮음
  admit 추가 추출 3회로 보완
```

### Hot Key (global-seq)
```
결정: 허용 범위

근거:
  Redis INCR 처리량 ≈ 100,000 ops/s
  500건 묶음 → INCRBY 400/s → 한계의 0.4%
  10만건 동시 → INCRBY 200/s → 여전히 한계 이하

대규모 확장 시:
  슬라이스별 독립 seq 도입 검토
  단, FIFO 보장 약화 트레이드오프
```

---

## 18. 대용량 처리 — 로직

### 멱등성 — Redis idempotency key
```
Java Queue 방식 기각:
  멀티 서버에서 각자 독립 Queue → 전체 순서 보장 불가
  서버 다운 시 Queue 소멸 → 유실

채택: Redis idempotency key
  SET admit-idem:{requestId} {result} EX 300 NX
  → 이미 처리된 requestId → 저장된 결과 반환
  → 멀티 서버 보장
  → 서버 재시작 후에도 Redis에 남아있음
```

### 비동기 INSERT 유실
```
3회 재시도 (backoff)
최종 실패 → Redis List insert-retry-queue 보관
Batch 서버가 주기적으로 재처리
```

### ADMIT_ISSUED → WAITING 복귀 (seq 복원)
```
문제:
  admitToken TTL 30초 초과 → WAITING 복귀
  Redis ZADD 시 원래 seq(score) 필요
  → seq가 DB에 없으면 복원 불가

해결:
  tokens 테이블에 seq 컬럼 추가 ✅
  Enqueue 시 INCRBY로 받은 seq → DB 저장

복구 흐름:
  Batch: EXISTS admit-token:{tokenId} = 0 감지
  DB SELECT tokens WHERE status = 'ADMIT_ISSUED' AND seq > 0
  → seq 조회
  → Redis ZADD queue:{t}:{q}:{slice} {seq} {tokenId}
  → DB UPDATE status = 'WAITING'
```

---

## 19. 대용량 처리 — 병렬 처리

### Batch 병렬화
```
큐별 독립 처리
동시 처리 큐 수: 10개 (워커 풀 제한)
큐별 타임아웃: 8초 (10초 주기 내 완료)

문제 및 해결:
  워커 수 폭발 → 동시 처리 10개로 제한
  특정 큐 지연 → 8초 타임아웃으로 스킵
  DB 부하 집중 → 청크 간 10ms 대기 유지

Flux.fromIterable(queues)
    .flatMap(queue ->
        processQueue(queue)
            .timeout(Duration.ofSeconds(8))
            .onErrorResume(e -> Mono.empty()),
        10  // 동시 처리 큐 수
    )
```

### admit 워커 병렬화
```
단위: Queue (slice 아님)
  Queue = Tenant가 생성한 대기열
  Slice = Queue 내부 분산 구조

큐별 독립 워커:
  admit-request-queue:{tenantId}:{queueA} → 워커1
  admit-request-queue:{tenantId}:{queueB} → 워커2
  → 다른 큐의 admit 병렬 처리 ✅
  → 같은 큐 내에서는 순차 보장 ✅

워커 풀: 10개
  큐 100개 → 10개 워커가 라운드로빈 처리
```

### Enqueue 병렬 처리
```
concurrency: 50
  근거:
    Redis 싱글스레드 → 동시 처리 수 늘려도 효과 없음
    50개 × Lua 2ms = 100ms 내 처리 → 충분
    500건 이상은 불필요한 메모리 낭비

Flux.fromIterable(requests)
    .buffer(500)
    .flatMap(batch -> processBulkEnqueue(batch), 50)
```

---

## 20. 메모리 압박 해결

```
즉시 적용:
  inactiveTtl 기본값: 1800s → 300s
    5분 무응답 = 사실상 이탈
    Polling 5초 간격 활성 유저는 영향 없음

  Batch 주기: 30초 → 10초
    EXPIRED 토큰 메모리 점유 최소화
    admit 불일치 확률 감소

  Redis maxmemory: 4GB
  maxmemory-policy: noeviction
  Prometheus 모니터링: redis_memory_used_bytes 80% 알림

대규모:
  Redis Cluster (포트폴리오 범위 초과 — 면접 언급용)
  "queueId 기준 샤딩으로 수평 확장"
```

---

## 21. 이탈(CANCELLED) 정책

```
이탈 허용 상태:
  WAITING      → CANCELLED ✅
  ADMIT_ISSUED → 409 QE_006_INVALID_STATUS ❌

이유:
  ADMIT_ISSUED = 이미 입장 토큰 발급
  → Tenant가 슬롯 할당한 상태
  → 유저 실수까지 Platform이 책임 안 함

ADMIT_ISSUED에서 이탈하려면:
  admitToken TTL 30초 대기
  → WAITING 자동 복귀
  → DELETE /tokens/:token → CANCELLED

재접속:
  CANCELLED 후 DEL queue-user 역인덱스
  → 같은 userId로 재Enqueue 가능
  → 새 seq 배정 (맨 뒤)
  → 우선순위 복구 없음 (자발적 이탈 귀책)
```

---

## 22. verify / complete 분리

### 결정
```
기존: verify → COMPLETED + ZREM (한번에)

변경:
  verify  → 유효성 확인만 (상태 변경 없음, ADMIT_ISSUED 유지)
  complete → Tenant가 입장 완료 후 명시적 통보 → COMPLETED + ZREM
```

### 이유
```
Tenant 입장에서:
  verify만으로 COMPLETED 처리되면
  → Tenant가 입장 처리 전에 대기열에서 이미 제거됨
  → 입장 실패 시 복구 불가

분리하면:
  verify → 유효성 확인 (입장 가능 여부만 판단)
  Tenant → 유저 실제 입장 처리
  complete → 입장 완료 확정 후 ZREM

  Tenant가 ZREM 타이밍을 직접 제어
  → 입장 실패 시 admitToken이 아직 유효 → 재시도 가능
```

### complete 처리 순서
```
① admitToken 유효성 재확인
② DB status = COMPLETED (먼저 — 원자성 전략)
③ Redis ZREM + DEL admit-token + DEL token-info (나중)
④ avgWaitingTime 갱신

DB 먼저 이유:
  잔류(Redis에 남음) > 유실(DB 미반영)
  잔류 → Batch 10초 내 정리
  유실 → 복구 불가
```

### 새 API
```
POST /api/v1/tokens/:token/complete
  Body: { admitToken: "at_xxx" }
  → COMPLETED + ZREM + avgWaitingTime 갱신
  ← { status: COMPLETED, completedAt }
```

### 면접 포인트
> "verify와 complete를 분리한 이유는
> Tenant가 입장 완료를 명시적으로 통보하게 함으로써
> ZREM 타이밍을 Tenant가 제어할 수 있도록 하기 위해서입니다.
> verify만으로 ZREM하면 입장 실패 시 복구가 불가능하지만
> complete 분리 시 admitToken이 유효한 동안 재시도가 가능합니다."
