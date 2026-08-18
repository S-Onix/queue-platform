package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.infrastructure.repository.QueueJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 신규 큐 클러스터 배정 (§75 D29).
 *
 * <p>Redis가 필요 없다. 두 분기 모두 <b>메모리 조회에 도달하기 전이거나, 조회가 실패했을 때</b>를
 * 보는 것이기 때문이다. 임계값 판정(사용률 ≥ 50%)은 로컬 1GB 노드에서 512MB를 채워야 해
 * 실 클러스터로 도달할 수 없다 — 그 경로는 {@code RedisClusterConnection} mock 체인이 유일한
 * 길이라 값어치 대비 비용이 맞지 않아 걸지 않았다.
 */
class RedisClusterAssignerTest {

    private final QueueJpaRepository queueJpaRepository = mock(QueueJpaRepository.class);
    private final StringRedisTemplate cluster1 = mock(StringRedisTemplate.class);

    private RedisClusterAssigner assigner() {
        return new RedisClusterAssigner(cluster1, queueJpaRepository);
    }

    @Test
    @DisplayName("단조증가 가드: 이미 cluster2로 넘어갔으면 메모리를 보지도 않고 cluster2")
    void monotonicGuard_neverGoesBack() {
        when(queueJpaRepository.findMaxRedisClusterNo()).thenReturn(2);

        assertThat(assigner().assign()).isEqualTo(2);

        // 가드를 지우면 cluster1 사용률이 임계 아래로 내려간 순간 신규 큐가 두 클러스터를
        // 왕복 배정된다. 메모리를 아예 조회하지 않는 것이 그 가드가 살아 있다는 증거다.
        verifyNoInteractions(cluster1);
    }

    @Test
    @DisplayName("사용률을 판정하지 못하면(조회 실패) 큐 생성을 실패시키지 않고 cluster1을 유지한다")
    void unknownUsageKeepsCluster1() {
        when(queueJpaRepository.findMaxRedisClusterNo()).thenReturn(1);
        // mock 템플릿에는 커넥션 팩토리가 없어 메모리 조회가 실패한다 — Redis 장애와 같은 상황.

        assertThat(assigner().assign()).isEqualTo(1);
    }
}
