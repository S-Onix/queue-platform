# Tenant 통합 가이드

> **대상**: Queue Platform을 자기 서비스에 붙이는 Tenant 개발자
> **정본**: API 필드·에러 코드의 정본은 [`API.md`](API.md)다 — **코드에서 추출한 것**이라
> 구현과 어긋나지 않는다. [`FRS_final.md`](FRS_final.md)는 설계 시점의 **요구사항 원본**이며
> 필드 명세로는 쓰지 마라(둘이 갈리면 코드에서 뽑은 `API.md`가 맞다).
> 이 문서는 **순서와 계약**을 다룬다.
> **최신화**: 2026-08-25 (구현 대조 — 아래 서술은 전부 `queue-api` 코드에서 확인했다)

---

## 0. 한 장 요약

```
[브라우저]                [Tenant 서버]              [Queue Platform]
    │                          │                            │
    │  1. 상품 페이지 요청      │                            │
    ├─────────────────────────>│                            │
    │                          │  2. POST /tokens (enqueue) │
    │                          ├───────────────────────────>│
    │                          │<───────────────────────────┤
    │                          │     tokenId, seq, rank     │
    │  3. 대기 페이지 + 🔴 tokenId·seq                       │
    │<─────────────────────────┤                            │
    │                                                       │
    │  4. GET /status  (전광판 — 전원 동일, 인증 없음)        │
    ├──────────────────────────────────────────────────────>│
    │  5. GET /tokens/{tokenId}?seq= (차례 임박 시에만)      │
    ├──────────────────────────────────────────────────────>│
    │<──────────────────────────────────────────────────────┤
    │              ready=true, admitToken                    │
    │                          │                            │
    │                          │  6. POST /admit  (내가 받을 수 있는 만큼)
    │                          ├───────────────────────────>│
    │                          │  7. POST /verify 또는 /complete
    │                          ├───────────────────────────>│
```

**Platform은 순서만 관리한다.** 몇 명을 언제 들여보낼지는 **Tenant가 정한다**(6번 `admit`).
Platform이 알아서 밀어 넣는 일은 없다.

---

## 1. 준비

| 항목 | 얻는 곳 |
|---|---|
| `X-API-Key` | 회원가입 → API Key 발급. **Tenant 서버에만 둔다. 브라우저로 내리지 마라** |
| `queueId` | 큐 생성 시 발급 |

`X-API-Key`가 필요한 것은 **`enqueue`·`admit`·`verify`·`complete` 넷**이다.
브라우저가 부르는 **`/status`와 폴링 둘은 인증이 없다**(§79).

---

## 2. Enqueue — 줄을 세운다 (Tenant 서버)

```bash
curl -X POST https://<platform>/api/v1/queues/{queueId}/tokens \
  -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"identifier": "0192f3c1-8b2e-7a44-9c31-000000000001"}'
```

```json
{ "isSuccess": true,
  "data": { "queueId":"q1", "identifier":"0192f3c1-...", "tokenId":"tk_...",
            "seq": 1042, "rank": 37, "total": 1042, "already": false } }
```

### `identifier`는 **UUIDv7을 재사용**한다

Tenant가 정하는 자유 문자열(최대 100자)이지만 규칙이 둘 있다.

- **같은 사용자·같은 큐에는 항상 같은 값**을 써라. 새 값을 주면 **줄을 새로 선다**(맨 뒤).
  사용자가 새로고침해도 같은 `identifier`면 `already: true`로 **자리가 유지된다**.
- **추측 가능한 값을 쓰지 마라**(이메일·회원번호·순번). 남의 자리를 점유할 수 있다.

`already: true`는 **오류가 아니다.** 이미 줄을 서 있다는 뜻이고, 응답의 `tokenId`·`seq`는
처음 발급된 그 값이다. 그대로 쓰면 된다.

---

## 3. 🔴 대기 페이지를 서빙할 때 `tokenId`와 `seq`를 함께 내려보내라

브라우저는 폴링할 때 두 값이 **둘 다** 필요하다. `FRS_final.md`의 흐름 요약(§2·§12)에 한 줄로
적혀 있지만 **왜 필요한지·잃으면 어떻게 되는지가 없어서** 실제로 자주 빠진다. 여기서 못박는다.

```html
<script>
  window.QUEUE = {
    queueId: "{{queueId}}",
    tokenId: "{{tokenId}}",   // enqueue 응답
    seq:     {{seq}}          // enqueue 응답 — 문자열 말고 숫자
  };
</script>
```

