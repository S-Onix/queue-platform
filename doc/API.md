# API 명세

> **정본은 코드다.** 이 문서는 `queue-api`의 컨트롤러·DTO·`SecurityConfig`·`ErrorCode`에서
> 추출한 것이며, 필드명은 **코드 원문 그대로**다. 코드를 바꾸면 여기도 같이 고쳐라.
>
> 계약·순서·흔한 실수는 `doc/TENANT_INTEGRATION.md`를, 설계 근거는 `doc/DECISIONS.md`를 봐라.
> 여기엔 **"무엇을 보내고 무엇이 오는가"만** 둔다.
>
> 🪤 이 문서가 생긴 계기: 2026-08-27 통합테스트에서 스크립트가 두 번 죽었다.
> `admit`의 `requestId`를 몰라 **4,086회 전부 400**, API Key 평문 필드가 `rawKey`인 걸 몰라 401.
> 둘 다 "필드가 무엇인지"만 적혀 있었어도 안 났다.

---

## 0. 공통

### 응답 봉투

**컨트롤러를 탄 응답은 `ApiResponse<T>`로 감싸진다.** 실제 body는 이 형태다.

```json
{
  "data": { ... },
  "timestamp": "2026-08-27T04:06:33.344329328Z",
  "errorResponse": null,
  "success": true
}
```

- 성공: `data`에 아래 표의 응답 필드, `success: true`, `errorResponse: null`
- 실패: `data: null`, `success: false`, `errorResponse`에 `{code, message}`

🪤 **`data`를 벗기지 않고 바로 필드를 찾으면 전부 `undefined`다.** 클라이언트는 반드시
`response.data.tokenId` 형태로 접근하라.

🔴 **봉투가 없는 응답이 있다.** `GlobalExceptionHandler`는 `BusinessException`만 처리한다.
그 밖의 것 — 본문 검증 실패(`MethodArgumentNotValidException`), JSON 파싱 실패
(`HttpMessageNotReadableException`), 404, 405 — 는 Spring Boot의 `/error`로 포워드되어
**봉투 없이** 이 형태로 나간다 (2026-08-28 실측):

```json
{"timestamp":"2026-08-28T07:05:20.132+00:00","status":400,"error":"Bad Request","path":"/api/v1/tenants/signup"}
```

즉 클라이언트는 `success` 필드의 유무로 갈라야 한다. 없으면 위 형태다.
⚠️ **이 응답에는 `message`도 필드별 사유도 없다.** Boot 기본값이 `include-message=NEVER`·
`include-binding-errors=NEVER`이고 이 레포는 `server.error.*`를 재정의하지 않는다(전수 확인).
그래서 400을 받아도 **어느 필드가 왜 틀렸는지는 응답으로 알 수 없다.**

### 인증

인증 수단이 **경로가 아니라 헤더로 정해진다.** `ApiKeyAuthenticationFilter`에는 경로 화이트리스트가
없고, `X-API-Key` 헤더가 있으면 그것으로 인증한다. 없으면 `JwtAuthFilter`가 `Authorization`을 본다.

| 인증 | 헤더 | 용도 |
|---|---|---|
| JWT | `Authorization: Bearer {accessToken}` | 테넌트 관리 작업 (큐 생성/수정, API Key 발급) |
| API Key | `X-API-Key: {rawKey}` | 런타임 작업 (enqueue, admit, verify, complete) |
| 없음 | — | 아래 permitAll 목록 |

**permitAll (인증 불필요)** — `SecurityConfig:36-59`

```
POST /api/v1/tenants/signup
POST /api/v1/tenants/login
POST /api/v1/tenants/refresh
GET  /api/v1/queues/*/tokens/*      ← 폴링. 유저 브라우저가 직접 부른다
GET  /api/v1/queues/*/status        ← 대기열 현황. 공개
GET  /actuator/{health,info,prometheus}
```
그 외 `/actuator/**`는 `denyAll`, 나머지 전부 `authenticated`.

