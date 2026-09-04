# Sprint 5 Phase 1 — Redis Sentinel 학습 노트

> 작성일: 2026-05 (Sprint 5 진행 중)
>
> ⚠️ **이 문서는 그 시점의 학습 노트다. 현행 인프라가 아니다.**
> 앱이 붙는 Redis는 **독립 2 Cluster**(7001-7008 / 8001-8008)이고,
> `RedisConfig`에서 **Sentinel 분기는 제거됐다**(§75 D28). 아래 §8 "Spring 연동"은 **폐기된 경로**다.
> Sentinel 자체는 failover·quorum 학습 자산으로 남아 있다 — 구축 절차는 `doc/INFRA_SETUP.md` §6.
> 목적: WSL2에 Redis Sentinel 직접 구성 + Failover 실증

---

## 1. 왜 Sentinel인가?

### Standalone Redis의 한계
```
Master 1대만 운영 시:
  → Master 죽으면 전체 서비스 다운
  → 운영자가 새벽에 깨어나야 함
  → 수동 Failover (몇 분 소요)
```

### Sentinel의 3가지 책임

1. **모니터링 (Monitoring)**
    - 각 Sentinel이 1초마다 Master/Slave/다른 Sentinel에 PING
    - `down-after-milliseconds` 초과 시 SDOWN (주관적 다운)

2. **합의 (Quorum-based Consensus)**
    - 1대 Sentinel 판단으로 함부로 Failover 안 함
    - `quorum` 수만큼 동의해야 ODOWN (객관적 다운)
    - 우리 설정: 3대 중 2대 동의 (quorum=2)

3. **자동 Failover**
    - Leader Sentinel 선출 (Raft 변형)
    - Slave 중 하나를 새 Master로 승격 (`REPLICAOF NO ONE`)
    - 다른 Slave를 새 Master로 재연결
    - 클라이언트(Lettuce)에게 Pub/Sub으로 알림

---

## 2. Sentinel 3대를 쓰는 이유

```
1대: 단일 장애점 → Sentinel 자체 죽으면 감시 불가
2대: 짝수 → Split Brain 위험
3대: ✅ quorum=2, 1대 죽어도 Failover 가능, 최소 비용
5대: 가용성↑ 비용↑ (금융 등 매우 중요한 시스템)

원칙: 합의 기반 시스템은 항상 홀수 (Kafka KRaft, Zookeeper, etcd 동일)
```

---

## 3. Split Brain 방어

### 시나리오
네트워크 분리로 Master가 격리되었을 때:

```
[격리된 쪽]              [다수파 쪽]
Master + Sentinel-1   ┊  Slave1, Slave2 + Sentinel-2, 3
                         → quorum=2 충족 → Slave1을 새 Master로 승격

→ Master 2명 동시 존재 가능 = Split Brain
```

### 방어 메커니즘

**① Sentinel quorum**: 잘못된 Failover 실행 방지

**② `min-replicas-to-write 1`**: 격리된 Master의 쓰기 차단

```
설정: min-replicas-to-write 1

격리된 Master 입장:
  Slave 0개 (다 다수파에 끌려감)
  → 쓰기 거부 (NOREPLICAS 에러)

→ 결국 쓰기는 다수파(새 Master)에서만 발생
→ Split Brain 데이터 충돌 방지
```

---

## 4. 우리 Redis 구성

```
WSL2 (Ubuntu) 
├── redis-master (6379)     ← 모든 쓰기/Lua 실행
├── redis-slave-1 (6380)    ← 복제 대기 + 백업
├── redis-slave-2 (6381)    ← 복제 대기 + 백업
├── sentinel-1 (26379)      ← 감시자 ①
├── sentinel-2 (26380)      ← 감시자 ②
└── sentinel-3 (26381)      ← 감시자 ③

quorum = 2
down-after-milliseconds = 5000 (5초)
failover-timeout = 10000 (10초)
parallel-syncs = 1
```

### 디렉토리 구조

