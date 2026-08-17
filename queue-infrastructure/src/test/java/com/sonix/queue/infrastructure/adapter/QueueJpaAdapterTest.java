package com.sonix.queue.infrastructure.adapter;

import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueStatus;
import com.sonix.queue.infrastructure.entity.QueueEntity;
import com.sonix.queue.infrastructure.queue.RedisClusterAssigner;
import com.sonix.queue.infrastructure.repository.QueueJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 클러스터 배정을 켜고 끄는 <b>단일 분기</b>({@code if (queue.getId() == null)}) 검증.
 *
 * <p>이 분기가 틀리면 (a) 수정 때마다 재배정하거나 (b) 영원히 배정하지 않는데, 컬럼이
 * {@code updatable = false}라 <b>둘 다 예외 없이 조용히</b> 일어난다. 배정이 안 붙은 큐는
 * 첫 enqueue 때 DB 기본값 1로 읽혀 cluster1에 만들어지고, 아무도 그걸 이상하다고 말해주지 않는다.
 */
class QueueJpaAdapterTest {

    private final QueueJpaRepository queueJpaRepository = mock(QueueJpaRepository.class);
    private final RedisClusterAssigner clusterAssigner = mock(RedisClusterAssigner.class);
    private final QueueJpaAdapter adapter = new QueueJpaAdapter(queueJpaRepository, clusterAssigner);

    private static Queue existingQueue() {
        return Queue.reconstruct(1L, "q_dev_adapter", 1L, "이름", 1000, 7200, 300,
                QueueStatus.ACTIVE, LocalDateTime.now(), null);
    }

    @Test
    @DisplayName("신규 큐를 저장하면 클러스터를 배정한다")
    void assignsOnInsert() {
        when(clusterAssigner.assign()).thenReturn(2);
        when(queueJpaRepository.save(any(QueueEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        adapter.save(Queue.create(1L, "이름", 1000, null, null));

        verify(clusterAssigner, times(1)).assign();
    }

    @Test
    @DisplayName("기존 큐를 수정 저장할 때는 배정하지 않는다 (재배정 = 큐가 클러스터를 갈아탄다)")
    void doesNotReassignOnUpdate() {
        when(queueJpaRepository.save(any(QueueEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        adapter.save(existingQueue());

        verifyNoInteractions(clusterAssigner);
    }
}