> ⚠️ **`authenticated`는 JWT와 API Key를 구분하지 않는다.** 즉 API Key로도 큐 생성이 되고
> JWT로도 enqueue가 된다. 아래 표의 "권장 인증"은 **의도된 사용법**이지 서버가 강제하는 제약이 아니다.

### 에러 코드 (`queue-common/ErrorCode`)

| 코드 | HTTP | 의미 |
|---|---|---|
| `T001` | 409 | 이미 존재하는 이메일 |
| `T002` | 404 | Tenant를 찾을 수 없음 |
| `T003` | 401 | 이메일 또는 비밀번호 불일치 |
| `T004` | 401 | 유효하지 않은 토큰 |
| `A001` | 404 | API Key를 찾을 수 없음 |
| `A002` | 403 | 본인의 API Key가 아님 |
| `Q001` | 404 | 대기열을 찾을 수 없음 |
| `Q002` | 403 | 본인의 대기열이 아님 |
| `Q003` | 409 | 이미 존재하는 대기열 이름 |
| `Q004` | 503 | 현재 진입할 수 없는 대기열 (PAUSED 등) |
| `Q005` | 429 | 대기열이 가득 참 (`maxCapacity` 초과) |
| `Q006` | 409 | 테넌트당 큐 개수 상한(20) 초과 |
| `QE006` | 409 | 현재 상태에서 수행할 수 없는 작업 |
| `QE001` | 503 | 대기열 처리 중 일시적 오류 — **재시도하라** |
| `TK001` | 404 | 대기 토큰을 찾을 수 없음 |
| `TK002` | 404 | 유효하지 않은 입장 토큰 |
| `AK001` | 401 | 인증 필요 |
| `AK002` | 403 | 권한 없음 |
| `RL001` | 429 | 요청 한도 초과 — `Retry-After` 헤더를 보라 |
| `I004` | 500 | 서버 오류 |

검증 실패(`@NotBlank` 등)는 위 코드가 아니라 **Spring의 `MethodArgumentNotValidException`이 400**으로
나간다. 🔴 **필드명도 사유도 담기지 않는다** — 위 [응답 봉투]의 `/error` 형태이고 `message`가 빠져 있다.
🪤 2026-08-28 이전에는 이 400이 **401로 위장돼 나갔다**(`SecurityConfig`의 permitAll 목록에 `/error`가
없어 ERROR 디스패치가 인증에 걸렸다). 그때 쓰인 클라이언트가 401을 자격 증명 문제로 처리하고 있다면
**지금은 그 분기가 안 탄다.**

---

## 1. 테넌트

### `POST /api/v1/tenants/signup` — 가입
인증 없음

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `email` | string | ✅ | `@Email` |
| `password` | string | ✅ | **최소 12자** |
| `name` | string | ✅ | — |

응답 `TenantResponse`: `tenantId` · `email` · `name`

🪤 Rate Limit **5회/분/IP** (Fixed Window, `rl:signup:ip{IP}:{윈도우}`). 로컬에서 여러 테넌트를
동시에 만들면 전부 `127.0.0.1`이라 한 창을 나눠 쓴다 — loopback 별칭(`127.0.0.x`)으로 IP를 갈라라.

### `POST /api/v1/tenants/login` — 로그인
인증 없음 · Rate Limit **10회/분/IP**

| 필드 | 타입 | 필수 |
|---|---|---|
| `email` | string | ✅ |
| `password` | string | ✅ |

응답 `LoginResponse`: **`accessToken`**(15분) · **`refreshToken`**(7일)

### `POST /api/v1/tenants/refresh` — 토큰 재발급
인증 없음 · Rate Limit **30회/분/IP**

| 필드 | 타입 | 필수 |
|---|---|---|
| `token` | string | ✅ | ← ⚠️ `refreshToken`이 **아니라** `token`이다

