package com.sonix.queue.api.queue.dto;

import java.time.LocalDateTime;

/** Complete 응답 (FRS §6.6). {@code completedAt}은 UTC 벽시계다 (§77). */
public record CompleteResponse(String status, LocalDateTime completedAt) {
}
