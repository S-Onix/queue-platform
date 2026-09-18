package com.sonix.queue.api.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignupRequest {

    @NotBlank
    @Email
    private String email;

    /**
     * 이유: 🔴 <b>비밀번호 최소 12자.</b> 제약이 없을 때 {@code "1234"} 가 가입됐다.
     * 문제: 10/분/IP 로도 노트북 한 대가 상위 1,000개 사전을 <b>100분</b>에 완주한다.
     * 원인: 뚫리면 콘솔 JWT 로 <b>API Key 를 자가 발급</b>해 전권과 과금까지 넘어간다.
     * 해결: 길이로 탐색 공간 자체를 키운다 — 축을 늘리면 상수배 느려질 뿐 같은 곡선이다.
     * ⚠️ {@code LoginRequest} 에는 걸지 마라 — 옛 계정이 로그인을 못 하고 정책이 노출된다.
     */
   @NotBlank
    @Size(min = 12, message = "password must be at least 12 characters")
    private String password;

    @NotBlank
    private String name;
}
