# Queue Platform JS SDK

대기 페이지가 Platform을 **직접 폴링**하게 해 주는 브라우저 SDK. 빌드 없음, 의존성 없음, ESM 한 파일.

```html
<script type="module">
import { createQueueClient } from './queue-sdk.js';

createQueueClient({
  baseUrl: 'https://queue.example.com',
  queueId: 'q_123',           // Tenant 서버가 enqueue 응답에서 받아 페이지에 심어 준다
  tokenId: 'tk_abc',
  seq: 10432,
  onUpdate: ({ rank }) => render(rank),
  onReady: ({ admitToken }) => goToService(admitToken),
  onError: (e) => console.warn(e.code, e.status),
}).start();
</script>
```

`start()` / `stop()` 둘뿐이다.

## 범위

| 하는 것 | 안 하는 것 |
|---|---|
| `GET /status` (전광판) | enqueue · admit · verify · complete |
| `GET /tokens/{tokenId}?seq=` (개인 폴링) | |

🔴 **enqueue 계열은 SDK에 없다.** 전부 `X-API-Key`가 필요한데 그 키는 Tenant 서버에만 둔다
(`doc/TENANT_INTEGRATION.md` §1). 브라우저로 내려가면 그 순간 남의 큐에 무제한으로 넣을 수 있다.

## SDK가 대신 해 주는 것

Tenant가 직접 짜면 틀리기 쉬운 넷이다.

1. **워터마크 단조 clamp** — API 서버가 N대이고 세션 어피니티가 없어 `lastAdmittedSeq`가 뒤로 갈 수 있다. 그대로 쓰면 순위가 역행한다.
2. **비대칭 지터** — 30만 명이 같은 pacing 표를 본다. 지터가 없으면 전원이 같은 초에 몰린다. 대칭(±)이면 실효 간격이 등급 하한 아래로 내려가 폴링 한도에 더 빨리 닿는다.
3. **리더 탭** — 폴링 버킷 키가 `rl:poll:token:{tokenId}` 하나라 **탭 2개면 여유가 0**이고 3개면 10초 만에 429다. 유일한 해법이 "탭 하나만 폴링"이고, 서버 변경 없이 클라이언트에서만 가능하다. Web Locks로 선출하고 나머지 탭은 `BroadcastChannel`로 화면만 받는다. 탭이 닫히면 브라우저가 락을 자동으로 놓아 다음 탭이 이어받는다(하트비트 불필요).
4. **개인 폴링을 시작하면 멈추지 않는다** — `last-active`를 심는 것이 개인 폴링이라, 시작해 놓고 멈추면 `inactiveTtl`(300초) 회수 대상이 된다.

## 에러

| 응답 | 뜻 | SDK 동작 |
|---|---|---|
| 429 `RL001` | 너무 자주 물어봤다 (`Retry-After` 있음) | 그만큼 쉬고 그대로 이어간다 |

🪤 429가 두 군데서 나오고 **모양이 다르다**. `RL001`은 필터가 봉투 없이 `{error, message, retryAfter}`를 쓰고, `Q005`는 `ApiResponse` 봉투(`errorResponse.code`)를 탄다. SDK는 둘 다 읽는다.
| 429 `Q005` | 큐가 꽉 찼다 (`Retry-After` **없음**) | `onError` — 재시도 금지 |
| 404 `TK001` | 토큰 종료 | `onError` — 재-enqueue하면 맨 뒤 |

## 점검

```bash
node test.mjs
```

## CORS

`GET /status`·`GET /tokens/*` 둘만 모든 오리진에 열려 있다(`PublicPollingCorsConfig`).
429의 `Retry-After`도 노출된다 — 안전 목록이 아니라 안 열면 교차 오리진에서 `null`이다.
나머지 경로는 닫혀 있다. 브라우저에서 enqueue를 시도하지 마라는 뜻이기도 하다.
