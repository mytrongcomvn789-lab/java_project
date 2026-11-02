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