응답 `RefreshResponse`: `accessToken` · `refreshToken` (Rotation — 이전 refreshToken은 무효)

### `POST /api/v1/tenants/logout` — 로그아웃
JWT 필요 · body는 `refresh`와 같은 `{token}`

응답 `data: null`

---

## 2. API Key

### `POST /api/v1/tenants/me/api-keys` — 발급
**JWT 필요**

**요청 body 없다.** 컨트롤러에 `@RequestBody`가 없으므로 `{}`를 보내든 `{"name":"..."}`를 보내든
**서버는 읽지 않는다.** 빈 POST로 충분하다.

응답 `ApiKeyIssueResponse`:

| 필드 | 타입 | 설명 |
|---|---|---|
| `apiKeyId` | string | 키 식별자. 폐기(`DELETE`)에 쓴다 |
| **`rawKey`** | string | 🔴 **평문 키. 이때 한 번만 나온다.** 서버는 해시만 보관한다 |
| `message` | string | `"이 키는 지금만 표시됩니다. 안전한 곳에 보관하세요."` |

🔴 **`rawKey`를 놓치면 복구 방법이 없다. 새로 발급받는 수밖에 없다.**
필드명이 `apiKey`도 `key`도 아닌 **`rawKey`**다.

### `DELETE /api/v1/tenants/me/api-keys/{apiKeyId}` — 폐기
JWT 필요 · body 없음 · 응답 `data: null`
에러: `A001`(없음) · `A002`(남의 키)

---

## 3. 큐 관리

전부 **JWT 권장**. 경로는 `/api/v1/queues`.

### `POST /api/v1/queues` — 생성

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `name` | string | ✅ | `@NotBlank` |
| `maxCapacity` | int | ✅ | **1 ~ 300,000**. 범위 밖은 400. 초과 enqueue는 `Q005`(429) |
| `waitingTtl` | Integer | ⬜ | 최소 1 **(초)** |
| `inactiveTtl` | Integer | ⬜ | 최소 1 **(초)** |

응답 `QueueResponse`: `queueId` · `name` · `maxCapacity` · `waitingTtl` · `inactiveTtl` · `status` · `createdAt`

- `queueId` 형식: `q_{UUIDv7}`
- `status`: `ACTIVE` / `PAUSED` / `DELETED`
- `createdAt`: **UTC Instant** (`2026-08-27T04:06:33Z`)
- 생성 시 `RedisClusterAssigner`가 Redis 클러스터를 배정한다 (§75). SQL로 직접 INSERT하면 이 로직을 건너뛴다.

### 나머지
| 메서드 | 경로 | body | 비고 |
|---|---|---|---|
| `GET` | `/api/v1/queues/{queueId}` | — | `QueueResponse` |
| `PATCH` | `/api/v1/queues/{queueId}` | `{name}` **만** | TTL·capacity는 수정 불가 |
| `POST` | `/api/v1/queues/{queueId}/pause` | 없음 | enqueue가 `Q004`(503)로 막힌다 |
| `POST` | `/api/v1/queues/{queueId}/resume` | 없음 | |
| `DELETE` | `/api/v1/queues/{queueId}` | 없음 | |

에러 공통: `Q001`(없음) · `Q002`(남의 큐) · `QE006`(상태 위반)
생성 전용: `Q003`(이름 중복) · `Q006`(테넌트당 큐 20개 상한. DELETED는 안 센다)

---

## 4. 런타임 — 대기열 엔진

`QueueEngineController`는 클래스 레벨 `@RequestMapping("/api/v1/queues")`가 붙어 있다.
아래 경로는 **전체 경로**다.

### `POST /api/v1/queues/{queueId}/tokens` — enqueue (줄 세우기)
**API Key 권장**

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `identifier` | string | ✅ | 최대 100자. **Tenant가 정하는 유저 식별자** |

응답 `EnqueueResponse`:

| 필드 | 타입 | 설명 |
|---|---|---|
| `queueId` | string | |
| `identifier` | string | 요청한 값 그대로 |
| **`tokenId`** | string | `tok_{UUIDv7}`. **폴링·complete에 필요하니 반드시 유저에게 전달** |
| **`seq`** | long | 발급 순번. **폴링 쿼리에 필수** |
| `rank` | long | 현재 대기 순위. **1-based** (첫 사람이 1). 🔴 **`-1`은 순번이 아니라 상태** — 아래 |
| `total` | long | 현재 대기 인원 |
| **`already`** | boolean | 🔑 **같은 `identifier`가 이미 줄 서 있었으면 `true`** |

🔑 **`already`의 의미** — 같은 `identifier`로 다시 부르면 서버는 새로 줄 세우지 않고
**기존 `tokenId`·`seq`를 그대로** 돌려주며 `already: true`를 붙인다. 중복 게이트는 `tokens` Hash의
`HSETNX`다. 그래서 **새로고침해도 자리를 잃지 않는다** — 단, 같은 `identifier`를 써야 한다.
(실측: 같은 identifier 3회 → 전부 200, tokenId·seq 동일, 2·3회차만 `already: true`)

🔴 **`already: true` + `rank: -1` = 이미 입장권이 나갔다** — 줄에 없다는 뜻이지 순번이 아니다.
`admit`은 대기줄에서 빼면서 `tokens` Hash 게이트는 남기므로(그래야 재진입이 새 줄로 안 세어져
중복 과금이 안 생긴다), 그 창에 재-enqueue가 오면 이 조합이 된다. 원인은 셋 — 입장 직후
새로고침 / Tenant가 `admit` 응답 전달 실패 후 재시도 / 막 만료됐고 회수 배치(10초)가 아직 안 돎.
그 `tokenId`로 폴링하면 앞 둘은 즉시 `ready: true` + `admitToken`, 마지막은 `TK001`(종료 신호)다.
`getDisplayRank()`가 `-1`을 그대로 보존한다 — `rank + 1`을 하면 와이어에 **0**이 나가 존재하지
않는 순번이 된다.

⚠️ **정원(`maxCapacity`)이 차 있어도 이 응답이 나온다.** `Q005`는 **신규 진입자에게만** 준다 —
이미 발급받은 사람은 정원과 무관하게 `already: true`다 (`enqueue_bulk.lua`의 FULL 분기가
`HGET`으로 먼저 가른다).

에러: `Q005`(429, 정원 초과 — **신규만**) · `Q004`(503, PAUSED) · `QE001`(503, **재시도 가능**) · `Q001`

### `GET /api/v1/queues/{queueId}/tokens/{tokenId}` — 폴링
**인증 없음 (permitAll).** 유저 브라우저가 직접 부른다.

| 파라미터 | 위치 | 필수 | 비고 |
|---|---|---|---|
| `queueId` | path | ✅ | |
| `tokenId` | path | ✅ | |
| **`seq`** | query | ✅ | 🔴 **필수다.** `@RequestParam long seq` — 기본값이 없어 **빠뜨리면 400** |
| `ka` | query | ⬜ | 기본 `false`. §82에서 keepalive 분기를 삭제해 **지금은 동작에 영향이 없다** |

응답 `PollResponse` — **`@JsonInclude(NON_NULL)`이라 `admitToken`은 있을 때만 나온다**

| 필드 | 타입 | 설명 |
|---|---|---|
| `ready` | boolean | 입장 준비 완료 여부 |
| `admitToken` | string | **`ready=true`일 때만 존재.** false면 **필드 자체가 없다** |

🪤 폴링 간격은 응답이 알려주지 않는다. **`/status`의 `pacing` 표로 클라이언트가 계산**한다 (§79).
Rate Limit은 `rl:poll:token:{tokenId}` — 토큰 단위라 **한 유저가 탭을 여러 개 열면 나눠 쓴다.**