**`seq`를 뺄 수 없다.** 폴링 엔드포인트가 `?seq=`를 요구하고, 서버는 이 값으로 대기 항목을 찾은 뒤
`tokenId`로 소유권을 대조한다. `seq`만으로 판정하면 `seq`가 큐별 연번이라 추측이 자명해서
남의 대기 항목을 건드릴 수 있다.

두 값을 어디에 담든(HTML 인라인·쿠키·`localStorage`) 상관없지만, **잃어버리면 폴링이 404가 되고
사용자는 자리를 잃은 것처럼 보인다.** 그때는 같은 `identifier`로 `enqueue`를 다시 부르면
`already: true`로 원래 자리를 되찾는다 — **그래서 `identifier` 재사용이 중요하다.**

---

## 4. 브라우저 폴링 — 엔드포인트가 둘이다

### ① 전광판 `/status` — 평상시엔 이것만 부른다

```
GET /api/v1/queues/{queueId}/status        인증 없음 · Rate Limit 없음
```
```json
{ "lastAdmittedSeq": 47,
  "pacing": [[50,2],[1000,5],[5000,10],[10000,15],[null,20]] }
```

**응답이 30만 명 전원에게 동일하다.** 개인화가 없어서 캐시가 가능하고, 그래서 제한도 없다.

- **내 순위** = `mySeq - watermark` (직접 계산한다. 서버는 rank를 주지 않는다)
- **다음 호출 간격** = `pacing`에서 **내 순위 이하인 첫 구간**의 초 + **지터**

```js
// 🔴 lastAdmittedSeq를 그대로 쓰지 마라 — 단조 clamp가 필요하다
wm = Math.max(wm, status.lastAdmittedSeq);          // wm은 호출 간에 유지한다
const rank = Math.max(0, QUEUE.seq - wm);

const base = status.pacing.find(([max]) => max === null || rank <= max)[1];
const waitMs = (base + Math.random() * Math.max(1, base / 4)) * 1000;
```

> 🔴 **`wm` 단조 clamp를 빼면 순위가 뒤로 갔다 앞으로 갔다 한다.** API 서버가 N대이고
> **세션 어피니티가 없어서**, 방금 받은 `lastAdmittedSeq`가 직전 값보다 **작을 수 있다**.
> 그대로 쓰면 사용자 화면에서 순위가 역행한다.

> 🪤 **지터를 빼지 마라.** 30만 명이 같은 표를 보고 있어서, 지터가 없으면 전원이 같은 초에 몰린다.
>
> ⚠️ **지터 규약은 아직 확정 전이다**(`FRS_final.md` §6.3의 🔴 표시). §79 본문은 `±20% 대칭`,
> 같은 절 Consequences는 `하한 위로만`(비대칭)이라 서로 다르다. 위 예시는 **비대칭**을 따랐다 —
> 대칭이면 실효 간격이 등급 하한 아래로 내려가 계약 ③의 한도에 더 빨리 닿기 때문이다.
> 서버가 간격을 계산하지 않으므로 **어느 쪽도 서버가 강제할 수 없다.**

### ② 개인 폴링 `/tokens/{tokenId}` — **차례가 가까울 때만**

```
GET /api/v1/queues/{queueId}/tokens/{tokenId}?seq={mySeq}     인증 없음
```
```json
{ "ready": true, "admitToken": "at_xxx" }     // ready=false면 admitToken은 null
```

`rank <= 0`이 되면(= 내 차례가 됐을 수 있으면) 이쪽을 부른다. **평상시에 이걸 부르면 안 된다** —
아래 계약 ③의 한도에 그대로 걸린다.

> **폴링이 곧 생존 신호다.** 이 호출이 대기자의 `last-active`를 갱신한다. 오래 안 부르면
> 이탈로 간주돼 회수된다. `?ka=` 파라미터는 **무시된다**(하위호환으로 남아 있을 뿐).

---

## 5. Admit — 받을 수 있는 만큼만 당겨 간다 (Tenant 서버)

```bash
curl -X POST https://<platform>/api/v1/queues/{queueId}/admit \
  -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"count": 50, "requestId": "adm-2026-08-25T10:00:00Z-01"}'
```

```json
{ "isSuccess": true,
  "data": { "admitted": [
    { "tokenId":"tk_...", "identifier":"0192f3c1-...", "seq":1042, "admitToken":"at_..." } ] } }
```

