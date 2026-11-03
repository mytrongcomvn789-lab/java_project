package com.smartdrone.application.common;

import org.springframework.http.HttpStatus;

// Lỗi nghiệp vụ có chủ đích. GlobalExceptionHandler đổi nó thành JSON lỗi chuẩn.
// Hai cách dùng:
//   throw new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng");   // lỗi chung
//   throw new ApiException(ErrorCode.INVALID_CREDENTIALS);                       // lỗi có mã (auth)
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode errorCode; // null nếu dùng constructor (HttpStatus, String)

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
        this.errorCode = null;
    }

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage());
    }

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.status = errorCode.getStatus();
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
