package com.smartdrone.api.error;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;

// Đổi mọi lỗi thành JSON chuẩn (ProblemDetail + code + message + traceId).
// "message" được giữ để web hiện tại (apiClient.ts) đọc được lỗi như trước.
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApiException(ApiException ex) {
        return build(ex.getErrorCode(), ex.getMessage(), null);
    }

    // Task 2.6 dùng @PreAuthorize: AccessDeniedException phải ra 403 chứ không phải 500.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex) {
        return build(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getDefaultMessage(), null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(AuthenticationException ex) {
        return build(ErrorCode.AUTH_REQUIRED, ErrorCode.AUTH_REQUIRED.getDefaultMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        String traceId = UUID.randomUUID().toString();
        log.error("Lỗi không mong đợi, traceId={}", traceId, ex);
        // Không trả chi tiết lỗi nội bộ ra ngoài.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                ErrorCode.INTERNAL_ERROR.getStatus(), ErrorCode.INTERNAL_ERROR.getDefaultMessage());
        problem.setTitle(ErrorCode.INTERNAL_ERROR.getDefaultMessage());
        problem.setProperty("code", ErrorCode.INTERNAL_ERROR.name());
        problem.setProperty("message", ErrorCode.INTERNAL_ERROR.getDefaultMessage());
        problem.setProperty("traceId", traceId);
        return respond(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", fe.getDefaultMessage() == null ? "Không hợp lệ" : fe.getDefaultMessage()))
                .toList();
        String detail = errors.isEmpty() ? ErrorCode.VALIDATION_ERROR.getDefaultMessage() : errors.get(0).get("message");
        return build(ErrorCode.VALIDATION_ERROR, detail, errors);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return build(ErrorCode.VALIDATION_ERROR, "Nội dung yêu cầu không phải JSON hợp lệ", null);
    }

    // Lỗi MVC chuẩn (405, 404, 415...) cũng được bổ sung code/message/traceId.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
                problem.setProperty("code", "REQUEST_ERROR");
            }
            problem.setProperty("message", problem.getDetail());
            problem.setProperty("traceId", UUID.randomUUID().toString());
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private ResponseEntity<Object> build(ErrorCode code, String detail, List<Map<String, String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.getStatus(), detail);
        problem.setTitle(code.getDefaultMessage());
        problem.setProperty("code", code.name());
        problem.setProperty("message", detail);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        if (errors != null) {
            problem.setProperty("errors", errors);
        }
        return respond(problem);
    }

    private ResponseEntity<Object> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
