package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.infrastructure.repository.QueueJpaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * 이유: 신규 큐의 Redis 클러스터 배정(§75 D25 · D27-3).
 * 🔑 <b>기준은 메모리 사용률이지 큐 개수가 아니다</b> — 30만 명 큐 1개가 유휴 큐 1000개보다 무겁다.
 * 해결: <b>cold path 전용</b>이다 — 큐 생성에서만 불리고 호출당 노드 수만큼 {@code INFO memory} 가 붙는다.
 * 🪤 캐시하지 않는다 — 자주 불리는 경로가 아니고, 캐시가 있으면 <b>방금 넘긴 임계값을 못 본다</b>.
 * 🪤 큐를 미리 다 만들면 cluster2 가 <b>영원히 안 쓰인다</b>(생성 시점엔 A 가 비어 있다, 실측).
 *
 * @author sonix
 */
@Slf4j
@Component
public class RedisClusterAssigner {

    static final int CLUSTER1 = 1;
    static final int CLUSTER2 = 2;

    /**
     * cluster1에서 cluster2로 넘어가는 메모리 사용률 (§75 D25).
     *
     * <p>50%인 이유: 남은 절반은 여유가 아니라 <b>이미 들어와 있는 큐가 자랄 몫</b>이다.
     * 배정은 신규 큐에만 적용되고 기존 큐는 옮기지 않으므로(D27-2), 임계를 높게 잡을수록
     * "더 넣을 곳이 없는데 기존 큐가 계속 자라는" 상태에 가까워진다.
     */
    private static final double MEMORY_THRESHOLD = 0.5;

    private final StringRedisTemplate cluster1;
    private final QueueJpaRepository queueJpaRepository;

    public RedisClusterAssigner(@Qualifier("stringRedisTemplate") StringRedisTemplate cluster1,
                                QueueJpaRepository queueJpaRepository) {
        this.cluster1 = cluster1;
        this.queueJpaRepository = queueJpaRepository;
    }

    /**
     * 신규 큐를 배정할 클러스터 번호.
     *
     * <p>cluster2의 사용률은 보지 않는다. 클러스터가 둘뿐이고 되돌아가지 않으므로,
     * cluster2가 아무리 차 있어도 <b>갈 곳이 거기밖에 없다</b> — 조회해도 결정이 바뀌지 않는다.
     */
    public int assign() {
        // 단조증가 가드: 한 번 cluster2로 넘어갔으면 cluster1이 다시 비어도 돌아가지 않는다.
        // 임계값 근처에서 신규 큐가 두 클러스터를 왕복하며 배정되는 것을 막는다(§75 D29).
        if (queueJpaRepository.findMaxRedisClusterNo() >= CLUSTER2) {
            return CLUSTER2;
        }
        return usedMemoryRatio() >= MEMORY_THRESHOLD ? CLUSTER2 : CLUSTER1;
    }

    /**
     * 이유: cluster1 마스터 중 <b>가장 높은</b> 메모리 사용률.
     * 원인: <b>용량을 먼저 소진하는 쪽이 한 노드</b>다 — 해시태그로 같은 슬롯에 모여 편차가 남는다.
     * 🪤 합계로 보면 <b>한 노드가 100%여도 평균은 25%</b> 라 배정이 계속된다.
     * 해결: 판정 실패 시(연결 불가·{@code maxmemory=0}) 0 을 반환해 cluster1 을 고른다 —
     *       큐 생성이라는 관리 작업을 Redis 상태 때문에 실패시키지 않는다.
     *
     * @author sonix
     */
    private double usedMemoryRatio() {
        try (RedisClusterConnection conn = cluster1.getRequiredConnectionFactory().getClusterConnection()) {
            double worst = 0;
            int judged = 0;
            for (RedisClusterNode node : conn.clusterGetNodes()) {
                if (!node.isMaster()) {
                    continue;
                }
                Properties info = conn.serverCommands().info(node, "memory");
                if (info == null) {
                    continue;
                }
                long used = parseLong(info.getProperty("used_memory"));
                long max = parseLong(info.getProperty("maxmemory"));
                if (max <= 0) {
                    // maxmemory 0 = 무제한. 비율을 정의할 수 없으므로 이 노드는 판정에서 뺀다.
                    continue;
                }
                judged++;
                worst = Math.max(worst, (double) used / max);
            }
            if (judged == 0) {
                // 이유: 판정할 노드가 없으면 사용률이 영원히 0이라 cluster2 로 넘어갈 수 없다.
                // 문제: 침묵하면 "임계에 안 닿았다"와 구분되지 않아 §75 가 조용히 무성해진다.
                // 해결: WARN 으로 드러낸다.
                // ⚠️ 실측 기준으로는 이 분기에 안 들어온다(전 노드 maxmemory=1gb + noeviction) —
                //    남겨두는 이유는 한도 없는 노드를 <b>추가하는 순간 판정이 조용히 무력화</b>되기 때문이다.
                log.warn("Redis cluster1에 maxmemory가 설정된 master가 없다. 사용률 판정이 성립하지 않아 "
                        + "신규 큐가 계속 cluster{}로만 배정된다 (DECISIONS §75 D27-3)", CLUSTER1);
            }
            return worst;
        } catch (Exception e) {
            log.warn("Redis cluster1 memory lookup failed, assigning to cluster{}: {}", CLUSTER1, e.getMessage());
            return 0;
        }
    }

    private static long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
