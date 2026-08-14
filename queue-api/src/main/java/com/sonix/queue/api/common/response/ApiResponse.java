package com.sonix.queue.api.common.response;

import com.sonix.queue.common.exception.ErrorCode;
import lombok.Getter;

import java.time.Instant;

@Getter
public class ApiResponse<T> {
    private final T data;
    private final boolean isSuccess;
    /**
     * 응답 생성 시각.
     *
     * <p>{@code Instant}인 이유: 이 프로젝트는 시각을 전부 UTC로 저장하는데(DECISIONS §77),
     * {@code LocalDateTime}으로 두면 존 정보 없이 {@code 2026-08-12T08:12:51}처럼 직렬화되어
     * 클라이언트가 자기 로컬 시각으로 읽는다. 한국 클라이언트면 9시간 어긋난다.
     * {@code Instant}는 {@code 2026-08-12T08:12:51.799Z}로 직렬화돼 UTC임이 값에 드러난다.
     */
    private final Instant timestamp;
    private final ErrorResponse errorResponse;

    // 다른쪽에서 객체 생성을 못하게 하기 위해서
    private ApiResponse (T data, boolean isSuccess, Instant timestamp, ErrorResponse errorResponse){
        this.data = data;
        this.isSuccess = isSuccess;
        this.timestamp = timestamp;
        this.errorResponse = errorResponse;
    }

    public static <T> ApiResponse<T> ok(T data){
        return new ApiResponse<T>(data, true, Instant.now(), null);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode) {
        return new ApiResponse<Void>(null, false, Instant.now(), ErrorResponse.of(errorCode));
    }


    @Getter
    static class ErrorResponse {
        private final String code;
        private final String message;

        private ErrorResponse (String code, String message){
            this.code = code;
            this.message = message;
        }

        public static ErrorResponse of(ErrorCode errorCode) {
            return new ErrorResponse(errorCode.getCode(), errorCode.getMessage());
        }
    }
}
