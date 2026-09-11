# 2026-09-11 — verify 완료 경로의 Redis 미정리: 3인 검토 기록

> §92의 근거 파일이다. 보고는 각 에이전트 원문을 요지만 남겼고, **인용은 전부 원문 대조**했다
> (§22 · §36 · §80 · §82 · `API.md:327` · `TokenJpaRepository.findAdmittedByAdmitToken`).
> 실측 스크립트(`walk*.py`)와 근거 원본(`EVIDENCE-verify-cleanup.md`)은 세션 scratchpad에 있었고
> 커밋하지 않았다 — 재현은 §92 본문의 표만으로 충분하다(REST 6호출).

## 발견 경로

인프라 Step 1(compose prod 프로필) 뒤 DB·Redis를 초기화하고 REST로 한 바퀴 돌리다 발견.
**부하와 무관하게 결정적으로 재현**된다. 자가 치유(≤70초)되고 에러·로그가 없어 부하 테스트나
헬스체크로는 안 보인다.

## 실측 (api 3대, 인스턴스를 갈아타며)

| # | 실험 | 결과 |
|---|---|---|
| 1 | admit 2건 → #1 verify, #2 complete → Redis 키 | complete: 5키 삭제 / verify: **5키 전부 잔류** |
| 2 | verify 직후 같은 identifier 재-enqueue | `already=true` 옛 tokenId `seq=-1`, 폴링 `ready=true` + 같은 admitToken, DB 행 **1** |
| 3 | complete 직후 같은 identifier 재-enqueue | `already=false` 새 tokenId, 폴링 `ready=false`, DB 행 **2** |
| 4 | verify → verify → verify (같은 admitToken) | 200 · 200 · 200 |
| 5 | complete → verify | 404 |
| 6 | verify → complete → verify | 200 · 200 · 404 |
| 7 | 잔류 `admitted`를 회수 배치가 걷을 때 | `admitTokenTTL=1건` ×3 로그 = **헛 EXPIRED 발행**, DB `status=2` 유지(가드 no-op) |
| 8 | 교차 테넌트 (security 실증) | 남의 큐 verify 403 Q002 / 자기 큐 + 남의 admitToken 404 TK002 |

## 세 보고의 요지

### security — ⚪ 위협 아님
- 반복 verify의 주체는 큐 소유 Tenant뿐(실험 8). 세션은 Tenant 소관(`TENANT_INTEGRATION.md:329-333`).
- 원장은 `status IN (0,1)` 가드가 막는다(실험 4에서 COMPLETED 재발행이 no-op).
- 🔑 §22 면접 포인트 원문: *"verify만으로 ZREM하면 입장 실패 시 복구가 불가능하지만 complete 분리 시
  admitToken이 유효한 동안 재시도가 가능"* — verify 비소비는 **의도된 결정**이고 근거는 재시도다.
  PR #48 뒤에도 살아 있다. → **`admit-by-admit`을 verify가 지우면 안 된다.**
- 기울기: 코드 0줄(주석·문서만). 단 과금 1 vs 2와 주석 모순은 "architect/planner 몫"으로 범위를 좁혔다.

### planner — 완료 = 회차 종료는 이미 결정돼 있다 (후보 A)
- 직접 문장 0곳, 함의 7곳(§22 · §36 · §80 ①⑥ · §82 · STATE 종단 · Lua 회차), 반대 0곳.
- verify 잔류는 **결정의 부재** — PR #48 커밋 본문은 "DB를 한 번도 쓰지 않는다"까지만 말한다.
- 후보 B(완료 후 60초 무료 재입장)는 결정 6곳 번복 + STATE에 `COMPLETED → ADMIT_ISSUED` 전이 신설.
- 발견한 문서 내부 모순: `API.md:327` "여러 번 불러도 valid"(계약) ↔ `cleanup_completed.lua` "남으면
  60초 통과"(결함). PR #48 뒤 모든 재-verify는 "완료된 admitToken에 대한 verify"라 둘이 양립 불가.
- DECISIONS에 PR #48 절 자체가 **없다** — verify 완료 확정 결정은 FRS·API·STATE·FLOW·TENANT 5곳과
  메모리에만 있었다. §92가 그 사실도 적었다.

