package com.sonix.queue.api.queue;

import com.sonix.queue.api.common.response.ApiResponse;
import com.sonix.queue.api.queue.dto.EnqueueRequest;
import com.sonix.queue.api.queue.dto.EnqueueResponse;
import com.sonix.queue.api.security.TenantAuth;
import com.sonix.queue.domain.queue.EnqueueResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;


/**
 * QueueEngine(대기열 라이프사이클) HTTP 엔드포인트.
 *
 * <p>관리용 컨트롤러(생성/수정/정지/삭제, JWT)와 분리된 엔진 엔드포인트.
 * Tenant 서버가 X-API-Key로 호출한다. Sprint 5-E는 enqueue만 노출하며,
 * 이후 admit / verify / complete가 순차 추가될 예정이다.
 */
@RestController
@RequestMapping("/api/v1/queues")
public class QueueEngineController {
    private final QueueEngineService queueEngineService;

    public QueueEngineController(QueueEngineService queueEngineService) {
        this.queueEngineService = queueEngineService;
    }

    /**
     * 대기열 진입 (Enqueue).
     *
     * <p>POST /api/v1/queues/{queueId}/tokens
     *
     * <p>중복 요청(EXISTS)은 에러가 아니라 기존 대기 정보를 반환하며,
     * 응답의 {@code already} 필드가 true가 된다. FULL/미존재/비활성 상태는
     * BusinessException으로 전환되어 GlobalExceptionHandler가 오류 응답을 만든다.
     *
     * @param queueId 대기열 식별자 (경로 변수)
     * @param request 진입 요청 (identifier)
     * @return 진입 결과 (rank, total, already)
     */
    @PostMapping("/{queueId}/tokens")
    public ResponseEntity<ApiResponse<EnqueueResponse>> enqueue(
            @AuthenticationPrincipal TenantAuth tenantAuth,
            @PathVariable String queueId,
            @Valid @RequestBody EnqueueRequest request
    ) {
        EnqueueResult result = queueEngineService.enqueue(tenantAuth.getId(),queueId, request.getIdentifier());
        EnqueueResponse response = EnqueueResponse.from(queueId, result);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
