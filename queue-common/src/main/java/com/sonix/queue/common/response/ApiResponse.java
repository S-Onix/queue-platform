package com.sonix.queue.common.response;

import com.sonix.queue.common.exception.ErrorCode;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class ApiResponse<T> {
    private final T data;
    private final boolean isSuccess;
    private final LocalDateTime timestamp;
    private final ErrorResponse errorResponse;

    // 다른쪽에서 객체 생성을 못하게 하기 위해서
    private ApiResponse (T data, boolean isSuccess, LocalDateTime timestamp, ErrorResponse errorResponse){
        this.data = data;
        this.isSuccess = isSuccess;
        this.timestamp = timestamp;
        this.errorResponse = errorResponse;
    }

    public static <T> ApiResponse<T> ok(T data){
        return new ApiResponse<T>(data, true, LocalDateTime.now(), null);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode) {
        return new ApiResponse<Void>(null, false, LocalDateTime.now(), ErrorResponse.of(errorCode));
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
