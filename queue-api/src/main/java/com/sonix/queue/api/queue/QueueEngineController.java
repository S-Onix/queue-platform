package com.sonix.queue.api.queue;

import com.sonix.queue.api.common.response.ApiResponse;
import com.sonix.queue.api.queue.dto.AdmitRequest;
import com.sonix.queue.api.queue.dto.AdmitResponse;
import com.sonix.queue.api.queue.dto.CompleteRequest;
import com.sonix.queue.api.queue.dto.CompleteResponse;
import com.sonix.queue.api.queue.dto.EnqueueRequest;
import com.sonix.queue.api.queue.dto.EnqueueResponse;
import com.sonix.queue.api.queue.dto.PollResponse;
import com.sonix.queue.api.queue.dto.QueueStatusResponse;
import com.sonix.queue.api.queue.dto.VerifyResponse;
import com.sonix.queue.api.security.TenantAuth;
import com.sonix.queue.domain.queue.AdmitResult;
import com.sonix.queue.domain.queue.EnqueueResult;
import com.sonix.queue.domain.queue.QueueBoard;

import java.time.LocalDateTime;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;


/**
 * QueueEngine(대기열 라이프사이클) HTTP 엔드포인트.
 *
 * <p>관리용 컨트롤러(생성/수정/정지/삭제, JWT)와 분리된 엔진 엔드포인트.
 * Tenant 서버가 X-API-Key로 호출한다 (polling만 예외 — permitAll).
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

    /**
     * 이유: 입장 허가(Admit). {@code POST /api/v1/queues/&#123;queueId&#125;/admit}
     * 해결: {@code count} 상한 300 은 {@link AdmitRequest} 의 {@code @Max} 가 강제한다 —
     *       초과하면 여기 도달하기 전에 400 이다.
     * 🔴 <b>Kafka 발행이 실패해도 200 이다</b> — Lua 가 이미 커밋돼 되돌릴 수 없다
     *    (근거는 {@code QueueEngineService.publishAdmitted}).
     *
     * @author sonix
     */
    @PostMapping("/{queueId}/admit")
    public ResponseEntity<ApiResponse<AdmitResponse>> admit(
            @AuthenticationPrincipal TenantAuth tenantAuth,
            @PathVariable String queueId,
            @Valid @RequestBody AdmitRequest request
    ) {
        AdmitResult result = queueEngineService.admit(
                tenantAuth.getId(), queueId, request.count(), request.requestId());
        return ResponseEntity.ok(ApiResponse.ok(AdmitResponse.from(result)));
    }

    /**
     * 이유: admitToken 유효성 확인(Verify).
     *       {@code POST /api/v1/queues/&#123;queueId&#125;/admit-tokens/&#123;admitToken&#125;/verify}
     * 🔑 <b>응답을 주는 시점이 곧 완료다</b> — 그 뒤 Tenant 안의 좌석 배정은 관측도 책임도 못 한다
     *    (원칙 1). 그래서 이 호출이 COMPLETED 를 발행하고, DB·Redis 는 직접 건드리지 않는다.
     * 🪤 경로에 queueId 가 있는 것은 Redis 키를 해시태그로 묶기 위해서다. 무효하면 404 TK002.
     *
     * @author sonix
     */
    @PostMapping("/{queueId}/admit-tokens/{admitToken}/verify")
    public ResponseEntity<ApiResponse<VerifyResponse>> verify(
            @AuthenticationPrincipal TenantAuth tenantAuth,
            @PathVariable String queueId,
            @PathVariable String admitToken
    ) {
        String identifier = queueEngineService.verify(tenantAuth.getId(), queueId, admitToken);
        return ResponseEntity.ok(ApiResponse.ok(VerifyResponse.ok(identifier)));
    }

    /**
     * 입장 완료 통보 (Complete).
     *
     * <p>POST /api/v1/queues/{queueId}/tokens/{tokenId}/complete
     *
     * <p><b>탐색 키는 URL의 tokenId</b>이고, 본문의 admitToken은 입장 자격을 증명하는 술어다.
     * verify를 건너뛴 호출도 거절하지 않는다 — complete 자체가 admitToken을 검증하므로
     * 거절할 근거가 없다 (§80).
     */
    @PostMapping("/{queueId}/tokens/{tokenId}/complete")
    public ResponseEntity<ApiResponse<CompleteResponse>> complete(
            @AuthenticationPrincipal TenantAuth tenantAuth,
            @PathVariable String queueId,
            @PathVariable String tokenId,
            @Valid @RequestBody CompleteRequest request
    ) {
        LocalDateTime completedAt = queueEngineService.complete(
                tenantAuth.getId(), queueId, tokenId, request.admitToken());
        return ResponseEntity.ok(ApiResponse.ok(new CompleteResponse("COMPLETED", completedAt)));
    }

    /**
     * 이유: 큐 전광판 — End-user 브라우저가 평상시 부르는 유일한 경로.
     *       {@code GET /api/v1/queues/&#123;queueId&#125;/status} · <b>인증 없음 · Rate Limit 없음</b>(§79).
     * 🔴 <b>제한이 없는 것은 알고 그렇게 둔 것이다</b>(이유 셋: 큐 버킷은 30만 명이 공유해 남용자
     *    1명이 전원을 429 시킨다 · 리미터가 {@code MGET} 보다 20~25배 비싸다 · 더 싼 우회로가 있다).
     * 🔁 재검토 트리거: 한 마스터의 {@code MGET} 실처리량이 <b>30,000 ops/s 미만</b>이면 캐시를 다시 연다.
     *
     * @author sonix
     */
    @GetMapping("/{queueId}/status")
    public ResponseEntity<ApiResponse<QueueStatusResponse>> status(@PathVariable String queueId) {
        QueueBoard status = queueEngineService.status(queueId);
        return ResponseEntity.ok(ApiResponse.ok(QueueStatusResponse.from(status)));
    }

    /**
     * 이유: 개인 상태 폴링 — 브라우저가 Platform 을 직접 호출한다.
     *       {@code GET .../tokens/&#123;tokenId&#125;?seq=&#123;mySeq&#125;&amp;ka=&#123;0|1&#125;}
     * 해결: <b>인증 없음</b> — tokenId 소유가 곧 자격(capability)이라 X-API-Key 를 받지 않는다.
     *       유효성은 서비스가 tokenId·seq 로 판정하고 미존재면 404(TOKEN_NOT_FOUND).
     * 🔑 차례가 가까울 때와 keepalive 에만 호출된다 — 평상시 폴링은 {@code /status} 가 받는다(§79).
     *
     * @author sonix
     */
    @GetMapping("/{queueId}/tokens/{tokenId}")
    public ResponseEntity<ApiResponse<PollResponse>> poll(
            @PathVariable String queueId,
            @PathVariable String tokenId,
            @RequestParam long seq,
            @RequestParam(defaultValue = "false") boolean ka

    ){
        PollResult result = queueEngineService.poll(queueId, tokenId, seq, ka);
        return ResponseEntity.ok(ApiResponse.ok(PollResponse.from(result)));
    }
}
