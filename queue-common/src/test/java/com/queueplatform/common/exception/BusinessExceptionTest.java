package com.queueplatform.common.exception;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BusinessExceptionTest {
    private ErrorCode errorCode;

    @BeforeEach
    void setUp(){
        errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
    }

    @Test
    void BusinessException_ErrorCode_저장_검증() {
        BusinessException exception = new BusinessException(errorCode);
        assertThat(exception.getErrorCode()).isEqualTo(errorCode);
    }

    @Test
    void BusinessException_메시지_검증() {
        BusinessException exception = new BusinessException(errorCode);
        assertThat(exception.getMessage()).isEqualTo(errorCode.getMessage());
    }

    @Test
    void BusinessException_RuntimeException_상속_검증() {
        BusinessException exception = new BusinessException(errorCode);
        assertThat(exception).isInstanceOf(RuntimeException.class);
    }
}