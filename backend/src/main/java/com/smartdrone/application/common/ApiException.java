package com.smartdrone.application.common;

import org.springframework.http.HttpStatus;

// Lỗi nghiệp vụ có chủ đích. GlobalExceptionHandler đổi nó thành JSON lỗi chuẩn.
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus status;

    public ApiException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.status = errorCode.getStatus();
    }

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = errorCode.getStatus();
    }

    // Tương thích các caller của middleware 2.4 sau khi ghép với auth 2.5.
    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
        this.errorCode = switch (status) {
            case BAD_REQUEST -> ErrorCode.VALIDATION_ERROR;
            case UNAUTHORIZED -> ErrorCode.AUTH_REQUIRED;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case NOT_FOUND -> ErrorCode.RESOURCE_NOT_FOUND;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.REQUEST_ERROR;
        };
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
