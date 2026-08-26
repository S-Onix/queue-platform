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
     * 🔴 <b>최소 12자.</b> 이 제약이 없을 때 {@code "1234"}가 가입됐고, 그 상태에서는
     * Rate Limit을 어떤 축으로 걸어도 brute force를 못 막는다 — 현행 {@code LOGIN 10/분/IP}로도
     * 노트북 한 대가 상위 1,000개 사전을 <b>100분</b>에 완주한다.
     *
     * <p>뚫리면 콘솔 JWT로 <b>API Key를 자가 발급</b>할 수 있어(§ApiKeyController) enqueue·admit
     * 전권과 과금(= {@code tokens} 행 수, §84)까지 넘어간다. 계정 하나가 곧 테넌트 전체다.
     *
     * <p><b>왜 Rate Limit 축을 늘리는 대신 이것인가</b>: 계정(email) 축을 실용적 임계로 걸어도
     * 공격 속도가 상수배 느려질 뿐 같은 곡선이다. 길이는 탐색 공간을 자릿수로 바꾼다.
     * 사다리에서 더 높은 칸이라 여기서 끝낸다.
     *
     * <p>⚠️ <b>{@code LoginRequest}에는 걸지 않는다.</b> 걸면 정책 변경 전에 만든 짧은 비밀번호
     * 계정이 로그인 자체를 못 하고, 400 응답이 <b>정책을 그대로 노출</b>한다.
     */
    @NotBlank
    @Size(min = 12, message = "password must be at least 12 characters")
    private String password;

    @NotBlank
    private String name;
}
