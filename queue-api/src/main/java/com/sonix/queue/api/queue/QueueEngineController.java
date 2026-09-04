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
     * 입장 허가 (Admit).
     *
     * <p>POST /api/v1/queues/{queueId}/admit
     *
     * <p>{@code count} 상한 100은 {@link AdmitRequest}의 {@code @Max}가 강제한다 —
     * 초과하면 여기 도달하기 전에 400이다.
     *
     * <p><b>Kafka 발행이 실패해도 200이다.</b> Lua가 이미 커밋돼 되돌릴 수 없기 때문이다.
     * 근거는 {@code QueueEngineService.publishAdmitted} 참조.
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
     * admitToken 유효성 확인 (Verify).
     *
     * <p>POST /api/v1/queues/{queueId}/admit-tokens/{admitToken}/verify
     *
     * <p>🔑 <b>응답을 주는 시점이 곧 완료다</b> — 그 뒤 Tenant 안의 좌석 배정은 관측할 수도
     * 책임질 수도 없다(CLAUDE.md 원칙 1). 그래서 이 호출이 {@code COMPLETED}를 발행한다.
     * DB·Redis는 직접 건드리지 않고 이벤트만 낸다.
     *
     * <p>경로에 queueId가 있는 것은 Redis 키를 {@code queue:&#123;queueId&#125;:*} 해시태그로
     * 묶기 위해서다. 무효하면 404 {@code TK002}.
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
     * 큐 전광판 (End-user 브라우저가 평상시 부르는 유일한 경로).
     *
     * <p>GET /api/v1/queues/{queueId}/status
     *
     * <p><b>인증 없음(permitAll) · Rate Limit 없음</b> (§79). {@code queueId}는 대기 페이지 JS에
     * 박히는 공개값이고, 노출되는 것은 admit 진행률과 pacing 표뿐이라 둘 다 대기자가 알아야 하는 값이다.
     *
     * <p>🔴 <b>제한이 없는 것은 알고 그렇게 둔 것이다.</b> {@code RateLimitFilter}가 미등록 public
     * 경로를 무조건 통과시킨다. 달지 않는 이유 셋: 큐 단위 버킷은 30만 명이 공유해 남용자 1명이
     * 정상 대기자 전원을 429시키고, 리미터 Lua가 이 경로의 {@code MGET}보다 20~25배 비싸며,
     * 개인 폴링에 더 싼 우회로가 있어 효과가 0이다. L7 flood는 CDN·WAF 소관이다.
     * 실측 근거(미지 queueId = 3왕복 · 8개 마스터 확산)는 {@code doc/FRS_final.md} §6.3 가드레일.
     *
     * <p>🔁 <b>재검토 트리거</b>: 프로덕션급 노드에서 한 마스터의 {@code MGET} 실처리량이
     * <b>30,000 ops/s 미만</b>이면 앱 내 스냅샷 캐시(§79 D1 보류분)를 다시 연다.
     * {@code redis-benchmark -h <master> -p <port> -t mget -n 500000 -c 50 --threads 4}
     *
     * <p>응답의 {@code data}는 전원 동일하다 — 캐시는 가능하지만 지금은 붙이지 않는다(§79 D1).
     */
    @GetMapping("/{queueId}/status")
    public ResponseEntity<ApiResponse<QueueStatusResponse>> status(@PathVariable String queueId) {
        QueueBoard status = queueEngineService.status(queueId);
        return ResponseEntity.ok(ApiResponse.ok(QueueStatusResponse.from(status)));
    }

    /**
     * 개인 상태 폴링 (End-user 브라우저가 Platform 직접 호출).
     *
     * <p>GET /api/v1/queues/{queueId}/tokens/{tokenId}?seq={mySeq}&ka={0|1}
     *
     * <p><b>인증 없음(permitAll)</b> — tokenId 소유가 곧 자격(capability).
     * enqueue/admit 등과 달리 X-API-Key를 받지 않는다. 실제 유효성은
     * 서비스에서 tokenId·seq로 판정하며, 미존재 시 404(TOKEN_NOT_FOUND).
     *
     * <p>차례가 가까울 때({@code rank <= 0})와 keepalive(30~60초 1회)에만 호출된다. 평상시
     * 폴링은 {@code /status}가 받는다 (§79).
     *
     * @param queueId 대기열 ID
     * @param tokenId 대기 토큰(자격 증명)
     * @param seq     enqueue 때 발급된 내 순번(mySeq) — 소유권 대조/keepalive 기준.
     *                <b>뺄 수 없다</b> — {@code poll_verify.lua}가 이 값으로 대기 항목을 찾는다
     * @param ka      ⚠️ <b>무시된다</b>(§82 F안). 예전엔 이 값이 {@code last-active} 갱신을
     *                결정했으나, 지금은 <b>폴링이 오면 언제나 갱신</b>한다 — 클라이언트가 안 붙이면
     *                살아 있는 대기자가 회수됐다. API 하위호환으로 파라미터만 남는다
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
