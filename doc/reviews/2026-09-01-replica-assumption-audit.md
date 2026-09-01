# replica 전제 재감사 (2026-09-01)

> **왜 이 문서가 있나.** `CLAUDE.md` §4-3이 *"이 절이 세운 전제 위에서 2026-08-26 dba 감사 6건이
> 나왔다. **그 판정들을 다시 봐야 한다**"*고 적었는데, **그 6건의 원문이 어디에도 없었다.**
> `doc/reviews/`에도 `DECISIONS.md`에도 없고 세션 transcript에만 있었다. 지시만 남고 대상이
> 사라진 상태였다.
>
> 🔑 **감사 결과는 반드시 파일로 남긴다.** 이 문서가 그 교훈의 산물이다.

목록을 복원하는 대신 범위를 다시 잡았다 — **"replica 전제를 깐 서술을 코드·문서에서 전수로 찾아
지금 실측에 대조한다."** 결과적으로 핸드오프의 "문서 전수 재훑기" 항목도 함께 처리됐다.

**수행**: `dba`·`architect` 2인 병렬(둘 다 읽기 전용, §5 준수) → 보고를 `lead`가 코드·실측으로 대조.
**환경**: dev(`3742140`) 빌드, api **3대**(8080·8083·8084) + batch + consumer 실기동,
라우팅 DEBUG on, MySQL M(3306)+R(3307), 데이터 비운 상태. → [[integration-test-multi-instance]]

---

## 0. 한 줄 결론

**Master/Replica는 더 이상 "읽기 분산"이 아니다. 실질 DR 자산이고, 그 명목이 남아 있어서
결함 3건과 낭비 1건을 낳고 있었다.**

읽기의 99% 이상이 master로 간다. 라우팅은 `isCurrentTransactionReadOnly()`로만 갈리는데,
**명시적 `@Transactional(readOnly = true)`가 붙은 곳은 단 하나뿐이었고 그것이 결함이었다.**

---

## 1. 확정된 결함 (전부 실측)

### 🔴 D-1. 큐 생성 직후 GET이 404 — **수정 완료**

`QueueService.getQueue`의 `@Transactional(readOnly = true)`가 이 프로젝트에서 **명시적으로
replica로 보내는 유일한 지점**이었고, 하필 그 경로가 **read-after-write**였다.

```
create(200) → GET  gap 24ms → 404 Q001    17:37:08.605 [master] / 08.629 [replica]
create(200) → GET  gap 26ms → 404 Q001    17:37:09.342 [master] / 09.368 [replica]
```

| 측정 | 결과 |
|---|---|
| architect (REST 8회) | **2 / 8** (25%) — 라우팅 로그 시각 대조 |
| lead 독립 재현 (12회) | **1 / 12** (8.3%) |
| **수정 후 (30회)** | **0 / 30** |

비율이 다른 것은 하니스의 create↔GET 간격 차이다(`curl` 프로세스 생성 오버헤드).
**부하가 없어도 발생하며, 부하로 복제 지연이 커지면 확률이 올라간다.**

- 피해자는 **테넌트 자동화/IaC**다 — 생성 직후 확인이 정상 패턴이라서다. 사람이 콘솔에서
  클릭하면 간격이 벌어져 안 걸리고, **그래서 지금까지 드러나지 않았다.**
- 🪤 **통합 테스트로는 구조적으로 못 잡는다.** 테스트 설정이 replica url을 master(3306)로 준다 —
  라우팅이 갈라지지 않으므로 어떤 단정도 빨개지지 않는다(§4-3).
- **§4-2 모순**: `getQueue`는 "읽기니까 replica"라는 일반 규칙을 따랐고, `POST → GET` 계약은
  "방금 만든 것은 조회된다"를 참으로 가정한다. **둘 다 각각 옳고 함께 두면 모순이다.**

