package com.sonix.queue.api.queue.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class QueueUpdateRequest {
    @NotBlank
    private String name;
}