- `count` **최대 100**. 초과하면 400이다
- `requestId`는 **멱등 키**다. 같은 값으로 재시도하면 **같은 목록**이 돌아온다.
  네트워크 타임아웃 때 그냥 같은 `requestId`로 다시 불러라 — 중복 입장이 생기지 않는다
- **Platform은 admit을 먼저 걸지 않는다.** 빈 좌석이 생겼을 때 **Tenant가 부르는 것**이다
  (Backpressure Pull). 안 부르면 줄은 그대로 서 있는다

`admitToken`은 이 응답에서 발급되고 **그 순간부터 TTL이 흐른다**. 브라우저 폴링은 이미 발급된
값을 **전달만** 한다 — 폴링이 늦었다고 시계가 늦게 가지 않는다.

---

## 6. Verify / Complete — 둘 중 하나는 반드시 부른다

```bash
# verify — admitToken이 유효한지 확인하고 identifier를 받는다
curl -X POST .../api/v1/queues/{queueId}/admit-tokens/{admitToken}/verify -H "X-API-Key: $API_KEY"
# → { "data": { "identifier": "0192f3c1-..." } }

# complete — 입장 완료를 통보한다
curl -X POST .../api/v1/queues/{queueId}/tokens/{tokenId}/complete \
  -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"admitToken": "at_..."}'
# → { "data": { "status": "COMPLETED", "completedAt": "2026-08-25T10:00:03.412Z" } }
```

**둘 다 불러도 된다.** `verify` 뒤의 `complete`는 처음 완료된 시각을 그대로 돌려준다(멱등).

🪤 **단, 적재 지연 구간에서 받은 200은 예외다.** `complete`는 DB를 먼저 보고, 컨슈머가 아직
`ADMITTED`를 적재하지 않아 0행이면 **Redis로 폴백**해 200을 준다. 그때는 Redis 키가 소비되므로
**같은 요청을 재시도하면 404**가 날 수 있다. **200을 받았으면 완료된 것이니 재시도하지 마라.**
(자세한 판정 순서는 [`API.md`](API.md) §4 complete)

---

## 🔴 계약 5건 — 어기면 어떻게 되는지까지

### ① `verify`와 `complete` 중 **최소 하나**는 불러야 한다

`verify`는 **응답을 주는 시점에 완료를 확정한다**. Platform의 책임은 답을 돌려주는 데까지이고,
그 뒤 Tenant 안에서 좌석 배정이 어떻게 되는지는 관측할 수도 책임질 수도 없기 때문이다.

| 부른 것 | 결과 |
|---|---|
| `verify`만 | ✅ 완료 확정 |
| `complete`만 | ✅ 완료 확정. **`verify`를 건너뛴 호출을 서버가 거절하지 않는다** — `complete` 자체가 `admitToken`을 검증하므로 거절할 근거가 없다 |
| 둘 다 | ✅ 안전. 두 번째 호출은 처음 완료 시각을 돌려준다 |
| **아무것도 안 부름** | 🔴 원장에 `ADMIT_ISSUED`로 남고, **대사 배치가 300초 뒤 만료로 정리**한다 |

> 마지막 줄이 요금을 바꾸지는 않는다(과금은 상태를 보지 않는다). 다만 **Tenant의 완료율 지표가
> 통째로 틀어진다.**

### ② `429`는 실패가 아니라 **재시도 신호**다

`Retry-After` 헤더가 항상 붙는다. **그 초만큼 기다렸다가 다시 불러라.**

| 어디 | 한도 | `Retry-After` |
|---|---|---|
| Tenant API (`enqueue`/`admit`/…) | Plan별 Token Bucket (`RL001`) | Plan 기준 값 |
| 브라우저 폴링 | 아래 ③ | **2초** |

🪤 **429를 오류 화면으로 띄우지 마라.** 사용자는 자리를 잃지 않았고, 잠깐 너무 자주 물어본 것뿐이다.
`enqueue`가 429면 그대로 재시도하면 된다 — `identifier`가 같으면 중복이 생기지 않는다.

### ③ **한 `tokenId`의 폴링 한도는 탭·기기를 통틀어 하나다**

버킷 키가 `tokenId` 하나다. **용량 5, 초당 1개 충전.**

```
탭 1개  → 여유 4
탭 2개  → 여유 0        ← 이미 아슬아슬하다
탭 3개  → 10초 안에 429
```