```
~/queue-platform-infra/redis/
├── master/redis.conf
├── slave-1/redis.conf
├── slave-2/redis.conf
├── sentinel-1/sentinel.conf
├── sentinel-2/sentinel.conf
└── sentinel-3/sentinel.conf
```

### 자동 시작 스크립트 (~/.bashrc)

```bash
redis_start    # 6개 프로세스 일괄 기동
redis_stop     # 종료
redis_status   # 상태 확인
redis_logs <대상>  # 로그 실시간 보기
```

---

## 5. Failover 실증 결과

### 시나리오: Master 강제 종료

```bash
kill -9 <master pid>
```

### 관찰된 흐름 (Sentinel 로그)

```
T+0초:  Master 죽음
T+5초:  +sdown master mymaster (Sentinel-1 주관적 다운 판정)
T+5초:  +odown master mymaster #quorum 2/2 (객관적 다운 확정)
T+5초:  Leader Sentinel 선출 (Raft 변형 알고리즘)
T+6초:  +selected-slave 127.0.0.1:6380 (승격 대상 선택)
T+6초:  +failover-state-send-slaveof-noone (REPLICAOF NO ONE 명령)
T+7초:  +promoted-slave (Slave-1이 새 Master로 승격 완료)
T+8초:  +slave-reconf-sent → Slave-2 재연결
T+9초:  +switch-master mymaster 127.0.0.1 6379 127.0.0.1 6380
T+10초: Failover 완료
```

**전체 5~10초 내 자동 복구.**

---

## 6. CONFIG REWRITE 함정 (트러블슈팅)

Sentinel은 Failover 후 conf 파일을 자동 수정합니다:

```
[Failover 전]
sentinel monitor mymaster 127.0.0.1 6379 2

[Failover 후 — 자동 수정됨]
sentinel monitor mymaster 127.0.0.1 6380 2  ← 새 Master 포트
known-replica mymaster 127.0.0.1 6379
known-replica mymaster 127.0.0.1 6381
known-sentinel mymaster 127.0.0.1 26380 <id>
known-sentinel mymaster 127.0.0.1 26381 <id>
current-epoch 1
```

**문제**: 단순 프로세스 재시작만으론 초기 상태로 복원 안 됨.

**해결**: conf 파일을 새로 작성하거나 다음 라인 제거:
```
- known-*
- current-epoch
- sentinel myid
- monitor 라인의 IP/포트 원복
```

---

## 7. 면접 답변 — Sentinel 핵심

> "WSL2에 Redis Master 1대 + Slave 2대 + Sentinel 3대를 직접 구성했습니다.
> quorum=2로 과반수 합의로 Failover를 결정하고,
> `down-after-milliseconds=5000`으로 5초 SDOWN,
> `min-replicas-to-write 1`로 Split Brain을 방지했습니다.
>
> Master를 kill -9로 종료했을 때 Sentinel이 SDOWN → ODOWN → Leader 선출 → REPLICAOF NO ONE으로 Slave 승격까지 10초 내 완료하는 것을 로그로 실증했습니다.
>
> 클라이언트는 Sentinel 주소만 알면 되고, Lettuce가 +switch-master Pub/Sub 이벤트로 새 Master를 자동 인식해 코드 변경 없이 복구됩니다.
>
> (⚠️ **Sentinel 구성 기준의 설명이다.** 이 프로젝트의 운영 구성은 Cluster이고 `RedisConfig`에
> Sentinel 분기는 없다 — §75 D28.)
>
> Sentinel은 감시·결정만 하고 프로세스 부활은 systemd/Docker/k8s의 책임입니다. 이 분리가 Redis HA 설계 철학입니다."

---

## 8. Spring 연동 (Phase 2 예정)

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes:
          - 127.0.0.1:26379
          - 127.0.0.1:26380
          - 127.0.0.1:26381
      lettuce:
        pool:
          max-active: 50
        cluster:
          refresh:
            adaptive: true   # Pub/Sub 즉시 반영
        timeout: 2000ms
```

핵심:
- Spring 코드는 Master 주소를 모름
- Sentinel에게 "mymaster 누구야?" 물어봄
- Failover 시 +switch-master 자동 수신 → 새 Master 재연결