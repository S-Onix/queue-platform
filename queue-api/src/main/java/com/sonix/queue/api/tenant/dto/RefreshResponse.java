package com.sonix.queue.api.tenant.dto;

import lombok.Getter;

@Getter
public class RefreshResponse {
    private String accessToken;
    private String refreshToken;

    private RefreshResponse(String accessToken, String refreshToken) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }

    public static RefreshResponse of(String accessToken, String refreshToken) {
        return new RefreshResponse(accessToken, refreshToken);
    }
}
