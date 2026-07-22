package com.sonix.queue.api.queue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class EnqueueRequest {
    @NotBlank(message = "identifier is required")
    @Size(max = 100, message = "identifier must be at most 100 characters")
    private String identifier;

    // Jackson 역직렬화용 기본 생성자
    public EnqueueRequest() {
    }

    public EnqueueRequest(String identifier) {
        this.identifier = identifier;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }
}
