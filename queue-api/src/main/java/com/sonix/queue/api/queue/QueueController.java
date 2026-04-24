package com.sonix.queue.api.queue;

import com.sonix.queue.api.queue.dto.QueueCreateRequest;
import com.sonix.queue.api.queue.dto.QueueResponse;
import com.sonix.queue.api.queue.dto.QueueUpdateRequest;
import com.sonix.queue.api.security.TenantAuth;
import com.sonix.queue.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class QueueController {
    private final QueueService queueService;

    public QueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping("/api/v1/queues")
    public ApiResponse<QueueResponse> create(@AuthenticationPrincipal TenantAuth auth,
                                             @RequestBody @Valid QueueCreateRequest request) {
        return ApiResponse.ok(queueService.createQueue(auth.getId(), request));
    }

    @GetMapping("/api/v1/queues/{queueId}")
    public ApiResponse<QueueResponse> get(@AuthenticationPrincipal TenantAuth auth,
                                          @PathVariable("queueId") String queueId) {
        return ApiResponse.ok(queueService.getQueue(auth.getId(), queueId));
    }

    @PatchMapping("/api/v1/queues/{queueId}")
    public ApiResponse<QueueResponse> update(@AuthenticationPrincipal TenantAuth auth,
                                             @PathVariable("queueId") String queueId,
                                             @RequestBody @Valid QueueUpdateRequest request) {
        return ApiResponse.ok(queueService.updateQueue(auth.getId(), queueId, request));
    }

    @PostMapping("/api/v1/queues/{queueId}/pause")
    public ApiResponse<QueueResponse> pause(@AuthenticationPrincipal TenantAuth auth,
                                            @PathVariable("queueId") String queueId) {
        return ApiResponse.ok(queueService.pauseQueue(auth.getId(), queueId));
    }

    @PostMapping("/api/v1/queues/{queueId}/resume")
    public ApiResponse<QueueResponse> resume(@AuthenticationPrincipal TenantAuth auth,
                                             @PathVariable String queueId) {
        return ApiResponse.ok(queueService.resumeQueue(auth.getId(), queueId));
    }

    @DeleteMapping("/api/v1/queues/{queueId}")
    public ApiResponse<QueueResponse> delete(@AuthenticationPrincipal TenantAuth auth,
                                             @PathVariable String queueId) {
        return ApiResponse.ok(queueService.deleteQueue(auth.getId(), queueId));
    }
}