**조치: `readOnly = true` 한 줄 삭제.** 늘리는 게 아니라 **줄이는** 안이다.
잃는 것은 읽기 분산인데 실측 분산율이 0.9%이고, 그 대부분이 배치의 `findAll()`일 것으로
**추정**한다 — ⚠️ **0.9%의 내역 분해는 미측정이다**(별도 창의 배치 replica/master 비가 근거이지
그 수치 자체의 분해가 아니다). 어느 쪽이든 사라지는 몫은 크지 않다.

기각한 대안:
- *생성 직후 404를 테넌트 계약에 추가(재시도하라)* — Platform 결함을 테넌트에게 떠넘긴다.
  계약 7번째 = 문서·SDK·테넌트 코드가 다 늘어난다
- *GTID read-your-writes(`wait_for_executed_gtid_set`)* — 새 인프라·새 코드. **삭제로 이미 안
  깨지는데 만드는 것**이라 §4 위반

### 🔴 D-2. Rate Limit이 조용히 꺼지는 **두 번째 입구** — 미해결

`RateLimitFilter.loadTenant` → `tenantRepository.findById` → **replica**(실측).

```java
Optional<Tenant> tenantOpt = loadTenant(tenantAuth.getId());
if (tenantOpt.isEmpty()) {
    log.warn("Tenant not found for rate limit: id={}", tenantAuth.getId());
    return true;  // 통과
}
```

복제 지연이면 401이 아니라 **그 요청의 Rate Limit이 꺼진다.** 바로 위 주석이 *다른* 원인
(`tenantId`가 null)에 대해 이미 *"이 분기가 모든 요청을 통과시켜 Rate Limit이 사실상 꺼진
상태가 된다"*고 경고해 놓았다 — **같은 함정에 입구가 둘이다.**

노출 창은 **신규 가입 후 테넌트 캐시 TTL 60초** 안. 정상 테넌트는 replica에 이미 행이 있다.

🟠 **replica가 죽었을 때의 실패 모드는 미측정**이다(빈 Optional이 아니라 커넥션 예외).
`INFRA_SETUP.md:1292`의 *"인증 조회가 replica라 401 위장한다는 것은 거짓"* 판정이 여기 걸려
있다 — 그 근거였던 `findByKeyHash`는 확실히 master지만, **같은 필터 체인의 다음 단계가
replica다.** → **security 또는 infra가 실측으로 판정해야 한다.**

### 🟠 D-3. 배치가 replica 가용성에 묶여 있다 — 미해결

`ReconcileJob:120`·`TokenReclaimJob:146`의 `findAll()`이 **루프 최상단에서, 감싸는 try 없이**
replica를 친다(큐별 예외만 잡는다).

- **replica가 죽으면 그 틱 전체가 죽고 회수 3경로가 통째로 멈춘다.** §82로 Cancel API를
  폐기해 **회수 배치가 유일한 정리 경로**다. 멈추면 `waiting` ZSet의 유령이 누적되고,
  Redis `noeviction`의 종착점은 **enqueue 503**이다.
- **즉 DR 목적으로 둔 replica가 앱의 청소 경로를 자기 가용성에 묶어 놨다.**
  batch가 master 없이 못 도는 것은 당연하지만, **replica 없이 못 돌 이유는 하나도 없다.**
- 알람 공백: `mysql_up == 0`은 있으나 **접속은 되는데 복제만 밀린 replica**는 아무도 안 울린다
  (`alerts/infra.yml` 205~228이 "쓸 곳이 생기면 만든다"로 보류 중). **그 보류 사유가 흔들린다.**

> 반면 *"새 큐가 복제 도착 전이면 그 주기 회수에서 빠진다"*(`QueueJpaAdapter:65`의 경고)는
> **무해로 판정**됐다 — 회수 3경로의 TTL이 최소 60초/180초인데 지연은 25ms다. 발생하지 않고,
> 발생해도 다음 주기에 잡힌다.

### 🟠 D-4. 커넥션 예산이 트래픽과 반대 — 미해결

```
application-prod.yml   master.maximum-pool-size: 50
                       replica.maximum-pool-size: 50     ← 대칭
실측 트래픽 비율        master 99% : replica 1%
```