### `GET /api/v1/queues/{queueId}/status` — 대기열 현황
**인증 없음 (permitAll).** 개인화 값이 없어 캐시 가능하다.

응답 `QueueStatusResponse`:

| 필드 | 타입 | 설명 |
|---|---|---|
| `lastAdmittedSeq` | long | 마지막으로 입장시킨 순번 (admitWatermark) |
| `pacing` | `List<List<Long>>` | 폴링 간격 구간표. `[[남은인원, 간격초], ...]` |

내 순위 = `내 seq - lastAdmittedSeq`. 🔑 **역행할 수 있으니 클라이언트가 단조 감소로 clamp하라**
(API 서버 N대 + 어피니티 없음).

### `POST /api/v1/queues/{queueId}/admit` — 입장시키기
**API Key 권장.** Tenant가 "지금 N명 받겠다"고 당긴다 (Backpressure Pull).

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `count` | int | ✅ | **1 ~ 100**. 상한 100은 Redis 단일 스레드 보호 (§80 ⑦) |
| **`requestId`** | string | ✅ | 최대 100자. **멱등 키** |

🔴 **`requestId`는 "채우면 되는 값"이 아니다.**

> Tenant가 정하는 멱등 키. 같은 값으로 다시 부르면 대기열을 건드리지 않고 저장된 결과를
> 그대로 돌려준다(REPLAY). — `AdmitRequest.java:15`

**한 번의 논리적 admit에 하나의 `requestId`를 고정하고, 재시도에도 같은 값을 유지하라.**
매 호출마다 새 UUID를 만들면 필드는 채워지지만 **멱등성은 하나도 못 받는다.**

안 지키면 이렇게 된다:
```
admit(count=100) → 서버가 100명 입장 → 응답 전송 중 네트워크 끊김
재시도 (새 requestId) → 또 100명 입장 → 200명이 자리를 받는다
```
**자리도 과금도 이중이 된다.** 멱등 payload TTL은 **300초**(`ADMIT_IDEM_TTL_MILLIS`).
키는 큐 스코프(`QueueKeys.admitIdem(queueId, requestId)`)라 다른 테넌트와 겹쳐도 안전하다.

응답 `AdmitResponse`: `{ "admitted": [ ... ] }` — 배열 원소 `Admitted`:

| 필드 | 타입 | 설명 |
|---|---|---|
| `tokenId` | string | |
| `identifier` | string | enqueue 때 Tenant가 준 값 |
| `seq` | long | |
| **`admitToken`** | string | 🔑 **입장권. TTL 60초.** 유저에게 전달해야 한다 |

⚠️ 요청한 `count`보다 **적게 올 수 있다** (대기자가 그만큼 없으면). 배열 길이로 판단하라.

### `POST /api/v1/queues/{queueId}/admit-tokens/{admitToken}/verify` — 입장 검증
**API Key 권장.** **요청 body 없다** (`admitToken`이 경로에 있다).

응답 `VerifyResponse`: `valid`(boolean) · `identifier`(string)

