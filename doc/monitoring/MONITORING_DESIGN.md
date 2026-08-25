# Queue Platform Monitoring — 설계 문서

모니터링 시스템의 목적, 기본 개념, 측정 카테고리, 의사결정 흐름.

## 목차

1. [목적과 원칙](#목적과-원칙)
2. [Prometheus 기본 개념](#prometheus-기본-개념)
3. [Grafana 기본 개념](#grafana-기본-개념)
4. [핵심 운영 개념](#핵심-운영-개념)
5. [위기감지 카테고리](#위기감지-카테고리)
6. [카테고리 1: API 사용량](#카테고리-1-api-사용량-측정)
7. [카테고리 2: 인프라 리소스](#카테고리-2-인프라-리소스-측정)
8. [카테고리 3: JVM + 애플리케이션](#카테고리-3-jvm--애플리케이션-측정)
9. [카테고리 4: Queue 비즈니스 정합성](#카테고리-4-queue-비즈니스-정합성-측정)

---

## 목적과 원칙

### 1차 목적 — 위기감지

```
시스템에 문제가 생기기 전, 또는 즉시 인지
→ 운영 안정성
→ 사고 시간 최소화 (MTTR ↓)
```

### 2차 목적 — 비즈니스 인사이트

```
Tenant별 사용 패턴 파악
→ 과도 사용 Tenant 식별
→ Rate Limiter 정책 결정
→ 용량 계획 (Capacity Planning)
```

### 3차 목적 — 성능 분석

```
응답 시간, 처리량 추이
→ 병목 식별
→ 최적화 기회 발견
```

### 핵심 원칙

```
1. Pull 방식 (Prometheus가 scrape)
   → 애플리케이션 단순화

2. Micrometer 추상화
   → 백엔드 교체 가능 (Datadog, New Relic 등)

3. 라벨 차원화
   → 다차원 분석
   → Cardinality 신중

4. 점진적 확장
   → Phase별 진행
   → 우선순위 명확

5. 모니터링 = 진단 도구
   → 위치까지 알려주진 않음
   → Heap Dump, Profiler 등 보조 도구 필수

6. 필요한 시점에 통합
   → 별도 작업으로 분리 X
   → 새 기능 = 새 메트릭 (한 묶음)
```

---

## Prometheus 기본 개념

### Prometheus란?

```
[정의]
오픈소스 모니터링 시스템 + 시계열 데이터베이스
CNCF 졸업 프로젝트 (Kubernetes 다음으로 두 번째)

[핵심 특징]
- Pull 방식 (Prometheus가 대상에서 메트릭 가져옴)
- 시계열 데이터베이스 내장
- PromQL (전용 쿼리 언어)
- 라벨 기반 다차원 데이터
```

### Pull 방식 vs Push 방식

```
[Pull 방식 — Prometheus 채택]
Prometheus → 애플리케이션의 /metrics endpoint 호출
            → 메트릭 가져감 (15초마다 등 주기적)

장점:
- 애플리케이션 단순화 (노출만 하면 됨)
- 대상 관리 중앙화 (Prometheus가 관리)
- 헬스체크 자동 (못 가져오면 DOWN)

[Push 방식 — 대안 (Datadog 등)]
애플리케이션 → 모니터링 서버로 메트릭 전송

장점:
- 방화벽 통과 용이
- 짧은 작업 (Batch Job 등)에 유리
```

### 시계열 데이터 (Time Series)

```
[기본 단위]
시간에 따른 숫자 값의 연속

[예시]
시간 12:00:15: jvm_memory_used_bytes = 5,242,880
시간 12:00:30: jvm_memory_used_bytes = 5,287,936
시간 12:00:45: jvm_memory_used_bytes = 5,331,968
...

→ 시간별로 어떻게 변하는지 추적
→ 그래프로 시각화 가능
```

### 메트릭과 라벨

```
[메트릭] http_server_requests_seconds_count
[라벨] method, status, uri 등

[저장되는 형태]
http_server_requests_seconds_count{method="GET", status="200", uri="/api/users"} = 1500
http_server_requests_seconds_count{method="POST", status="200", uri="/api/users"} = 80
http_server_requests_seconds_count{method="GET", status="404", uri="/api/orders"} = 5
...

[각 라벨 조합 = 별도 시계열]
→ 다차원 분석 가능
```

### 메트릭 종류 (Metric Types)

```
[Counter (카운터)]
누적되는 값. 단조 증가만.
예: 총 요청 수, 총 에러 수
PromQL: rate() 함수로 초당 증가율 계산

[Gauge (게이지)]
현재 값. 오르고 내릴 수 있음.
예: 현재 메모리 사용량, 현재 활성 연결 수

[Histogram (히스토그램)]
분포 측정. 버킷별 카운트.
예: 응답 시간 분포 (50ms, 100ms, 500ms 버킷)
PromQL: histogram_quantile()로 p95, p99 등 계산

[Summary]
Histogram과 비슷하지만 클라이언트에서 분위수 계산
→ 보통 Histogram 권장 (집계 가능)
```

### PromQL — 쿼리 언어

```
[기본 쿼리]
up                    # 현재 UP/DOWN 상태
jvm_memory_used_bytes  # 모든 시계열

[필터링]
jvm_memory_used_bytes{area="heap"}

[집계]
sum(jvm_memory_used_bytes{area="heap"})
sum by (area) (jvm_memory_used_bytes)

[Counter는 rate로 변환]
rate(http_server_requests_seconds_count[1m])
→ 분당 평균 RPS

[히스토그램에서 분위수]
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))
→ p95 응답 시간

[Top N]
topk(10, sum by (tenant_id) (rate(tenant_api_calls_total[5m])))
→ Top 10 Tenant
```

### Scrape (수집)

```
[동작]
Prometheus가 대상의 /actuator/prometheus 같은 endpoint를 주기적으로 호출
→ 메트릭 텍스트 가져옴
→ 시계열 DB에 저장

[주기]
scrape_interval: 보통 15초
→ 15초마다 모든 대상에서 메트릭 수집

[targets]
prometheus.yml에 등록된 대상들
- queue-platform-api (Spring Boot)
- mysqld_exporter (MySQL)
- redis_exporter (Redis)
- 등
```

### Exporter

```
[정의]
Prometheus 형식으로 메트릭을 노출하는 도구
대상이 직접 Prometheus 형식 지원 안 할 때 사용

[종류]
- node_exporter: 시스템 리소스 (CPU, 메모리, 디스크)
- mysqld_exporter: MySQL 메트릭
- redis_exporter: Redis 메트릭
- kafka_exporter: Kafka 메트릭

[사용 패턴]
Exporter가 MySQL/Redis 등에서 메트릭 조회
→ Prometheus 형식으로 노출 (포트 9104, 9121 등)
→ Prometheus가 Exporter에서 scrape
```

---

## Grafana 기본 개념

### Grafana란?

```
[정의]
오픈소스 시각화 + 대시보드 도구
다양한 데이터 소스 지원 (Prometheus, MySQL, Elasticsearch 등)

[역할]
Prometheus는 데이터 저장
Grafana는 그 데이터를 시각화
→ 두 도구의 조합이 표준
```

### 데이터 소스 (Data Source)

```
[정의]
Grafana가 데이터를 가져올 곳

[설정]
Connections → Data sources → Add new
- Prometheus
- MySQL
- Elasticsearch
- CloudWatch
- 등

[Queue Platform]
Prometheus 1개 → 충분
URL: http://localhost:9090
```

### 대시보드 (Dashboard)

```
[정의]
여러 패널(그래프)을 모아놓은 화면
하나의 주제를 한눈에 보기 위함

[Queue Platform 권장 대시보드]
1. System Overview (전체 상태)
2. Tenant Activity (Tenant 사용 패턴)
3. API Performance (API 응답 시간)
4. JVM Internals (메모리, GC, VT)
5. Queue Business (Queue lifecycle)
6. Infrastructure (MySQL, Redis, Kafka)
```

### 패널 (Panel)

```
[정의]
대시보드의 개별 그래프 또는 시각화

[종류]
- Time Series: 시간별 추이 그래프
- Stat: 단일 숫자 (현재 값)
- Gauge: 게이지 그래프
- Bar Chart: 막대 그래프
- Pie Chart: 원형 그래프
- Heatmap: 히트맵
- Table: 표
- Logs: 로그 표시

[Queue Platform 패널 예]
- Time Series: 시간별 RPS
- Stat: 현재 활성 Queue 수
- Bar Chart: Top 10 Tenant
- Heatmap: 응답 시간 분포
```

### 변수 (Variables)

```
[정의]
대시보드에서 동적으로 값을 선택하는 드롭다운

[예시]
$tenant_id 변수:
- 드롭다운에서 "t-001", "t-002" 등 선택
- 패널 쿼리에서 활용:
  sum by (tenant_id) (rate(tenant_api_calls_total{tenant_id="$tenant_id"}[5m]))
- "All" 옵션으로 전체 보기 가능

[활용]
- 전체 + 개별 대시보드 통합
- Tenant 모니터링 전략의 핵심
```

### Explore — 임시 쿼리

```
[정의]
대시보드 만들기 전에 임시로 쿼리 시도하는 도구

[활용]
- 새 PromQL 검증
- 메트릭 라벨 확인
- 데이터 소스 연결 확인

[접근]
좌측 메뉴 → Explore (나침반 아이콘)
```

### Import — 공식 대시보드

```
[정의]
다른 사용자가 만든 대시보드 가져오기

[Queue Platform 추천]
- 4701: JVM (Micrometer)
- 11378: Spring Boot Statistics
- 12900: Spring Boot 2.1 Statistics

[방법]
Dashboards → New → Import → ID 입력
```

---

## 핵심 운영 개념

### Cardinality (카디널리티)

```
[정의]
메트릭의 유일한 시계열 개수
= 라벨 값 조합의 수

[예시]
http_requests_total{method, status, endpoint}
- method: 4개 (GET, POST, PUT, DELETE)
- status: 5개
- endpoint: 50개

Cardinality = 4 × 5 × 50 = 1,000 시계열
```

### 왜 중요한가?

```
[Prometheus 부하]
시계열 1개당 메모리 1-3 KB 할당
- 1,000 시계열: 1-3 MB (안전)
- 100,000 시계열: 100-300 MB (안전)
- 1,000,000 시계열: 1-3 GB (주의)
- 10,000,000 시계열: 10-30 GB (위험)

[쿼리 성능]
시계열 많을수록 PromQL 느려짐

[대시보드 가독성]
1,000개 라인 그래프 → 의미 없음
```

### 위험 라벨 패턴

```
[안티 패턴]
- user_id: 사용자 수만큼 시계열
- request_id: UUID, 무한 증가
- 동적 URL: /users/123 → 모두 다른 라벨
- timestamp: 무한대

[해결]
- 라벨 분리 (의도가 다른 메트릭은 별도)
- URL 패턴화: /users/{id}
- ID는 라벨 X (로그로 추적)
```

### Cardinality 관리 전략

```
[전략 1: 라벨 분리]
같은 데이터를 두 가지 차원으로
- 메트릭 A: tenant_id + api_category (적은 라벨)
- 메트릭 B: endpoint + method (Tenant 라벨 없음)
→ 곱셈 → 덧셈

[전략 2: URL 패턴화]
/api/users/123 → /api/users/{id}

[전략 3: Top N 표시]
topk(10, ...) — 상위만 시각화

[전략 4: Recording Rules]
사전 집계 (매 1분마다)
→ 대시보드는 사전 계산값 조회
```

### RPS (Requests Per Second)

```
[정의]
초당 요청 수
부하 측정의 기본 단위

[Prometheus 측정]
rate(http_server_requests_seconds_count[5m])
→ 5분 평균 RPS

[현실 감각]
1 RPS: 개인 블로그
100 RPS: 중견 서비스
1,000 RPS: 대형 서비스
10,000+ RPS: 카카오톡 수준
```

### 정상 패턴 vs 비정상 패턴

```
[톱니파 패턴]
값이 오르락내리락
예: Heap 사용량 — Eden 가득 차면 GC로 회수
→ 정상

[우상향 패턴]
값이 꾸준히 증가
예: Old Gen이 회수 안 되고 증가
→ 메모리 누수 의심

[급증 패턴]
짧은 시간에 큰 변화
예: 트래픽 갑작스러운 증가
→ 캠페인 or 공격 의심
```

### 모니터링은 "감지" 역할

```
[모니터링이 알려주는 것]
✅ 어떤 메트릭이 이상한가
✅ 언제부터 이상해졌는가
✅ 어느 정도 심각한가

[모니터링이 알려주지 못하는 것]
❌ 정확한 코드 위치
❌ 객체별 메모리 사용
❌ 메서드별 시간 분석

[보조 도구 필요]
- Heap Dump + Eclipse MAT (메모리 누수 위치)
- JFR (Java Flight Recorder) (성능 분석)
- 분산 추적 도구 (요청 흐름 추적, 향후)
```

---

## 위기감지 카테고리

4개 카테고리로 분류 (보안 카테고리는 Rate Limiter 구축 후 추가 예정).

| # | 카테고리 | 핵심 메트릭 | 대응 시점 |
|---|---------|------------|----------|
| 1 | API 사용량 | RPS, Tenant별 사용 패턴 | 5분 평균 임계치 초과 |
| 2 | 인프라 리소스 | MySQL/Redis/Kafka | 80% 사용률 |
| 3 | JVM + 애플리케이션 | Heap, GC, Virtual Thread | Heap 80%, GC pause ↑ |
| 4 | Queue 비즈니스 정합성 | Queue lifecycle, 정합성 | 발생 즉시 |

---

## 카테고리 1: API 사용량 측정

### 1-1. Tenant 차원 측정

- 측정 이유
  - Tenant 단위 의사결정 지원
    - Rate Limiter 정책 결정 (특정 Tenant 한도 설정)
    - 영업 컨택 우선순위 (헤비유저 = 잠재 유료 고객)
    - Tenant별 사용 패턴 파악
- 문제
  - 모든 Tenant를 Grafana에 노출 시 모니터링 보기 어려움
  - API 전체 측정하게 되면 인증 쪽의 문제인지 Queue의 문제인지 확인이 어려움
  - Cardinality 폭발 위험
    - tenant_id × endpoint × method = 잠재적 100만 시계열
    - Prometheus 메모리 부하 ↑
- 대안
  - Top 10의 Tenant만 노출하는 방향
  - API 카테고리별 측정 (auth / queue / admin)
  - 라벨 분리로 Cardinality 관리
    - Tenant 차원: tenant_id + api_category (적은 라벨)
    - 세부 차원은 별도 메트릭 (1-2)에서
- 이슈
  - Top 10에 대해서는 어떻게 노출시킬 것인가?
    - 결정: 5분 평균 RPS 기준 topk(10, ...)
  - API 카테고리 분류 기준은?
    - 결정: URL 패턴 기반 자동 분류
      - /api/v1/tenants/* → auth
      - /api/v1/queues/* → queue
      - /api/v1/admin/* → admin
- 활용방안
  - 어떤 Tenant가 어떤 시간에 API 사용이 높은지 확인할 수 있음
  - 운영 시 대응을 위해 서버 증설 작업을 미리 해놓을 수 있음 (예측)
  - 이전 모니터링 기록을 바탕으로 Tenant에 Queue 용량을 제안할 수 있음
  - Rate Limiter 정책 입력
    - 평소 평균의 10배 이상 호출 → 의심 (공격 / 버그)
    - 정상 성장 vs 비정상 식별 → 한도 조정

---

### 1-2. API 차원 측정

- 측정 이유
  - 시스템 단위 의사결정 지원
    - 어느 endpoint에 캐싱 도입할지
    - 인프라 증설 시점 판단
  - Tenant 차원(1-1)으로 답할 수 없는 질문에 답
    - 어느 endpoint가 가장 핫한가
    - POST/GET 비율
    - 시간대별 endpoint 패턴
- 문제
  - Tenant 정보가 없음 → 누가 부하 발생시키는지 모름
  - 보완: Tenant 차원 메트릭(1-1)으로 식별 가능
- 대안
  - Tenant 라벨 없는 별도 메트릭으로 분리
    - 라벨: endpoint + method
    - Cardinality 안전 (50 × 4 = 200 시계열)
- 이슈
  - Endpoint 패턴화 기준
    - 결정: Spring Boot Actuator 자동 패턴 사용
      - /api/v1/queues/q-018f6c8e-... → /api/v1/queues/{id}
    - Cardinality 무한 증가 방지
- 활용방안
  - 어느 endpoint가 시스템 부하 주는지 식별
  - 인프라 증설 시점 판단 (전체 RPS 추세)
  - API 캐싱 전략 (GET 많은 endpoint 우선)
  - 비즈니스 KPI (총 API 호출 수, 시간대별 트래픽 패턴)

---

### 메트릭 분리 원칙

두 차원의 메트릭은 같은 API 호출 이벤트를 두 가지 관점으로 측정.

- Tenant 차원: Tenant 단위 의사결정 (비즈니스/영업/Rate Limiter)
- API 차원: 시스템 단위 의사결정 (인프라/캐싱)

분리하는 이유:
- 의사결정 종류가 다름 → 메트릭도 다름
- Cardinality 관리
  - 합치면: tenant × endpoint × method = 잠재 위험
  - 분리: 각 메트릭이 안전한 범위
- 같은 사건을 두 번 카운트하지만, 답하는 질문이 본질적으로 다름

---

## 카테고리 2: 인프라 리소스 측정

### 2-1. MySQL 측정

- 측정 이유
  - DB 부하 상태 파악
    - Connection Pool 포화 여부 (커넥션 부족 → 요청 대기)
    - Buffer Pool 사용 효율 (메모리 부족 → 디스크 I/O ↑)
    - Slow Query 발생 추세 (성능 저하 원인 식별)
  - Master-Replica 동기화 상태
    - Replication Lag (Replica가 Master 따라가는지)
    - 읽기 분산 정상 여부
- 문제
  - DB 응답 지연이 발생해도 원인 추정 어려움
    - 쿼리 자체가 느린가
    - 커넥션 부족인가
    - 디스크 I/O 병목인가
  - Replication Lag 미인지 시 데이터 정합성 문제
    - Write Master → Read Replica 직후 데이터 없음 보일 수 있음
- 대안
  - mysqld_exporter 설치 (Prometheus용 MySQL Exporter)
  - 핵심 메트릭만 우선 수집
    - Connection Pool 사용률
    - Buffer Pool 사용률
    - Slow Query rate
    - Replication Lag
- 이슈
  - Master / Replica 각각 따로 측정?
    - 결정: 두 개 Exporter 띄워서 분리 측정
      - Master (3306) → mysqld_exporter:9104
      - Replica (3307) → mysqld_exporter:9105
    - 라벨로 instance 구분
  - Slow Query 임계치?
    - 결정: MySQL의 long_query_time 설정 (기본 10초, 1초로 조정 검토)
- 활용방안
  - 응답 시간 급증 시 원인 추적 (DB? 앱?)
  - 인덱스 추가 필요 시점 (Slow Query rate 추세)
  - DB 인스턴스 스케일업 시점 (Buffer Pool / Connection 포화)
  - Read 분산 최적화 (Replication Lag 추세로 Replica 추가 결정)

---

### 2-2. Redis 클러스터 측정 (Master + Slave + Sentinel)

- 측정 이유
  - Redis 부하 상태 파악
    - 메모리 사용량
    - 처리량 (초당 Redis 명령 수)
    - 캐시 적중률 (Hit/Miss Rate)
  - 가용성 상태 추적
    - Sentinel Failover 발생 여부
    - Master 변경 추적
  - 캐시 무효화 감지
    - Eviction 발생 (메모리 부족으로 기존 키 강제 삭제)
    - Eviction = 캐시 적중률 저하 → DB 부하 ↑
- 문제
  - Redis 메모리 가득 차면 Eviction 발생
    - 기존 키가 강제로 삭제됨
    - 캐시 미스 ↑ → DB로 폴백
    - 미리 감지 못 하면 장애로 이어짐
  - Sentinel Failover 발생 시점을 모르면 운영 대응 늦음
    - Master 변경 → 클라이언트 재연결 필요
- 대안
  - redis_exporter 설치 (Prometheus용 Redis Exporter)
  - 측정 대상 분리
    - 성능: Master(6379), Slave(6380, 6381) — 각 인스턴스 메트릭
    - 가용성: Sentinel(26379, 26380, 26381) — Failover 이벤트
  - 핵심 메트릭만 우선 수집
    - Memory 사용률 (Eviction 사전 감지)
    - 초당 명령 수 (부하)
    - Hit Rate (캐시 적중률)
    - Connected Clients
    - Replication 상태
- 이슈
  - Master / Slave / Sentinel 측정 방식?
    - 결정: redis_exporter 1개로 여러 인스턴스 측정
      - 라벨로 인스턴스 구분 (master / slave-1 / slave-2)
      - Sentinel은 INFO sentinel 명령으로 별도 수집
  - Failover 알람 즉시 보낼지?
    - 결정: 알람 우선순위 높음 (Critical)
      - Sentinel +switch-master 이벤트 감지 → 즉시 알람
  - Eviction 임계치?
    - 결정: Eviction 발생 자체가 위험 신호
      - Eviction rate > 0 (지속 발생) → 경고
      - 정상 운영 시 Eviction 발생하면 안 됨
- 활용방안
  - 캐시 메모리 부족 시 인스턴스 스케일업 또는 정책 조정
  - Hit Rate 낮으면 캐시 키 설계 재검토
  - Failover 추세로 Master 인스턴스 안정성 확인
  - 운영 사후 분석 (Failover 발생 → 원인 추적)

---

### 2-3. Kafka 측정 (Sprint 8+)

- 측정 이유
  - 메시지 처리 지연 파악
    - Consumer Lag (Producer 속도 vs Consumer 속도)
    - 처리량 (메시지/초)
  - Broker 상태
    - Partition 분포
    - 디스크 사용량
- 문제
  - Consumer Lag 미인지 시 메시지 처리 지연 누적
    - 큐 토큰 발급 → DB INSERT 비동기 처리 지연
    - 사용자에겐 정상 응답이지만 실제 데이터 미반영
  - Broker 디스크 가득 차면 메시지 손실 가능
- 대안
  - kafka_exporter 설치 (Prometheus용 Kafka Exporter)
  - 핵심 메트릭만 우선 수집
    - Consumer Lag (가장 중요)
    - Topic 별 메시지 수
    - Broker 디스크 사용량
- 이슈
  - 모니터링 리소스 부담은?
    - Queue Platform 토픽 규모: 4-10개
    - 파티션: 토픽당 4-10개
    - Cardinality: 수십 시계열
    - 결정: 운영 부담 거의 없음, 부담 없이 도입 가능
  - Consumer Group 단위로 측정?
    - 결정: Consumer Group별 Lag 측정
      - Topic 단위 합산도 같이 측정
  - Lag 임계치?
    - 결정: 1,000 메시지 초과 → 경고
    - 단, 정확한 수치는 운영 후 패턴 보고 조정
- 활용방안
  - Consumer 스케일아웃 시점 결정
  - Producer 트래픽 급증 식별
  - Broker 인스턴스 스케일업 시점
  - 메시지 손실 사고 사전 방지

---

## 카테고리 3: JVM + 애플리케이션 측정

### 3-1. Heap 메모리 측정

- 측정 이유
  - 메모리 누수 감지
    - Old Gen 사용량의 장기 추세
    - Full GC 후에도 회복 안 되는 패턴
  - OOM (Out Of Memory) 사전 방지
    - 임계치 도달 전 알람
- 문제
  - 모니터링만으로는 "누수 있음"만 감지
    - 어느 객체가 누수인지는 알 수 없음
    - Heap Dump 분석 필요 (Eclipse MAT 같은 도구)
  - 정상 패턴 vs 비정상 패턴 구분 어려움
    - 톱니파 (정상): GC로 메모리 회수, 오르락내리락
    - 우상향 (누수): GC 후에도 메모리 감소 안 함
- 대안
  - Spring Boot Actuator의 jvm_memory_* 메트릭 활용
    - 별도 Exporter 불필요 (자동 노출)
  - 24시간 추세로 누수 감지
    - deriv() 함수로 기울기 계산
    - 양수 지속 → 누수 의심
  - OOM 시 자동 Heap Dump
    - JVM 옵션: -XX:+HeapDumpOnOutOfMemoryError
- 이슈
  - 임계치 어떻게 설정?
    - 결정:
      - Heap 사용률 80% → 경고
      - Heap 사용률 90% → 위험
      - Old Gen 24시간 우상향 → 누수 의심 알람
  - 누수 의심 시 운영 대응 절차는?
    - 결정: Heap Dump 생성 → Eclipse MAT 분석
      - jcmd <PID> GC.heap_dump
      - 또는 /actuator/heapdump endpoint
- 활용방안
  - 운영 중 메모리 누수 조기 발견
  - OOM 사고 사전 방지
  - 인스턴스 메모리 적정화 (스케일업/다운 결정)
  - 사후 분석 (누수 원인 추적 → 코드 개선)

---

### 3-2. GC (Garbage Collection) 측정

- 측정 이유
  - GC pause time이 응답 시간에 직접 영향
    - GC 발생 시 STW (Stop The World, 모든 스레드 일시 정지)
    - 사용자 응답 지연
  - 메모리 누수 동반 시그널
    - Full GC 빈번 발생 → 메모리 부족 가능성
- 문제
  - GC pause가 길어도 평소엔 안 보일 수 있음
    - 평균 응답 시간엔 묻힘
    - p95, p99에서만 드러남
  - GC 종류별 특성 다름
    - G1 (기본), ZGC, Parallel 각각 트레이드오프
- 대안
  - 자동 메트릭 (jvm_gc_*) 활용
  - p99 pause time 임계치 모니터링
  - GC overhead (전체 시간 중 GC 비율) 추적
- 이슈
  - GC pause 줄이는 방법?
    - 잘못된 접근: System.gc() 강제 호출 (안티 패턴)
    - 올바른 접근:
      - Heap 크기 적정화 (너무 크면 한 번에 오래)
      - GC 옵션 튜닝 (-XX:MaxGCPauseMillis)
      - 메모리 누수 제거 (Full GC 빈번 원인)
      - 객체 생성 최소화
      - 필요 시 GC 종류 변경 (ZGC 등)
  - 임계치?
    - 결정:
      - 평균 GC pause 500ms → 경고
      - p99 GC pause 1초 → 위험
      - Full GC > 분당 1회 → 위험
- 활용방안
  - 응답 시간 급증의 GC 원인 식별
  - GC 튜닝 의사결정 (Heap 크기, GC 종류)
  - 메모리 누수 동반 시그널로 활용
  - 운영 환경 GC 설정 검증 (운영 전 부하 테스트)

---

### 3-3. Virtual Thread 측정 (Java 21 특화)

- 측정 이유
  - Spring Boot 3.x + Java 21 환경 특화
    - Spring MVC + Virtual Thread per request
    - 각 HTTP 요청이 Virtual Thread로 처리
  - Spring Boot 기본 메트릭 한계
    - jvm_threads_*는 Platform Thread만 추적
    - Virtual Thread는 미포함
  - Pinning 감지
    - Pinning = VT가 Carrier Thread에 고정되어 unmount 안 되는 상황
    - synchronized 블록 안에서 IO 호출 시 발생 (안티 패턴)
    - Carrier Thread 자원 낭비 → 다른 VT 처리 지연
- 문제
  - VT 누수 감지 불가능 (기본 메트릭으로)
    - 무한 생성되어도 안 보임
  - Carrier Thread Pool 상태 모름
    - VT가 Pinning 되어도 인지 못 함
- 대안
  - Custom 메트릭 작성
    - VirtualThreadMetrics 클래스
    - Thread.getAllStackTraces()에서 isVirtual() 필터링
  - 주요 메트릭
    - jvm.threads.virtual.live: VT 수
    - jvm.threads.platform.live: Platform Thread 수
    - jvm.threads.carrier.parallelism: Carrier Pool 크기
    - jvm.threads.carrier.active: 활성 Carrier
  - Pinning 감지 (선택)
    - JFR (Java Flight Recorder, JDK 내장 성능 분석 도구)의
      jdk.VirtualThreadPinned 이벤트 구독
    - JFR은 비행기 블랙박스처럼 JVM 이벤트 기록
    - 운영 오버헤드 < 1% (가벼움)
    - jvm.virtualthread.pinned.total Counter로 노출
- 이슈
  - VT 카운트 성능 부담?
    - Thread.getAllStackTraces()는 모든 스레드 정보 수집
    - VT가 수만 개면 비용 ↑
    - 결정: Prometheus scrape 주기 (15초)에만 측정 → 부담 적음
  - Pinning 발생 시 대응?
    - 결정: 코드에서 synchronized + IO 패턴 찾기
      - synchronized 블록 안에서 DB/HTTP 호출 X
      - ReentrantLock으로 교체 (VT가 정상 unmount 가능)
- 활용방안
  - VT 누수 조기 발견 (정상 트래픽 대비 비정상 시계열)
  - Pinning 발생 코드 식별 → 코드 개선
  - Carrier Thread Pool 적정 크기 결정
  - Java 21 + Virtual Thread 운영 경험 (면접 자산)

---

### 3-4. Tomcat 측정

- 측정 이유
  - HTTP 처리 스레드 상태 파악
    - 사용 중인 스레드 수
    - 대기 중인 요청 수
- 문제
  - Virtual Thread 모드 사용 시 의미 약함
    - Spring Boot가 VT per request로 변경
    - Tomcat의 전통적 thread pool 사용 안 함
  - Tomcat 메트릭만 보면 실제 처리 상태 모름
    - VT 메트릭(3-3)과 같이 봐야 정확
- 대안
  - 자동 메트릭 (tomcat_threads_*) 활용
  - VT 메트릭(3-3)과 같이 분석
- 이슈
  - VT 모드에서도 의미 있는가?
    - 결정: 보조 지표로만 사용
      - 주요 지표는 3-3 (Virtual Thread)
      - Tomcat 자체 안정성 확인용
- 활용방안
  - 시스템 부하 보조 지표
  - VT가 안 켜졌을 때 비교 분석
  - Tomcat 자체 이슈 (스레드 풀 설정 등) 확인

---

### 카테고리 3 통합 원칙

JVM + 애플리케이션 측정은 시스템 안정성의 핵심:

- Heap (3-1) + GC (3-2): 메모리 + 성능 함께 봐야 정확
  - GC 빈번 + Heap 우상향 = 메모리 누수
  - GC 정상 + Heap 톱니파 = 정상
- Virtual Thread (3-3) + Tomcat (3-4): 둘 다 봐야 처리 상태 정확
  - Spring Boot 3.x + Java 21 환경의 특수성

운영 시 핵심:
- 모니터링은 "감지" 역할
- 정밀 분석은 Heap Dump / JFR / Profiler 도구로 보완
- 안티 패턴 (System.gc() 강제) 회피

---

## 카테고리 4: Queue 비즈니스 정합성 측정

Queue Platform의 핵심 비즈니스인 Queue의 전체 lifecycle을 추적.
다른 도메인(인증, 보안)은 별도로 분류 (현재 단계에서는 제외).

### 4-1. Queue 생성 / 삭제 측정

- 측정 이유
  - Queue 운영 현황 파악
    - 현재 활성 Queue 수
    - Queue 생성 / 삭제 추이
  - Tenant별 Queue 사용량
    - 과금 기준
    - Tenant 활동 수준 판단
- 문제
  - Queue 생성 실패 시 Tenant의 핵심 비즈니스 영향
    - 빠른 감지 필요
  - 누적 Queue 수가 너무 많으면 리소스 부담
    - 만료/삭제 안 된 Queue 추적 필요
- 대안
  - Custom 메트릭 작성
    - queue_created_total{tenant_id}
    - queue_deleted_total{tenant_id, reason}
      - reason: manual / expired / tenant_deleted
    - queue_active_count (Gauge, 현재 활성 Queue 수)
- 이슈
  - tenant_id 라벨 Cardinality?
    - 카테고리 1에서 검토한 그대로 (Tenant 수 제한적이므로 OK)
- 활용방안
  - Tenant별 Queue 사용량 → 과금
  - 활성 Queue 수 추세 → 시스템 부하 예측
  - 폐기되지 않은 Queue 추적 (운영 정리)

---

### 4-2. Token Enqueue 측정 (대기열 진입)

- 측정 이유
  - 사용자가 Queue에 진입하는 핵심 흐름
    - 진입 성공률
    - 진입 실패 원인 추적
  - 시스템 부하 측정
    - 동시 진입 시도가 비즈니스 부하 핵심 지표
- 문제
  - Enqueue 실패 시 사용자 진입 불가
    - 큐 정원 초과? 시스템 에러?
    - 원인별 구분 필요
  - 대기열이 너무 길면 사용자 이탈
    - 대기 인원 수 추적 필요
- 대안
  - Custom 메트릭 작성
    - queue_token_enqueue_total{tenant_id, queue_id, result}   ⬜ **미구현** (계측 코드 0건)
      - result: success / failure
    - queue_waiting_count (Gauge, Queue별 현재 대기 인원)         ⬜ **미구현** — ZCARD 직접 조회로 대체 중
      - 라벨: queue_id
- 이슈
  - queue_id 라벨 Cardinality?
    - Tenant당 큐 수 (보통 수십 개)
    - 총 큐 수 (수백~수천)
    - 결정: 일단 라벨 유지, Cardinality 추세 모니터링
  - 대기 인원 임계치?
    - 결정:
      - 큐 정원의 80% → 경고 (입장 지연 가능)
      - 정원 초과 시도 → 정상 거부, 모니터링만
- 활용방안
  - 인기 큐 식별 (Top N waiting)
  - Tenant에 큐 정원 조정 제안
  - 진입 실패율 추세로 시스템 점검

---

### 4-3. Token Admit 측정 (입장 허용 = Dequeue)

- 측정 이유
  - 대기 → 입장 흐름의 정상성
    - Admit 처리 속도
    - Admit 실패 추적
  - 대기 시간 (사용자 경험 핵심)
    - 입장까지 얼마나 걸렸나
- 문제
  - Admit 처리가 느리면 큐 정체
    - Tenant의 Backpressure 처리 속도가 관건
  - 대기 시간이 길면 사용자 이탈
    - 임계치 모니터링 필요
- 대안
  - Custom 메트릭 작성
    - queue_token_admit_total{tenant_id, queue_id, result}
      - result: success / failure
    - queue_admission_wait_seconds (Histogram)
      - 대기 시간 분포
      - 라벨: queue_id
- 이슈
  - 대기 시간 측정 시점은?
    - 결정: Token enqueue 시점 ~ admit 시점 차이
      - Token 도메인에 enqueued_at, admitted_at 컬럼 활용
  - 대기 시간 임계치?
    - 결정:
      - p95 대기 시간 1분 → 경고
      - p95 대기 시간 5분 → 위험 (Tenant Backpressure 점검)
- 활용방안
  - 대기 시간 추세로 Queue 용량 조정 제안
  - Tenant Backpressure 정상성 검증
  - 사용자 경험 KPI

---

### 4-4. Token 상태 전이 측정

- 측정 이유
  - Token lifecycle 전체 추적
    - WAITING → ADMIT_ISSUED → COMPLETED (정상 흐름)
    - WAITING → CANCELLED (사용자 취소)
    - ADMIT_ISSUED → EXPIRED → WAITING (TTL 만료, 재대기)
  - 비정상 전이 감지
    - COMPLETED 후 변경 (절대 안 됨)
    - 잘못된 상태 전이
- 문제
  - 상태 전이 비정상 발생 시
    - 비즈니스 로직 버그
    - 데이터 정합성 깨짐
  - EXPIRED → WAITING 빈번 발생 시
    - 사용자가 입장 못 잡음 (UX 저하)
- 대안
  - Custom 메트릭 작성
    - queue_token_state_transition_total{from_state, to_state, queue_id}
- 이슈
  - 라벨 Cardinality?
    - from_state × to_state × queue_id
    - 5 × 5 × 100 = 2,500 시계열
    - 단, 실제 발생하는 전이는 일부만 → 더 적음
    - 결정: 안전
  - 비정상 전이 알람?
    - 결정: 비정상 전이 (COMPLETED → 다른 상태 등) 발생 시 즉시 알람
- 활용방안
  - 상태 전이 정상성 검증
  - EXPIRED 빈도 추적 (TTL 적정성 검증)
  - 비즈니스 로직 버그 조기 감지
  - 사용자 행동 패턴 분석 (CANCELLED 비율 등)

---

### 4-5. Token 처리 결과 측정

- 측정 이유
  - Token 발급에서 종료까지 결과 추적
    - 정상 완료 (COMPLETED)
    - 만료 (EXPIRED)
    - 취소 (CANCELLED)
  - 사용자 경험 KPI
    - 완료율 = 정상 큐 사용 비율
- 문제
  - 만료율이 너무 높으면 사용자 경험 저하
    - 입장 받았지만 시간 내 사용 못 함
    - TTL 너무 짧은가? Tenant 서비스 느린가?
  - 취소율이 너무 높으면 이탈 의심
    - 대기 시간 너무 길어서?
- 대안
  - 4-4의 상태 전이 메트릭으로 계산 가능
    - 별도 메트릭 불필요
    - 상태 전이의 to_state 비율로 계산
- 이슈
  - 별도 메트릭 만들지?
    - 결정: 4-4로 충분, 별도 만들지 않음
  - 임계치?
    - 결정:
      - 완료율 < 80% → 점검 필요
      - 만료율 > 10% → TTL 또는 Tenant 응답 속도 점검
      - 취소율 > 20% → 대기 시간 너무 긴지 점검
- 활용방안
  - Queue 서비스 품질 측정
  - admitToken TTL 적정성 검증
  - 사용자 행동 패턴 분석
  - Tenant 서비스 품질 간접 측정

---

### 4-6. 데이터 정합성 검증 (Sprint 5+)

- 측정 이유
  - 분산 시스템에서 데이터 일관성 검증
    - Redis (실시간) vs MySQL (영구 저장)
    - Queue 카운트 vs 실제 Token 수
  - 비동기 처리 (Kafka)의 정합성
    - 이벤트 손실 또는 중복 처리 감지
- 문제
  - 분산 시스템에서 일시적 불일치 발생 가능
    - 정상 동작 vs 진짜 정합성 깨짐 구분 필요
  - 장기 누적 시 데이터 신뢰성 저하
- 대안
  - 정기 검증 잡 (예: 매시간)
    - Queue.current_waiting vs COUNT(*) FROM tokens WHERE status=WAITING
    - 불일치 시 카운터 증가
  - Custom 메트릭
    - data_consistency_mismatch_total{type}
      - type: queue_vs_tokens / redis_vs_db / kafka_lost
- 이슈
  - 검증 잡 비용?
    - 결정: 매시간 1회로 시작, 부담 보고 조정
  - 알람 임계치?
    - 결정: 불일치 발생 자체가 위험 신호 → 즉시 알람
- 활용방안
  - 분산 시스템 안정성 검증
  - 비동기 처리 (Kafka) 정상성 검증
  - 운영 사후 분석 (불일치 발생 → 원인 추적)

---

### 카테고리 4 통합 원칙

Queue Platform의 핵심 비즈니스 흐름 전체를 추적:

```
[정상 흐름]
Queue 생성 (4-1)
  ↓
Token enqueue (4-2)
  ↓ 대기
Token admit (4-3)
  ↓ TTL 동안 사용
Token complete (4-5)
  ↓
Queue 삭제 (4-1)

[비정상 / 예외]
- Enqueue 실패 (4-2)
- Admit 실패 (4-3)
- 비정상 상태 전이 (4-4)
- 데이터 정합성 깨짐 (4-6)
```

Queue의 lifecycle을 한 카테고리에 모아서 보면
비즈니스 정합성 검증이 일관되고 명확함.

---

## 향후 추가 예정

### 보안 카테고리 (Sprint 6+ Rate Limiter 구축 시)

```
- JWT 검증 실패
- Refresh Token 재사용 감지 (Phase B 자산)
- 인증 실패 (Brute Force)
- Rate Limit 초과
```

### 시스템 리소스 카테고리 (선택)

```
- CPU 사용률 (node_exporter)
- Memory 사용률
- Disk 사용률
- Network I/O
```

### AlertManager 통합

```
- 임계치 기반 자동 알림
- Slack/Discord Webhook
- 우선순위 분류 (Critical / Warning)
```