Hikari `minimum-idle` 기본값이 `maximum-pool-size`와 같아 **풀 크기만큼 미리 붙는다.**
로컬 실측: replica에 커넥션 **46개** 상주하며 `Com_select`는 재기동 후 **2**.

api 3대면 replica에 150개가 놀고, 한계는 **master가 먼저** 친다.

⚠️ **prod `max_connections`는 미확인**이다(호스트 접근 없음). 투영은 로컬값 200 가정 위의
산수다 — **확인이 선행이다.** → infra

---

## 2. 미측정 2건 해소 (§4-3 보강)

| 항목 | 이전 | 이번 실측 | 방법 |
|---|---|---|---|
| `@Query(nativeQuery)` | 🟠 미측정 ("추론") | **master** | verify를 없는 admitToken으로 불러 Redis 미스 → DB 폴백 강제. `findAdmittedByAdmitToken` 경로에서 replica 0 / master 4 |
| `SimpleJpaRepository.findById` | 🟠 미검증 (`findAll` 하나만 확인) | **replica** | 테넌트 캐시 cold/warm 차분 |

### 🔑 판정 기준을 하나로 줄여라

§4-3이 기준을 *"`SimpleJpaRepository`가 구현했나 / 인터페이스에 선언했나"*로 적으면서 동시에
*"확인한 CRUD 메서드는 `findAll` 하나뿐 — 일반화하지 마라"*고도 적는다. **자기모순이다.**

- **설계 규칙으로 남길 것 (하나뿐)**:
  **"replica로 보내려면 호출자가 `@Transactional(readOnly = true)`를 명시한다."**
  코드에 보이고, `grep`으로 전수가 되고, 리뷰에서 확인된다.
- **함정 노트로 강등할 것**: *"`SimpleJpaRepository`가 구현한 메서드는 클래스 레벨 어노테이션이
  걸려 replica로 갈 수 있다(실측: `findAll`·`findById`)."* 이건 **Spring Data 구현 세부**다 —
  버전이 바뀌면 조용히 달라지고, 그때 깨지는 건 라우팅이라 **아무 테스트도 빨개지지 않는다.**
  무엇보다 **의도가 코드에 안 보인다** — `findAll()` 호출자는 자기가 replica를 읽는 줄 모른다.
  실제로 그래서 배치가 D-3의 의존을 갖게 됐다.

🪤 **메커니즘 추론으로 판단하지 마라.** 이 건에서 에이전트 둘(code-reviewer·architect)이 독립적으로
*"Spring Data가 readOnly라 replica"*라고 추론했고 **둘 다 틀렸다.** 확인 수단은 라우팅 로그다:
`--logging.level.com.sonix.queue.infrastructure.config=DEBUG`

---

## 3. 핸드오프 항목 하나가 겨눈 대상이 틀렸다

**기존 서술**: *"`getMaxCapacity`가 master를 친다. 주기 50배↑라 빈도도 50배. 큐 수 늘면
여기가 먼저."*

**실측** (라우팅 카운트):

```
직렬 20건(1 rps)   → master 44회   (요청당 2)
동시 20건(버스트)  → master 23회   (요청당 1 + 3)
```

enqueue 1건이 master를 **두 번** 친다:

| # | 경로 | 빈도 | 버스트에서 |
|---|---|---|---|
| ① | `QueueEngineService.findQueueAndVerifyOwner` | **요청당 1회**, 캐시 없음 | 20회 그대로 |
| ② | `BatchProcessor.getMaxCapacity` | (틱 × 큐)당 1회 | 20 → **3회로 접힘** |

```
getMaxCapacity/s (WAS당) = min(enqueue rps,  50 × 활성 큐 수)
findQueueAndVerifyOwner/s = enqueue rps        ← 항상 이쪽이 크거나 같다
```

20ms 주기의 영향은 실재한다(1000ms 대비 50배). **하지만 ②를 없애도 master 부하는 6% 준다.**

🔑 **진짜 문장은 이것이다: enqueue 핫패스가 요청당 MySQL SELECT 1회를 탄다.**
10만/s 버스트에서 먼저 포화되는 것은 Redis도 Kafka도 아니고 **MySQL master와 커넥션 풀**이다.
(**미측정** — 로컬에서 10만/s를 만들 수 없다. "요청당 1회"는 실측이고 그 위의 계산은 산술이다.)

