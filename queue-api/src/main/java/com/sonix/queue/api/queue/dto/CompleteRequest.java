package com.sonix.queue.api.queue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Complete 요청 (FRS §6.6). 탐색 키인 tokenId는 URL에 있고, 여기 admitToken은 <b>입장 자격</b>이다.
 */
public record CompleteRequest(
        @NotBlank(message = "admitToken is required")
        @Size(max = 50, message = "admitToken must be at most 50 characters")
        String admitToken
) {
}
