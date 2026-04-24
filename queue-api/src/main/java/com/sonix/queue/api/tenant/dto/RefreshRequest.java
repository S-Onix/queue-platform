package com.sonix.queue.api.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class RefreshRequest {
    @NotBlank
    String token;
}