같은 사용자가 **탭을 두 개 열면 그 둘이 한 버킷을 나눠 쓴다.** 기기가 달라도 마찬가지다 —
IP가 아니라 `tokenId`가 키이기 때문이다.

**대응**: 탭 하나만 폴링하게 만들어라. `BroadcastChannel`로 리더 탭을 뽑는 것이 표준적인 방법이고,
서버 변경 없이 클라이언트만으로 된다.

> 이것이 **평상시 폴링을 `/status`로 보내야 하는 이유**다. `/status`는 제한이 없다.

### ④ **입장권 1장 ≠ 세션 1개**

`admitToken`은 **"줄을 통과했다"는 증명**이지 로그인 세션이 아니다. Platform은 세션을 만들지 않고,
동시 접속 수도 세지 않는다.

- `verify`가 돌려준 `identifier`로 **Tenant가 자기 세션을 만든다**. 그때부터는 Tenant 소관이다
- 한 사용자가 통과한 뒤 **탭을 여러 개 여는 것을 Platform은 막지 않는다.**
  그걸 막아야 하면 Tenant의 세션 계층에서 해라
- 반대로 **Tenant 세션이 끊겨도 Platform은 모른다.** 다시 줄을 세우려면 `enqueue`를 다시 불러야 하고,
  그건 **맨 뒤**다

### ⑤ **`verify`는 60초, `complete`는 300초** — 창이 다르다

| 호출 | 유효 창 | 기준 시각 |
|---|---|---|
| `verify` | **60초** | `admit` 응답 시점 |
| `complete` | **300초** | `admit` 응답 시점 |

`verify`는 `admitToken`의 Redis 키(PX 60초)에 기대고, `complete`는 DB의 `admitted_at`을 300초까지
소급해 받아 준다. **비대칭은 의도한 것이다** — 좌석 배정이 오래 걸린 Tenant의 **늦은 완료 통보**를
받아 주기 위해서다(실측: admit 후 **98초**에 온 `complete`가 정상 200이었다).

**그래서 순서가 중요하다.**

```
❌  admit → [내부 좌석 배정 90초] → verify        → TK002 404 (창 60초를 넘겼다)
✅  admit → verify → [내부 좌석 배정 90초] → complete   → 200
```

**`verify`를 내부 처리 *전에* 불러라.** 뒤로 미루면 60초 창을 넘긴다.

---

## 에러 코드

| 코드 | HTTP | 뜻 | 대응 |
|---|---|---|---|
| `RL001` | 429 | 요청 한도 초과 | `Retry-After`만큼 대기 후 재시도 |
| `QE001` | 503 | 대기열 처리 일시 오류 | 재시도. `enqueue`면 같은 `identifier`로 안전하다 |
| `TK001` | 404 | 대기 토큰 없음 | 폴링 중이면 자리를 잃은 것. 같은 `identifier`로 `enqueue` 재시도 |
| `TK002` | 404 | 입장 토큰 무효 | 창(60초)을 넘겼거나 잘못된 값. 계약 ⑤ 참조 |

---

## 흔한 실수 6가지

1. 🔴 **대기 페이지에 `seq`를 안 내려보낸다** → 폴링이 아예 안 된다 (§3)
2. 🔴 **사용자마다 매번 새 `identifier`를 만든다** → 새로고침할 때마다 맨 뒤로 간다 (§2)
3. 🔴 **평상시에도 개인 폴링을 부른다** → 계약 ③에 걸려 429. 평상시는 `/status`다
4. 🔴 **`verify`를 내부 처리 뒤에 부른다** → 60초 창을 넘겨 `TK002` (계약 ⑤)
5. 🟠 **폴링 간격에 지터를 안 넣는다** → 30만 명이 같은 초에 몰린다 (§4)
6. 🟠 **429를 오류 화면으로 띄운다** → 사용자는 자리를 잃지 않았다 (계약 ②)

---

## 더 볼 것

| 문서 | 내용 |
|---|---|
| [`FRS_final.md`](FRS_final.md) | API 필드·에러 코드·Redis 키의 **정본** |
| [`FLOW.md`](FLOW.md) | Enqueue·Polling·Admit·Complete 흐름도 |
| [`STATE.md`](STATE.md) | Token 상태 머신 |
| [`DECISIONS.md`](DECISIONS.md) | §79(폴링 분할) · §80(admit) · §82(이탈 회수) · §84(과금) |