### architect — ④안 (`admit-by-admit`만 남긴다)
- 1-b가 결정적: `complete()`는 `markCompleted` 0행 → `findCompletedAt` 0행 → **Redis 폴백** 순서인데,
  컨슈머 백로그 + §91 발행 지연(67~128ms)이면 앞 둘이 0행이라 폴백 키 하나에 기댄다. verify가 그
  키를 지우면 둘 다 부르는 정상 Tenant가 404 — §80·§91이 없앤 결함의 부활, `verified-token` 재도입 필요.
- 회차 대조(`HGET tokenId` 비교)는 verify 컨텍스트에서도 성립 — `complete()` Redis 폴백이 이미
  같은 `AdmitRef` 입력으로 `cleanupCompleted`를 부르고 있다(새 조합 아님).
- F-3(트랜잭션 없음, Kafka 12초)과 충돌 없음 — Redis EVAL은 커넥션과 무관.
- ① 5키 전부(1-b 404) · ② `admit-by-admit`만(최악 조합 — 지워야 할 넷은 안 지우고 지우면 안 되는
  하나만 지움) · ③ 문서화(사고의 명문화) 기각.

## 판정 (lead 역할 = 세션 주체, 사용자 승인 2026-09-11)

세 보고가 한 점에서 만난다: **verify는 회차 키 넷을 정리하고 `admit-by-admit`은 남긴다.**
security의 "0줄" 기울기는 위협 관점에 한정된 것이고 일관성 관점에서는 수정이 맞다 — 관점 차이지
모순이 아니다. security의 "추측"(verify에 cleanup 넣으면 재시도 404)은 코드로 확정했다:
`findAdmittedByAdmitToken`이 `status = 1`을 요구하므로 컨슈머가 2로 올린 뒤엔 Redis 키 없이 404.

사용자 결정: ④안 채택. 근거 설명에서 사용자가 물은 두 질문("verify만 부르면 TTL을 기다려야 하나" →
④에서는 넷이 즉시, `admit-by-admit`만 PX / "반대 상황은" → complete 경로는 변화 없음, complete→verify
404는 순서가 틀린 호출)이 §92 본문의 표로 남았다.

## 미검증 (§92 "남는 것"과 같다)
- ①안의 404(1-b)는 코드 경로 추적 — 컨슈머 정지 결함 주입 실측은 안 했다
- 교차 인스턴스(verify 8083 / 재-enqueue 8084) — 안 쟀다
- `admitExpired` 카운트를 읽는 경보 유무 — monitoring 미확인

---

# 2차 검토 (구현 후) — code-reviewer · tester

둘 다 **조건부**. 조건이 전부 코드 동작 0 변경(주석·문서)이었고, 선택 항목 하나(`routeForWrite` 테스트)는
사용자가 포함을 결정했다.

## code-reviewer

| 지적 | 대조 결과 | 조치 |
|---|---|---|
| `doc/ROADMAP.md:490`에 "Redis·DB 직접 쓰기 0회"가 남음 | ✅ 사실. 내 전수 grep이 놓쳤다 | 정정 |
| `RedisQueueEngine`의 WARN 문구·주석이 호출자를 complete로 단정 | ✅ 사실. §92로 `0` 반환의 출처가 둘이 됐다 | 문구에서 "complete" 제거 + 출처 둘을 주석에 명시 |
| `enqueue_bulk.lua:13` 삭제 경로를 `cleanupCompleted` 하나로 적음 | ✅ 사실 | "둘이다"로 정정 |
| `enqueue_bulk.lua:28` "admit된 사람"이 완료자를 포함해 읽힘 | ✅ 사실 | "admit됐지만 아직 완료하지 않은"으로 좁히고 완료자 반례를 명시 |
| `DECISIONS §80`(6145)에 §92 배너 없음 (§22에만 있었다) | ✅ 사실 | 배너 추가 (본문은 안 고친다 — 이력) |
| `waiting` 삭제가 `cleanupVerified` 경로에서 무검증 | ✅ 사실. `AdmitExpiryReclaimTest`의 seed가 `waiting`을 안 심는다 | 새 라우팅 테스트가 `seedOnCluster2`로 `waiting`을 심어 함께 닫았다 |

🔧 **한 건은 반려했다.** `AdmitApiTest:257`의 DisplayName "Redis·DB 쓰기 0회"는 **404 경로**라 지금도 참이다
(cleanup 이전에 throw). 전수 grep 결과에 걸렸지만 정정 대상이 아니다.

## tester