게다가 ①과 ②는 **같은 `queues` 행을 읽는다** — 요청 스레드가 `Queue`(maxCapacity 포함)를 이미
손에 쥔 뒤, 드레인 스레드가 같은 행을 다시 읽는다.

**목표 부하 200 rps에서 master SELECT 200/s. 지금 문제가 아니다.** §4대로 여기서 멈춘다.
검토했다가 기각한 것: `getMaxCapacity` 제거(포트 시그니처 변경 = 도메인 계약, 되돌리기 비싸다),
`findQueueAndVerifyOwner` 캐시(`Queue.status`가 가변이라 **TTL만큼 PAUSED가 안 먹는다**).

---

## 4. replica로 옮길 수 있는 읽기 — 사실상 없다

전수로 "쓰기 직후 읽기인가"부터 답했다.

| 후보 | 쓰기 직후? | 판정 |
|---|---|---|
| `findQueueAndVerifyOwner` (enqueue/admit/verify/complete 전부) | 예 — 큐 생성 직후 첫 enqueue | ❌ `QUEUE_NOT_FOUND` 404 (D-1과 같은 사고) |
| `BatchProcessor.getMaxCapacity` | 예 | ❌ `IllegalStateException` → 그 틱 그룹 전체 실패 |
| `ApiKeyAuthenticationFilter.findByKeyHash` | 예 — 키 발급 직후 첫 호출 | ❌ stale이 **401**이다. fail-closed라 더 나쁘다 |
| `RateLimitFilter.loadTenant` | 예 — 가입 직후 | ❌ (이미 replica다. D-2) |
| verify DB 폴백 `findAdmittedByAdmitToken` | 예 — admit 60초 안 | ❌ 정상 입장자 404 |
| complete DB 300초 창 | 예 | ❌ [[complete-db-first-fallback]]의 폴백 근거가 무너진다 |
| `ReconcileJob`의 `COUNT`류 | 아니오 — `SETTLE_SECONDS` 정착 창이 있다 | ⭕ 유일한 안전 후보. **5분에 큐당 2쿼리라 옮겨도 얻는 게 없다** |

**이 서비스의 DB 읽기는 구조적으로 전부 쓰기 직후다.** Redis가 먼저 쓰이고 DB는 Kafka를 거쳐
늦게 들어오는 설계(§73)라서, DB 읽기가 남은 자리는 죄다 "방금 벌어진 일"을 확인하는 자리다.
**"replica로 옮겨 master 부하를 줄인다"는 이 아키텍처에서 성립하지 않는다.**

---

## 5. 문서 대조표

