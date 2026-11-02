package com.smartdrone.api.exception;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

// Bắt lỗi từ MỌI controller và đổi thành JSON ErrorResponse.
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 1. Lỗi nghiệp vụ mình tự ném
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex, HttpServletRequest req) {
        return build(ex.getStatus(), ex.getMessage(), req);
    }

    // 2. Gõ sai đường dẫn (VD: /api/abc) -> 404
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "Không tìm thấy đường dẫn: " + req.getRequestURI(), req);
    }

    // 3. Mọi lỗi còn lại (bug) -> 500, KHÔNG lộ chi tiết cho người dùng
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception ex, HttpServletRequest req) {
        log.error("Lỗi không mong muốn tại {}", req.getRequestURI(), ex); // chi tiết chỉ ghi vào log
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi hệ thống, vui lòng thử lại sau", req);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest req) {
        ErrorResponse body = new ErrorResponse(
                status.value(), status.getReasonPhrase(), message, req.getRequestURI(), Instant.now());
        return ResponseEntity.status(status).body(body);
    }
}