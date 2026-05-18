package com.sonix.queue.api.queue.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QueueUpdateRequest {
    @NotBlank
    private String name;
}
