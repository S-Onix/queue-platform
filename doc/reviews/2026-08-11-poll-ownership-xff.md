# 리뷰 기록 — 폴링 소유권 검증 / X-Forwarded-For 신뢰 경계

- **일자**: 2026-08-11
- **브랜치**: `feat/queue-enqueue-token-kafka`
- **PR**: [#19](https://github.com/S-Onix/queue-platform/pull/19) (base `dev`)
- **대상**: 보안 결함 2건 수정 (커밋 전 working tree)

읽기 전용 에이전트(security / code-reviewer / lead)의 산출물은 파일로 남지 않으므로 이 문서가 원본이다.
구현 에이전트(backend / tester)의 산출물은 코드 자체이며, 여기엔 판단 근거만 남긴다.

---

## 0. 요약

| 결함 | 유형 | 수정 | 검증 |
|---|---|---|---|
| A. 폴링이 tokenId를 검증하지 않음 | 권한 (인증 없는 엔드포인트) | `poll_verify.lua` 신규 + 포트 통합 | 실 Redis 20건, 공격 시나리오 전부 차단 |
| B. `X-Forwarded-For` 무검증 채택 | 신뢰 경계 (한도 무력화) | `extractIp()` 삭제 → `getRemoteAddr()` | 실서버 재현 전/후 대조, 뮤테이션 검증 |

**두 결함은 같은 뿌리다** — *신뢰할 수 없는 입력을 검증 없이 권한·한도의 근거로 삼았다.*
A는 tokenId를 대조하지 않아 자격이 없었고, B는 클라이언트가 쓰는 헤더를 버킷 키로 썼다.

전체 테스트 **207건 / 실패 0**. code-reviewer 판정 **BLOCKER 0건**.

---

## 1. 결함 A — 폴링 tokenId 미검증

### 문제

`GET /api/v1/queues/{queueId}/tokens/{tokenId}?seq={seq}&ka={0|1}` 는 permitAll(인증 없음)이다.
설계 의도는 **"tokenId 소유가 곧 자격(capability)"** 이었으나, 실제 코드는 tokenId를 한 번도 보지 않았다.

```java
// 수정 전 — QueueEngineService.poll()
if (!queueEngine.isWaiting(queueId, seq)) {      // seq 존재만 확인
    throw new BusinessException(ErrorCode.TOKEN_NOT_FOUND);
}
...
if (keepalive) queueEngine.touchLastActive(queueId, seq, clock.millis());
```

`seq`는 큐별 `INCR` 값이라 `1, 2, 3...`으로 추측이 자명하다.
따라서 **아무 tokenId나 넣고 `?seq=1&ka=1`을 반복하면 남의 대기 항목 inactive_ttl을 무한 연장**할 수 있었다.
자기 자리를 지키는 것이 아니라 **남의 자리를 대신 지켜주는** 형태의 오용이다.

### 수정

`poll_verify.lua` 신규 — waiting / tokens / last-active 3키를 받아 한 번에 처리한다.

```
ZRANGEBYSCORE waiting seq seq  →  identifier   (없으면 0)
HGET tokens identifier         →  "tokenId|issuedAt"
'|' 앞부분과 제시된 tokenId를 문자열 비교        (불일치면 0)
keepalive='1'이면 ZADD lastActive nowMillis seq
return 1
```

도메인 포트에서 `isWaiting` + `touchLastActive`를 삭제하고 하나로 합쳤다.

```java
boolean verifyWaiting(String queueId, long seq, String tokenId, boolean keepalive, long nowMillis);
```

### 결정 근거

**왜 Lua 1회인가 (검증과 keepalive를 왜 안 나누나)**

나누면 이런 순서가 성립한다.

```
1. 검증 통과 (이 시점엔 항목이 있다)
2. ← 그 사이 admit 또는 이탈로 waiting에서 제거됨
3. touch 실행 → 이미 사라진 seq의 last-active가 되살아난다 (좀비)
```

Redis 스크립트는 단일 실행이므로 `ZRANGEBYSCORE → HGET → ZADD` 사이에 아무것도 끼어들 수 없다.
부수적으로 라운드트립도 2배에서 1배로 준다.

**왜 포트를 합쳤나**

두 메서드가 분리돼 있는 한 *"검증 없이 touch"* 를 다시 호출할 수 있다. 그게 정확히 이번 결함이다.
호출처가 `poll()` 하나뿐이라 합치는 쪽이 diff도 작다.

**버린 대안**

| 대안 | 버린 이유 |
|---|---|
| Java에서 `isWaiting` → `HGET` 비교 → `ZADD` 3회 왕복 | 위 좀비 경쟁이 그대로 남는다 |
| 검증만 Lua, touch는 별도 | 같은 이유. 원자성이 절반만 생긴다 |
| Lua에서 `tonumber(tokenId)` 비교 | tokenId는 `tok_`+UUIDv7 문자열. 반드시 문자열 비교 |

**`ZADD` member에 `ARGV[1]` 원문을 쓰는 이유**
`tostring(tonumber(x))`는 Lua의 `%.14g` 포맷을 거쳐 Java가 만든 문자열과 어긋날 수 있다.
실측: seq `1000000000000000000` → member `"1000000000000000000"` (`1e+18`이 아님).
다만 **이 member를 되읽는 코드는 아직 존재하지 않는다** (inactive_ttl 배치가 미구현).

### code-reviewer 검증 (로컬 Redis 7.0.15, 직접 EVAL 9케이스)

| 항목 | 판정 |
|---|---|
| 원자성 주장이 성립하는가 | **성립.** mismatch·hash 삭제 모두 `0` 반환 + `lastActive` zcard 0 |
| member 포맷이 안전한가 | **안전.** `@RequestParam long seq`라 `"007"`은 7로, `"1e3"`은 400으로 처리되어 Lua까지 못 온다 |
| 헥사고날 위반 | **없음.** `QueueEngine.java`는 import 0줄(순수 인터페이스) |
| `keepalive`/`nowMillis` 누출이 정당한가 | **정당.** 이 둘을 분리하는 순간 원자성이 깨지고, 그게 이번에 고친 결함이다 |
| 구식 Hash 값(구분자 없음) 분기가 우회 경로인가 | **아님.** 완전한 tokenId를 이미 알아야만 통과 |
| 테스트가 회귀를 막는가 | **막는다.** 서비스 계층이 Mockito strict stubbing이라 tokenId 전달을 되돌리면 happy path가 깨진다 |

---

## 2. 결함 B — X-Forwarded-For 무검증

### 문제

```java
// 수정 전 — RateLimitFilter.extractIp()
String xForwardedFor = request.getHeader("X-Forwarded-For");
if (xForwardedFor != null && !xForwardedFor.isBlank()) {
    return xForwardedFor.split(",")[0].trim();
}
return request.getRemoteAddr();
```

이 IP가 `RateLimitKeys.publicEndPoint(action, ip)` 키가 되어 **인증 전** 엔드포인트의 Fixed Window 버킷을 가른다
(signup 5/분, login 10/분, refresh 30/분).

### 실증 (수정 전, local 실서버)

```bash
# A) XFF 없음 — login 15회
→ 404 ×10, 429 ×5              # 11번째부터 정상 차단

# B) 요청마다 X-Forwarded-For만 다르게 — 15회
→ 404 ×15                       # 전부 통과, 429 0건
```

Redis 키가 증거다. 헤더 값마다 새 버킷이 생겼다.

```
rl:login:ip203.0.113.1:29773976  ...  rl:login:ip203.0.113.15:29773976   (15개)
```

**즉 login / signup / refresh의 인증 전 Rate Limit은 사실상 존재하지 않았다.**
brute force 방어가 헤더 한 줄로 무력화된다.

### 수정

`extractIp()` 메서드를 통째로 삭제하고 호출부를 바꿨다.

```java
// 프록시가 없으므로 TCP peer가 유일한 사실. XFF는 클라이언트가 쓰는 값이라 신뢰 근거가 없다.
// LB 도입 시 server.forward-headers-strategy=native + internal-proxies로 처리한다(앱 코드 아님).
String ip = request.getRemoteAddr();
```

`application*.yml`은 **손대지 않았다.**

### 결정 근거 — 왜 지금 `forward-headers-strategy: native`를 켜지 않는가

처음 검토한 안은 Tomcat `RemoteIpValve`를 켜는 것이었다. security 에이전트가 이를 뒤집었고, 근거가 타당해 채택했다.

**반박 1 — 성립 조건이 뒤집혀 있다.**
`native`의 안전성은 *"앱이 `getRemoteAddr()`만 본다"* 는 전제 위에서만 성립한다.
코드를 그대로 두고 native만 켜면 아무것도 못 막는다. 인터넷에 직접 노출된 WAS(peer = 공인 IP)에서는
RemoteIpValve가 XFF를 **건드리지 않고 원본 그대로 통과**시키므로 `extractIp()`가 여전히 위조 헤더를 읽는다.
→ **코드 삭제가 본체이고, yml은 프록시가 생긴 뒤의 후속 조치다.**

**반박 2 — 지금 켜면 오히려 구멍이 열린다.**
Tomcat 기본 `internal-proxies`에는 `127.0.0.1` / `::1` / 사설대역이 포함된다.
로컬에서 XFF가 다시 신뢰되어, 방금 막은 구멍이 그대로 열린다.

**반박 3 — 프록시가 실재하지 않는다.**
리포지토리 전체에 nginx conf / docker-compose / Dockerfile / `*.conf` **0건**.
ALB·Nginx는 전부 Sprint 11 계획(`doc/ROADMAP.md:626`).
즉 기존 주석의 "Nginx 프록시 대응"은 **존재하지 않는 프록시를 위해 실제 방어를 판 것**이었다.

**왼쪽 우선 채택은 신뢰 프록시가 있어도 위조 가능하다** — 클라이언트가 보낸 XFF에 프록시가 뒤에 append하기 때문.
RemoteIpValve가 오른쪽부터 거슬러 올라가는 이유가 이것이다.

**버린 대안**

| 대안 | 버린 이유 |
|---|---|
| 지금 `native` 켜기 | 위 반박 1·2 |
| XFF 파싱을 앱에서 "제대로" 고치기 | RemoteIpValve가 이미 그 일을 한다. 직접 짜면 IPv6 대괄호, 다중 XFF 헤더, RFC7239 `Forwarded:` 엣지를 다시 틀린다 |
| XFF 신뢰 여부를 프로퍼티 토글로 | 값이 프로파일당 고정인데 앱 코드에 분기를 만드는 것. 밸브가 이미 프로파일 설정으로 해결한다 |

### 문서 정정

코드와 어긋난 근거가 남으면 다음 사람이 되돌린다. 세 곳을 같이 고쳤다.

- `doc/DECISIONS.md` §63 Rationale
- `doc/DECISIONS.md` §63 Interview Point
- `doc/sprint-5/RATE_LIMITER.md` §5.4 (옛 `extractIp` 코드가 그대로 복사돼 있었음)

---

## 3. 악의적 사용자 전제 검증 (tester)

**대전제**: 사용자는 정상적이지 않다. 남의 것을 훔치려 하고, 한도를 우회하려 하고, 이상한 값을 밀어넣는다.

### 3-1. 뮤테이션 검증 — 테스트가 진짜 회귀를 잡는가

신규 테스트를 만든 뒤, **`extractIp`(XFF 신뢰)를 일부러 되살려** 테스트가 실패하는지 확인했다.

```
extractIp 복원  → 2 tests completed, 2 failed   ✅ 회귀를 실제로 잡음
원복 확인       → diff 3+/13- 원상 복구
```

테스트가 있다는 것과 테스트가 작동한다는 것은 다르다. 이 절차로 후자를 확인했다.

### 3-2. 폴링 소유권 공격 (실 Redis, 자동화 20건)

| 시나리오 | 결과 |
|---|---|
| 교차 탈취 (남의 seq + 내 tokenId, 양방향) | false + last-active 0 |
| **TTL 연장 공격** `ka=true` 100회 | zcard 끝까지 **0** |
| seq 열거 스캔 1~50 | 전부 false |
| 경계·이상값 seq `0 / -1 / MAX / MIN` | 예외 없이 false |
| tokenId 구분자 주입 `tok_X\|issuedAt`, `tok_X\|999`, `\|tok_X` | 전부 false |
| 이상 tokenId 13종 (null / 빈 / 공백 / 탭·개행 / `*` / `user_*` / `\r\nHGETALL` / 유니코드 / 이모지 / Lua 조각 / NUL / 10KB) | 예외 없이 전부 false |
| 큐 교차 (A의 유효 쌍 → B) | A=true, B=false |
| tokens Hash 유실 (필드/전체) | **fail-closed** → false |
| admit으로 waiting 이탈 후 keepalive | 좀비 last-active 없음 |

**`|` 주입이 안 통하는 이유**: Lua는 **저장값**만 `|`로 자르고 **입력값**은 그대로 비교한다 (`storedTokenId ~= tokenId`).
공격자가 `tok_X|999`를 제시해도 저장된 `tok_X`와 문자열이 다르므로 불일치다.

### 3-3. Rate Limit 우회 (실서버 8090)

| 시나리오 | 수정 전 | 수정 후 |
|---|---|---|
| login, 매 요청 XFF 변조 15회 | 15/15 통과, 버킷 키 **15개** | **11번째부터 429**, 버킷 키 **1개** |
| 우회 헤더 6종 (`X-Real-IP`, `Forwarded`, `X-Client-IP`, `X-Cluster-Client-IP`, `True-Client-IP`, XFF 중복) | — | 한도 못 벗어남, 키 1개 |
| signup 5/분 (헤더 위조 7회) | — | 6번째부터 429 |
| refresh 30/분 (헤더 위조 33회) | — | 31번째부터 429 |

HTTP 레벨 폴링 소유권도 확인: 공격자 토큰 + 피해자 seq → **404**, 주인 → **200** + last-active 기록.

### 3-4. 결론

**뚫린 것 없음.** 막힐 줄 알았는데 통과한 공격은 하나도 없다.
전체 **207 tests / 0 failures**. 30초 상한에 걸린 항목 없음, 미실행 항목 없음.

---

## 4. 이번 수정으로 막지 못하는 것

정직하게 남긴다. 이 목록이 다음 작업의 입력이다.

### 4-1. 폴링 Rate Limit 키가 공격자 통제값 (**결함 B와 같은 유형**)

`RateLimitFilter:113` — `String tokenId = uri.substring(uri.lastIndexOf('/') + 1)` 을 버킷 키로 쓴다.
방금 고친 XFF와 **완전히 같은 실수**다: 신뢰할 수 없는 입력이 한도의 근거가 됐다.

실측 — 랜덤 tokenId 50회:

| 지표 | 값 |
|---|---|
| 429 발생 | **0 / 50** |
| 신규 Redis 버킷 키 | **50개** |
| 키당 TTL | **3587초 (약 1시간)** |
| 대조군 (같은 tokenId 12회) | 6번째부터 429 → 리미터 자체는 정상 |

요청 1건당 Redis master EVAL 2회(token-bucket + 30만 ZSet `ZRANGEBYSCORE`)가 확정 실행된다.
100만 요청이면 100만 키가 1시간 상주한다.

tokenId는 UUIDv7(랜덤 74비트)이라 **자격 추측은 비현실적**이므로 권한 문제는 아니다. 순수 자원 소모 증폭이다.
→ 폴링에도 **peer IP 기준 버킷을 tokenId 버킷과 AND로** 걸어야 한다 (IP는 위조 불가이므로 상한이 실제로 선다).

### 4-2. `last-active` ZSet에 삭제·만료 경로가 없다

`poll_verify.lua:45`가 `ZADD`만 하고, 전 소스에 `ZREM`도 `EXPIRE`도 **0건**이다(읽는 코드도 0건).

30만 대기 큐가 `ka=1` 폴링을 한 바퀴 돌면 멤버 30만. 이벤트가 끝나 `waiting`이 비어도 이 키는 남는다.
seq는 `INCR`이라 재사용도 안 되므로 이벤트 100회면 같은 키에 **3천만 멤버가 영구 적재**된다.
`maxmemory 0` 권고와 겹치면 OOM.

→ inactive_ttl 배치(Sprint 7/9) 설계 시 **"판정 후 `ZREM`"을 같은 스크립트에** 넣을 것.

### 4-3. Sprint 11 — `native` 누락 시 역방향 self-DoS

ALB를 붙이고 `application-prod.yml`에 `forward-headers-strategy: native`를 빠뜨리면
모든 요청의 `getRemoteAddr()`가 **ALB 사설 IP 하나**가 된다.
→ `rl:signup:ip10.0.1.7` 단일 버킷 → **전 세계 신규 가입이 분당 5건.**

429가 안 나오던 게 문제였는데, 이번엔 정상 사용자 전원이 429다. 실패 모드가 비대칭이라 배포 체크리스트로 올려야 한다.

추가로, native를 켜도 **사설대역에서의 위조는 남는다.**
EC2가 private subnet에 있고 8080이 VPC 내부에 열려 있으면 ALB를 건너뛴 직결이 가능하다.
→ **실질 방어는 보안그룹으로 8080을 ALB SG에서만 인바운드 허용**하는 것이고, yml은 보조다.

### 4-4. 나머지

- **tokenId 유출**: URL 경로에 실리므로 로그·리퍼러·프록시로 새면 소유권 증명이 무력하다. 서명 토큰(HMAC) 또는 헤더 이동은 범위 밖
- **WAS 시계 오차**: `last-active` score의 출처가 요청을 받은 WAS의 벽시계다. NTP 미동작 시 조기 EXPIRE 가능. 배치 판정을 Redis `TIME` 기준으로 통일하면 오차원이 하나로 준다
- **Lua가 score 유일성을 암묵 전제**: `members[1]`을 무조건 채택한다. 현재는 `INCR`이 유일성을 보장하나, **DB→Redis 복구가 중복 score를 넣으면 뒤로 밀린 사용자가 영구 404**가 된다. 추적이 매우 어려운 유형이므로 복구 스크립트에 유일성 단언이 필요하다
- **NAT 공유 IP**: peer IP만 쓰므로 같은 사무실·캐리어 NAT 뒤 사용자가 한도를 공유한다. 다만 프록시가 없는 현재 구성에서 **오늘 기준 동작 변화는 없다**(XFF는 오직 공격자만 보내던 값). Sprint 11에 `native` + 한도 재조정으로 같이 다룰 사안
- **IPv6 /128 버킷**: 한 사용자가 사실상 무한 IP를 갖는다. /64로 접어 키를 만드는 게 맞다
- **계정 단위 실패 카운터 부재**: IP 기반 방어는 봇넷·프록시 로테이션에 무력하다. `rl:login:account:{email}`이 진짜 방어

---

## 5. 부수적으로 정정한 사실

작업 중 드러난 **잘못된 근거**들이다. 코드는 맞았지만 이유가 틀린 경우로, 그대로 두면 다음 판단을 오염시킨다.

| 위치 | 틀린 서술 | 사실 |
|---|---|---|
| `poll_verify.lua:11` 주석 | "Lua의 TIME은 비결정적이라 쓰지 않는다" | Redis 5+ **effects replication** 이후 사실이 아니다(로컬 7.0.15에서 `TIME` + write 스크립트 정상 실행 확인). Java 주입 결정 **자체는** 테스트 용이성으로 정당하므로 근거만 정정 |
| backend 보고 | "폴링에 rate limit이 없다" | `RateLimitFilter:110-122`에 tokenId 기준 Token Bucket(cap 5, refill 0.5/s)이 실재한다. 결론(무제한 시도 가능)은 맞고 근거가 틀렸다 — 버킷 키가 공격자 선택값이라 열거가 안 막히는 것 |
| `RateLimitKeys.publicEndPoint()` | — | `"rl:" + action + ":ip" + ip` 로 `:ip` 뒤 콜론이 빠져 `rl:login:ip127.0.0.1` 형태가 된다. 동작 문제는 아니나 IPv6 값이 들어가면 구분자가 뭉개진다 (별건, 미수정) |

---

## 6. 최종 판정

<!-- lead 판정 대기 중 — 도착 시 이 절을 채운다 -->

---

## 참고

- 결정 근거 원문: `doc/DECISIONS.md` §63 (Rate Limit), §66-70 (Queue Engine), §73 (Kafka 복귀)
- Rate Limiter 설계: `doc/sprint-5/RATE_LIMITER.md`
- Lua 학습 노트: `doc/sprint-5/LUA_SCRIPTS.md`
- 기술 개념 문서: `doc/blog/`
- 운영 대응: `doc/monitoring/runbook/`
