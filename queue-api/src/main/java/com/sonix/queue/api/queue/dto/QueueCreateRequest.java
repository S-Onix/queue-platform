package com.sonix.queue.api.queue.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QueueCreateRequest {
    @NotBlank
    private String name;
    private int maxCapacity;

    private Integer waitingTtl;
    private Integer inactiveTtl;

}
