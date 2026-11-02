package com.smartdrone.application.common;

import org.springframework.http.HttpStatus;

// Lỗi nghiệp vụ do chính mình chủ động ném ra.
// Ví dụ: throw new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng");
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
// Lỗi nghiệp vụ có chủ đích. GlobalExceptionHandler đổi nó thành JSON lỗi chuẩn.
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