- 결함 주입 사각지대 a·c·d·e 전부 단정이 있음을 확인. 인자 전달 누락(자리 바꿔치기)도 단위·통합 양쪽에서 하중을 받는다
- **동시성 테스트는 명시적 반대** — 두 경로의 상태 변경이 전부 한 EVAL 안이라 "Redis가 원자적인가"를
  재는 꼴이다. 깨지는 불변식 0 → 만들지 않았다(§4)
- **오염 없음 확인** — `try/finally` + 리터럴 키 이름이라 `RedisQueueEngineAdmitTest`의 "단독 통과, 전체 실패"
  전례(랜덤 admitToken 키)와 구조가 다르다
- `@Tag` 정확 — 통합은 `redis`, `@WebMvcTest`는 무태그

### 🔴 tester가 내 자기보고 오류를 잡았다

내가 "`hasKey(byAdmit).isTrue()`가 어댑터 5키 회귀를 잡는다"고 썼는데 **거짓**이다 —
`cleanupVerified(queueId, identifier, tokenId, seq)`에는 `admitToken`이 없어 어댑터가 그 키 이름을
만들 도리가 없다. 그 회귀의 실제 방어는 `AdmitApiTest`의 `never().cleanupCompleted(...)` 하나뿐이다.
§91의 "결함 주입이 지우는 방향만이라 사각지대 2건을 놓쳤다"와 **같은 계열의 오류**이고, §92 본문과
메모리에서 정정했다.

## 채택한 선택 항목 — `routeForWrite` 라우팅 테스트

두 검토자가 독립적으로 같은 공백을 지적했다: `RedisQueueEngineRoutingTest`가 밟는 다섯 경로에
**cleanup 계열이 한 건도 없었다.** `routeForWrite`를 빼도 전 스위트가 초록이고, 그때 cluster2 배정 큐는
완료 정리가 통째로 no-op이 된다(영구 락아웃 + 무료 재입장 + 헛 EXPIRED).

§92 이전부터 있던 공백이지만 **§92가 이 호출을 `complete당 1회` → `verify당 1회`로 올려 노출을 키우므로**
이번 범위에 넣었다(사용자 결정). 기존 픽스처를 그대로 쓰는 1건이다.

**결함 주입 D 실측**: `cleanup()`의 `routeForWrite(queueId)` → `cluster1`로 바꾸니
`RedisQueueEngineRoutingTest` 12건 중 1건 빨강. 원복 확인.

---

# 실기동 검증 (2026-09-11, 컨테이너 prod 프로필 · api 3대)

이미지 재빌드 후 DB·Redis를 비우고 REST로만. 요청은 인스턴스를 갈아타며 보냈다.

## A. §92 핵심 주장 — 4/4

| 단계 | 결과 |
|---|---|
| verify (8080) | 200 |
| 같은 identifier 재-enqueue (8084) | 200 · `already=false` · **새 tokenId** · `seq=2` |
| 그 토큰 폴링 (8083) | 200 · `ready=false` — **줄에 다시 섰다** |
| 같은 admitToken 재-verify (8080) | **200** — 재시도 계약 유지 |

결함 발견 당시(§92 이전)의 같은 시나리오는 `already=true` · 옛 tokenId · `ready=true`였다.
**교차 인스턴스 미검증 항목도 이걸로 해소된다** — verify·재-enqueue·폴링을 서로 다른 인스턴스가 받았다.

## B. (e) 결함 주입 — "①안이면 백로그 구간 complete가 404"

재빌드 없이 모사했다: verify 직후 `admit-by-admit`을 직접 `DEL`하면 ①안이 만들었을 상태와 같다.
컨슈머를 멈춰 DB 적재가 없는 구간을 재현했다.

| | verify | DEL | complete |
|---|---|---|---|
| B-1 대조군 (현행 ④안) | 200 | — | **200** |
| B-2 실험군 (①안 모사) | 200 | 1건 | **404 TK002** |

- B-1의 200이 폴백 경로였음을 로그로 확인: `complete가 Redis 폴백으로 처리됐다 — 컨슈머 적재가 밀려 있다`
- 컨슈머 재기동 후 토큰 3 → 5 → 그 구간에 **DB 행이 실제로 없었다**

**architect의 1-b 판정이 실측으로 확정됐다.** ①안을 골랐다면 verify·complete를 둘 다 부르는
정상 Tenant가 404를 받는다.

## 남은 미검증

- `admitExpired` 카운트를 읽는 경보 유무 — monitoring 미확인 (§92가 그 카운트를 **줄이는** 방향이라
  급하지 않다)
