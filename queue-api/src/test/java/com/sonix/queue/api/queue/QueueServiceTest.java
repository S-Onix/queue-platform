package com.sonix.queue.api.queue;

import com.sonix.queue.api.queue.dto.QueueCreateRequest;
import com.sonix.queue.api.queue.dto.QueueResponse;
import com.sonix.queue.api.queue.dto.QueueUpdateRequest;
import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.queue.QueueStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    @Mock
    private QueueRepository queueRepository;

    @InjectMocks
    private QueueService queueService;

    // ── 생성 ──

    @Test
    @DisplayName("Queue 생성 성공")
    void createQueue_success() {
        // given
        QueueCreateRequest request = new QueueCreateRequest();
        request.setName("이벤트 대기열");
        request.setMaxCapacity(100000);

        when(queueRepository.existsByTenantIdAndName(1L, "이벤트 대기열")).thenReturn(false);
        when(queueRepository.save(any(Queue.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // when
        QueueResponse response = queueService.createQueue(1L, request);

        // then
        assertNotNull(response);
        assertEquals("이벤트 대기열", response.getName());
        assertEquals(100000, response.getMaxCapacity());
        assertEquals(QueueStatus.ACTIVE, response.getStatus());
        verify(queueRepository).save(any(Queue.class));
    }

    @Test
    @DisplayName("Queue 생성 - 테넌트당 상한 도달 시 Q006")
    void createQueue_tenant_limit_exceeded() {
        // given
        QueueCreateRequest request = new QueueCreateRequest();
        request.setName("21번째 대기열");
        request.setMaxCapacity(100000);

        when(queueRepository.existsByTenantIdAndName(1L, "21번째 대기열")).thenReturn(false);
        // 🔑 상수를 참조하면 상수 자체가 안 잠긴다 — MAX_QUEUES_PER_TENANT를 999999로 바꿔도
        //    스텁이 함께 999999가 되어 초록이었다(결함 주입으로 확인). 그래서 리터럴 20이다.
        //    이 값은 DECISIONS §87과 doc/API.md가 단정하는 숫자다. 바꾸려면 여기도 같이 고쳐라.
        when(queueRepository.countActiveByTenantId(1L)).thenReturn(20);

        // when
        BusinessException e = assertThrows(BusinessException.class,
                () -> queueService.createQueue(1L, request));

        // then
        assertEquals(ErrorCode.QUEUE_LIMIT_EXCEEDED, e.getErrorCode());
        verify(queueRepository, never()).save(any(Queue.class));
    }

    @Test
    @DisplayName("Queue 생성 - 상한 직전(19개)은 통과한다")
    void createQueue_just_below_limit() {
        // given
        QueueCreateRequest request = new QueueCreateRequest();
        request.setName("20번째 대기열");
        request.setMaxCapacity(100000);

        when(queueRepository.existsByTenantIdAndName(1L, "20번째 대기열")).thenReturn(false);
        when(queueRepository.countActiveByTenantId(1L)).thenReturn(19);  // 리터럴 — 위 주석 참조
        when(queueRepository.save(any(Queue.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // when
        QueueResponse response = queueService.createQueue(1L, request);

        // then — 경계가 >= 인지 > 인지를 잠근다. > 로 잘못 쓰면 21개가 만들어진다.
        assertNotNull(response);
        verify(queueRepository).save(any(Queue.class));
    }

    @Test
    @DisplayName("Queue 생성 - 이름 중복")
    void createQueue_duplicate_name() {
        // given
        QueueCreateRequest request = new QueueCreateRequest();
        request.setName("이벤트 대기열");
        request.setMaxCapacity(100000);

        when(queueRepository.existsByTenantIdAndName(1L, "이벤트 대기열")).thenReturn(true);

        // when & then
        assertThrows(BusinessException.class, () ->
                queueService.createQueue(1L, request));
        verify(queueRepository, never()).save(any());
    }

    // ── 조회 ──

    @Test
    @DisplayName("Queue 조회 성공")
    void getQueue_success() {
        // given
        Queue queue = Queue.create(1L, "이벤트 대기열", 100000, null, null);

        when(queueRepository.findByQueueId("q_test1234")).thenReturn(Optional.of(queue));

        // when
        QueueResponse response = queueService.getQueue(1L, "q_test1234");

        // then
        assertNotNull(response);
        assertEquals("이벤트 대기열", response.getName());
    }

    @Test
    @DisplayName("Queue 조회 - 존재하지 않음")
    void getQueue_not_found() {
        // given
        when(queueRepository.findByQueueId("q_notexist")).thenReturn(Optional.empty());

        // when & then
        assertThrows(BusinessException.class, () ->
                queueService.getQueue(1L, "q_notexist"));
    }

    @Test
    @DisplayName("Queue 조회 - 본인 소유 아님")
    void getQueue_not_owned() {
        // given
        Queue queue = Queue.create(999L, "이벤트 대기열", 100000, null, null);

        when(queueRepository.findByQueueId("q_test1234")).thenReturn(Optional.of(queue));

        // when & then
        assertThrows(BusinessException.class, () ->
                queueService.getQueue(1L, "q_test1234"));
    }

    // ── 수정 ──

    @Test
    @DisplayName("Queue 이름 변경 성공")
    void updateQueue_success() {
        // given
        Queue queue = Queue.create(1L, "기존 이름", 100000, null, null);
        QueueUpdateRequest request = new QueueUpdateRequest();
        request.setName("새 이름");

        when(queueRepository.findByQueueId("q_test1234")).thenReturn(Optional.of(queue));
        when(queueRepository.save(any(Queue.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // when
        QueueResponse response = queueService.updateQueue(1L, "q_test1234", request);

        // then
        assertEquals("새 이름", response.getName());
        verify(queueRepository).save(any(Queue.class));
    }

    // ── 정지 ──

    @Test
    @DisplayName("Queue 정지 성공")
    void pauseQueue_success() {
        // given
        Queue queue = Queue.create(1L, "이벤트 대기열", 100000, null, null);

        when(queueRepository.findByQueueId("q_test1234")).thenReturn(Optional.of(queue));
        when(queueRepository.save(any(Queue.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // when
        QueueResponse response = queueService.pauseQueue(1L, "q_test1234");

        // then
        assertEquals(QueueStatus.PAUSED, response.getStatus());
    }

    @Test
    @DisplayName("Queue 정지 - 이미 PAUSED 상태")
    void pauseQueue_already_paused() {
        // given
        Queue queue = Queue.create(1L, "이벤트 대기열", 100000, null, null);
        queue.pause();

        when(queueRepository.findByQueueId("q_test1234")).thenReturn(Optional.of(queue));

        // when & then
        assertThrows(BusinessException.class, () ->
                queueService.pauseQueue(1L, "q_test1234"));
    }

    // ── 재개 ──

    @Test
    @DisplayName("Queue 재개 성공")
    void resumeQueue_success() {
        // given
        Queue queue = Queue.create(1L, "이벤트 대기열", 100000, null, null);
        queue.pause();

        when(queueRepository.findByQueueId("q_test1234")).thenReturn(Optional.of(queue));
        when(queueRepository.save(any(Queue.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // when
        QueueResponse response = queueService.resumeQueue(1L, "q_test1234");

        // then
        assertEquals(QueueStatus.ACTIVE, response.getStatus());
    }

    @Test
    @DisplayName("Queue 재개 - ACTIVE 상태에서 시도")
    void resumeQueue_already_active() {
        // given
        Queue queue = Queue.create(1L, "이벤트 대기열", 100000, null, null);

        when(queueRepository.findByQueueId("q_test1234")).thenReturn(Optional.of(queue));

        // when & then
        assertThrows(BusinessException.class, () ->
                queueService.resumeQueue(1L, "q_test1234"));
    }

    // ── 삭제 ──

    @Test
    @DisplayName("Queue 삭제 성공 - PAUSED 상태에서")
    void deleteQueue_success() {
        // given
        Queue queue = Queue.create(1L, "이벤트 대기열", 100000, null, null);
        queue.pause();

        when(queueRepository.findByQueueId("q_test1234")).thenReturn(Optional.of(queue));
        when(queueRepository.save(any(Queue.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // when
        QueueResponse response = queueService.deleteQueue(1L, "q_test1234");

        // then
        assertEquals(QueueStatus.DELETED, response.getStatus());
    }

    @Test
    @DisplayName("Queue 삭제 - ACTIVE 상태에서 시도")
    void deleteQueue_from_active() {
        // given
        Queue queue = Queue.create(1L, "이벤트 대기열", 100000, null, null);

        when(queueRepository.findByQueueId("q_test1234")).thenReturn(Optional.of(queue));

        // when & then
        assertThrows(BusinessException.class, () ->
                queueService.deleteQueue(1L, "q_test1234"));
    }

    @Test
    @DisplayName("Queue 삭제 - 본인 소유 아님")
    void deleteQueue_not_owned() {
        // given
        Queue queue = Queue.create(999L, "이벤트 대기열", 100000, null, null);

        when(queueRepository.findByQueueId("q_test1234")).thenReturn(Optional.of(queue));

        // when & then
        assertThrows(BusinessException.class, () ->
                queueService.deleteQueue(1L, "q_test1234"));
    }
}