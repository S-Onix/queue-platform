package com.queueplatform.common.exception;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    @Test
    void INTERNAL_SERVER_ERROR_코드값_검증() {
        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
        assertThat(errorCode.getCode()).isEqualTo("I004");
    }

    @Test
    void INTERNAL_SERVER_ERROR_HTTP상태코드_검증() {
        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
        assertThat(errorCode.getHttpStatus()).isEqualTo(500);
    }

    @Test
    void INTERNAL_SERVER_ERROR_메시지_검증() {
        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
        assertThat(errorCode.getMessage()).isEqualTo("서버 오류가 발생했습니다.");
    }
}