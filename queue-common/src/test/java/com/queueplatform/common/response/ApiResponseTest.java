package com.queueplatform.common.response;

import com.queueplatform.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ApiResponseTest {

    private ErrorCode errorCode;

    @BeforeEach
    void setUp(){
        errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
    }

    @Test
    void ok_성공여부_검증() {
        ApiResponse<String> response = ApiResponse.ok("test");
        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    void ok_데이터_검증() {
        ApiResponse<String> response = ApiResponse.ok("test");
        assertThat(response.getData()).isEqualTo("test");
    }

    @Test
    void ok_에러응답_null_검증() {
        ApiResponse<String> response = ApiResponse.ok("test");
        assertThat(response.getErrorResponse()).isNull();
    }

    @Test
    void fail_성공여부_검증() {
        ApiResponse<Void> response = ApiResponse.fail(errorCode);
        assertThat(response.isSuccess()).isFalse();
    }

    @Test
    void fail_에러코드_검증() {
        ApiResponse<Void> response = ApiResponse.fail(errorCode);
        assertThat(response.getErrorResponse().getCode()).isEqualTo("I004");
    }

    @Test
    void fail_에러메시지_검증() {
        ApiResponse<Void> response = ApiResponse.fail(errorCode);
        assertThat(response.getErrorResponse().getMessage()).isEqualTo("서버 오류가 발생했습니다.");
    }

    @Test
    void fail_데이터_null_검증() {
        ApiResponse<Void> response = ApiResponse.fail(errorCode);
        assertThat(response.getData()).isNull();
    }
}