- **Redis를 먼저 본다.** 그래서 Kafka 적재 지연과 무관하게 동작한다.
- **`admitToken`을 소비하지 않는다.** 여러 번 불러도 60초 안이면 계속 `valid: true`.
- 🔑 **verify 응답 시점이 완료 확정이다** (PR #48). 그래서 `complete`는 선택이다.
- 에러: `TK002`(404) — TTL 60초가 지났거나 잘못된 토큰

### `POST /api/v1/queues/{queueId}/tokens/{tokenId}/complete` — 완료 통보
**API Key 권장**

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `admitToken` | string | ✅ | 최대 50자 |

응답 `CompleteResponse`: `status`(string) · `completedAt`(LocalDateTime)

**판정 순서: DB 먼저, 0행이면 Redis.**

1. DB `markCompleted` — 유효 창 **300초**. `admitToken` TTL 60초를 넘겨도 통과한다
2. 0행이고 **이미 완료된 행**이면 → **처음 완료된 시각**을 그대로 돌려준다 (재시도 안전)
3. 0행이고 행 자체가 없으면 → **Redis 폴백**. 적재 지연 구간의 정상 입장자를 살린다

에러: `TK002`(404) — 셋 다 실패했을 때

> **왜 폴백이 필요한가.** DB 술어가 요구하는 `admit_token`·`admitted_at`은 Kafka → consumer
> 경로로만 채워진다. 적재가 밀리면 **정상 요청도 0행**이 되어 입장한 사용자가 404를 받는다.
> 같은 창에서 `verify`가 멀쩡한 것은 Redis를 먼저 보기 때문이다.
>
> **왜 자격이 넓어지지 않는가.** `admit-by-admit`의 TTL은 60초, DB 창은 300초다 —
> **Redis 히트 창 ⊂ DB 창**. 폴백이 통과시키는 요청은 예외 없이 적재만 끝났다면 DB 경로도
> 통과시켰을 것들이다. 부하 수치가 아니라 두 상수의 관계에서 나오는 성질이다.

🪤 **재시도 규약.** 첫 호출이 **Redis 폴백으로** 200을 받은 경우(= 적재 지연 구간), Redis 키가
소비되므로 **같은 요청을 재시도하면 404**가 날 수 있다. 200을 받았으면 완료된 것이니 재시도하지
마라. 반면 DB 경로로 200을 받은 경우는 몇 번을 재시도해도 **같은 `completedAt`**이 나온다.

---

## 5. 호출 순서

```
POST /tenants/signup                       → tenantId
POST /tenants/login                        → accessToken
POST /tenants/me/api-keys        (JWT)     → rawKey      🔴 지금만 보인다
POST /queues                     (JWT)     → queueId
─────────────────────────── 유저가 도착 ───────────────────────────
POST /queues/{q}/tokens          (Key)     → tokenId, seq, rank
        ↓ tokenId·seq를 대기 페이지에 전달 (seq는 뺄 수 없다)
GET  /queues/{q}/status          (공개)    → lastAdmittedSeq, pacing → 간격 계산
GET  /queues/{q}/tokens/{t}?seq= (공개)    → ready:false … 반복
─────────────────────── Tenant가 여유를 만들면 ────────────────────
POST /queues/{q}/admit           (Key)     → admitted[].admitToken
GET  /queues/{q}/tokens/{t}?seq= (공개)    → ready:true + admitToken
POST /queues/{q}/admit-tokens/{admitToken}/verify  (Key)  → 완료 확정
POST /queues/{q}/tokens/{t}/complete               (Key)  → 선택
```

**다음 호출에 필요해서 반드시 보관할 값**

| 값 | 나오는 곳 | 쓰는 곳 | 놓치면 |
|---|---|---|---|
| `rawKey` | api-keys 발급 | 모든 런타임 호출 | 🔴 **복구 불가.** 재발급뿐 |
| `accessToken` | login | 관리 API | refresh로 재발급 |
| `queueId` | 큐 생성 | 전부 | `GET /queues`로 조회 |
| `tokenId` | enqueue | 폴링·complete | 재-enqueue (같은 identifier면 자리 유지) |
| `seq` | enqueue | **폴링 쿼리 필수** | 폴링이 400 |
| `admitToken` | admit 또는 폴링 | verify·complete | 60초 지나면 만료 |

---

## 6. 흔히 틀리는 것

| 실수 | 결과 | 바로잡기 |
|---|---|---|
| 응답에서 `data`를 안 벗김 | 전 필드 `undefined` | `response.data.tokenId` |
| API Key를 `apiKey`/`key`로 찾음 | 401 | **`rawKey`** |
| `refresh`에 `refreshToken` 키로 보냄 | 400 | 필드명은 **`token`** |
| 폴링에 `seq` 안 붙임 | 400 | `?seq={enqueue의 seq}` **필수** |
| `admit`에 `requestId` 누락 | 400 전량 | `@NotBlank`다 |
| `admit`에 매번 새 `requestId` | 400은 면하나 **멱등성 0** | 한 논리 요청에 하나 고정, 재시도에도 유지 |
| `ready:false`인데 `admitToken`을 읽음 | `undefined` | `NON_NULL`이라 **필드 자체가 없다** |
| enqueue마다 새 `identifier` 생성 | 새로고침이 맨 뒤로 | 유저당 안정적인 값 (UUIDv7 재사용) |
| `rank`를 0-based로 표시 | 순위가 1 어긋남 | **1-based**다 |
| `admit` 응답 길이가 `count`와 같다고 가정 | 인덱스 오류 | 배열 길이로 판단 |
| `QE001`(503)을 영구 실패로 처리 | 유실 | **재시도하라** |
| `complete` 200 후 재시도 | 적재 지연 구간이었다면 404 | **200을 받으면 완료다. 재시도하지 마라** |

---

## 7. 최소 동작 예시

```bash
B=http://localhost:8080/api/v1

curl -s -X POST $B/tenants/signup -H 'Content-Type: application/json' \
  -d '{"email":"a@b.com","password":"Password1234!","name":"acme"}'

JWT=$(curl -s -X POST $B/tenants/login -H 'Content-Type: application/json' \
  -d '{"email":"a@b.com","password":"Password1234!"}' | jq -r .data.accessToken)

KEY=$(curl -s -X POST $B/tenants/me/api-keys -H "Authorization: Bearer $JWT" \
  | jq -r .data.rawKey)                      # ← 지금만 보인다

Q=$(curl -s -X POST $B/queues -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' \
  -d '{"name":"launch","maxCapacity":200000,"waitingTtl":720,"inactiveTtl":600}' \
  | jq -r .data.queueId)

# enqueue → tokenId·seq 확보
R=$(curl -s -X POST $B/queues/$Q/tokens -H "X-API-Key: $KEY" \
  -H 'Content-Type: application/json' -d '{"identifier":"user-0001"}')
TOK=$(echo $R | jq -r .data.tokenId); SEQ=$(echo $R | jq -r .data.seq)

curl -s "$B/queues/$Q/status"                          # 공개
curl -s "$B/queues/$Q/tokens/$TOK?seq=$SEQ"            # 공개. seq 필수

# admit — requestId를 고정하고 재시도에도 그대로 쓴다
RID=$(uuidgen)
AT=$(curl -s -X POST $B/queues/$Q/admit -H "X-API-Key: $KEY" \
  -H 'Content-Type: application/json' -d "{\"count\":10,\"requestId\":\"$RID\"}" \
  | jq -r '.data.admitted[0].admitToken')

curl -s -X POST $B/queues/$Q/admit-tokens/$AT/verify -H "X-API-Key: $KEY"
curl -s -X POST $B/queues/$Q/tokens/$TOK/complete -H "X-API-Key: $KEY" \
  -H 'Content-Type: application/json' -d "{\"admitToken\":\"$AT\"}"
```

---

## 부록 — 코드 위치

| 대상 | 파일 |
|---|---|
| 테넌트·API Key | `queue-api/.../tenant/TenantController.java`, `.../apikey/ApiKeyController.java` |
| 큐 관리 | `queue-api/.../queue/QueueController.java` |
| 런타임 엔진 | `queue-api/.../queue/QueueEngineController.java` |
| DTO | `queue-api/.../{tenant,apikey,queue}/dto/` |
| 인증 경로 규칙 | `queue-api/.../security/SecurityConfig.java` |
| API Key 필터 | `queue-api/.../security/ApiKeyAuthenticationFilter.java` |
| 에러 코드 | `queue-common/.../exception/ErrorCode.java` |
