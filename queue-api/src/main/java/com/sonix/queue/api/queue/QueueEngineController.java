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
     * admitToken 유효성 확인 (Verify). <b>상태를 바꾸지 않는다.</b>
     *
     * <p>POST /api/v1/queues/{queueId}/admit-tokens/{admitToken}/verify
     *
     * <p>경로에 queueId가 있는 이유는 Redis 키를 {@code queue:&#123;queueId&#125;:*} 해시태그로
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
     * 박히는 사실상의 공개값이라 인증으로 막을 수 있는 게 없고, 노출되는 것은 admit 진행률과
     * pacing 표뿐이라 둘 다 대기자가 알아야 하는 값이다.
     *
     * <p><b>Rate Limit을 일부러 안 거는 것이다.</b> {@code RateLimitFilter}가 미등록 public 경로를
     * 무조건 통과시키므로 이 경로는 "인증 0 + 제한 0"이 된다 — 모르고 두는 것과 다르다.
     * 큐 단위 버킷은 30만 명이 하나를 공유해 남용자 1명이 정상 대기자 전원을 429시키므로
     * 오히려 해롭고, 미지 {@code queueId}는 {@code MGET}의 {@code seq} 키가 Redis 1왕복 안에서
     * 404로 끝내 DB로 내려가지 않는다. 남는 위험은 L7 flood이며 CDN·WAF 소관이다.
     *
     * <p>응답은 <b>전원 동일</b>하다. 캐시(WAS·CDN)는 그래서 가능해지지만 지금은 붙이지 않는다
     * (§79 D1 — 적중률 미측정 상태에서 만들면 정작 효과가 큰 CDN 도입이 미뤄진다).
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
     * @param ka      keepalive 플래그(30~60s에 1회만 1) — inactive_ttl 갱신
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