| 위치 | 원문 요지 | 판정 | 조치 |
|---|---|---|---|
| `AdmitApiTest:389-391` | "verify는 `@Transactional(readOnly = true)`라 Replica로 라우팅" | 🔴 **거짓** — `QueueEngineService`에 `readOnly=true`가 0곳이고 verify엔 어노테이션 자체가 없다 | ✅ **정정** |
| `MONITORING_DESIGN.md:592` | "읽기 분산 정상 여부"를 잰다 | 🔴 **거짓 전제** — 잴 대상이 없다 | ✅ **정정** |
| `MONITORING_DESIGN.md:619` | "Read 분산 최적화 → **Replica 추가 결정**" | 🔴 **거짓 전제** — replica를 늘려도 안 바뀐다. master가 병목인데 replica를 늘리는 판단으로 이어진다 | ✅ **정정** |
| `dashboards/README.md:38` | "readOnly가 Replica로 가므로 이 값이 커지면 읽기가 낡은 데이터를 본다" | 🟠 기전은 참, **함의가 오도** — 실제 대상은 배치 `findAll()`과 `loadTenant` 둘뿐 | ⬜ 미조치 |
| `FRS_final.md:1100-1116` | Read/Write 분리 표 + 2026-08-27 정정 | ✅ 참, 단 **불완전** — 실제 replica 경로 2개가 표에 없다 | ⬜ 미조치 |
| `INFRA_SETUP.md:1292-1297` | "인증 조회가 replica라 401 위장"은 거짓 / replica는 DR 자산 | ✅ 참, 단 **불완전** — D-2가 같은 필터 체인에 있다 | ⬜ D-2와 함께 |
| `QueueJpaAdapter:65-77` | `findAll()`은 replica 등 | ✅ **참** (batch replica 172 / master 20) | — |
| `BillingJdbcAdapter:203-212` | "`readOnly=true`를 **붙이면** replica로 간다. 그래서 안 붙인다" | ✅ **참** (조건문이고 조건이 맞다) | — |
| `DECISIONS.md:1506` | "Polling SELECT는 Read Replica로 분산" | 🔴 **거짓** — 폴링은 DB를 아예 안 읽는다(실측 0/0) | ⚠️ **이력이라 수정 안 함** |
| `DECISIONS §29 / §27` | "Read(SELECT) → Read Replica" / 용량표 "Replica 조회 ~200건/초" | 🔴 명목이 실제와 반대. 용량표에 **enqueue 요청당 master SELECT 1회가 없다** | ⚠️ **이력.** 각주 사안 → planner |
| `doc/blog/mysql-replication-routing/01:165` | `getQueues(Long tenantId)`가 readOnly → replica | 🔴 그런 메서드가 없다 | ⚠️ **로컬 전용, 수정 안 함** |

---

## 6. 이번에 조치한 것

| # | 파일 | 변경 |
|---|---|---|
| 1 | `QueueService.java` | `@Transactional(readOnly = true)` **삭제** + 이유·실측·함정을 javadoc으로 |
| 2 | `AdmitApiTest.java` | 거짓 주석 정정. **진짜 이유(Kafka 12초 커넥션 점유)로 교체**하고, 구 근거가 왜 거짓인지 + "되붙이면 무엇이 되살아나는지" 명시 |
| 3 | `MONITORING_DESIGN.md` | 죽은 지표 2곳에 취소선 + 실측 근거. Replication Lag은 **DR 건전성 지표로만** 읽도록 |

**검증**: 전체 **444건 실패 0** · create→GET **0/30**(수정 전 1/12·2/8) · api 3대 실기동

---

## 7. 남은 것 — 사용자 판단 대상

| # | 항목 | 필요한 선행 |
|---|---|---|
| D-2 | Rate Limit 두 번째 입구 | **replica 다운 시 실패 모드 실측** → security / infra |
| D-3 | 배치가 replica 가용성에 묶임 | `findAll()`을 master로 보낼지. 라우팅 예측이 **추론**이라 바꾸면 로그 확인 필수. 복제 지연 알람 보류 사유 재검토 → monitoring |
| D-4 | 풀 50:50 | **prod `max_connections` 확인이 선행** → infra |
| — | `DECISIONS §29·§26·§27` 각주 | 이력 문서 원칙(본문 수정 금지) → planner |
| — | `dashboards/README.md:38` · `FRS_final.md` 표 보완 | — |

**§4대로 대응책을 확정하지 않았다.** 결함과 선택지까지이고, 확대 여부의 최종 판단은 사용자다.

---

## 8. 이 감사가 남기는 절차 교훈

1. **감사 결과는 파일로 남긴다.** 6건이 사라져 "다시 보라"는 지시만 남았던 것이 이번 혼선의
   원인이다. → 이 문서
2. **`--scan`은 접속한 노드만 훑는다** (`-c`를 붙여도 SCAN은 리다이렉트되지 않는다).
   lead가 `findById` 라우팅을 두 번 잘못 측정했다가 캐시가 실제로 비워졌는지 확인하고서야
   dba 측정과 일치했다. **0이라는 숫자를 그대로 믿지 마라.**
3. **에이전트 보고를 대조하라.** dba와 architect가 `loadTenant`의 현재 라우팅을 두고 갈렸고,
   실측한 dba가 맞았다. → [[agent-review-workflow]]
