package com.sonix.queue.api.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RefreshRequest {
    @NotBlank
    String token;

    public RefreshRequest() {}

    public RefreshRequest(String token) {
        this.token = token;
    }
}
