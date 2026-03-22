# 📊 Queue Platform — 상태 흐름도

---

## Token 상태 머신

```mermaid
stateDiagram-v2
    [*] --> WAITING : POST /tokens<br/>ZADD NX

    WAITING --> ADMITTED : POST /admit<br/>Tenant 서버 호출
    WAITING --> CANCELLED : DELETE /token<br/>자발적 이탈
    WAITING --> EXPIRED : Batch<br/>waitingTtl / inactiveTtl 초과

    ADMITTED --> COMPLETED : DELETE /token<br/>세션 정상 종료
    ADMITTED --> EXPIRED : Batch<br/>sessionTtl 초과 <br/>(Heartbeat 미호출)

    COMPLETED --> [*]
    CANCELLED --> [*]
    EXPIRED --> [*]
```

### expiredReason

| 값 | 원인 | 감지 방법 |
|----|------|----------|
| `WAITING_TTL` | waitingTtl 초과 | `ZRANGEBYSCORE 0 ~ (now - waitingTtl)` |
| `INACTIVE_TTL` | Polling 없어 비활동 | `EXISTS token-last-active:{token}` = 0 |
| `SESSION_TTL` | Heartbeat 미호출 | `DB expiresAt < now` |

---

## Queue 상태 머신

```mermaid
stateDiagram-v2
    [*] --> ACTIVE : POST /queues (생성)

    ACTIVE --> PAUSED : POST /pause<br/>신규 Enqueue 차단
    PAUSED --> ACTIVE : POST /resume

    ACTIVE --> DRAINING : DELETE /queues<br/>잔여 토큰 순차 만료
    PAUSED --> DRAINING : DELETE /queues

    DRAINING --> DELETED : Batch DrainJob<br/>잔여 토큰 = 0 확인 후
```

---

## API Key 상태 머신

```mermaid
stateDiagram-v2
    [*] --> ACTIVE : POST /api-keys

    ACTIVE --> REVOKED : DELETE /api-keys/{id}

    state ACTIVE {
        [*] --> 정상운영
        정상운영 : SHA-256 해시 저장
        정상운영 : Redis TTL 60s
    }

    state REVOKED {
        [*] --> 즉시무효화
        즉시무효화 : Redis DEL
        즉시무효화 : 이후 401 반환
    }
```
