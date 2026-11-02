package com.smartdrone.api.error;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.smartdrone.application.common.ErrorCode;

import jakarta.servlet.http.HttpServletResponse;

// Ghi JSON lỗi chuẩn ở tầng filter (Spring Security chạy trước controller nên
// GlobalExceptionHandler không bắt được lỗi 401/403 ở đây).
// Cùng hình dạng với ProblemDetail của GlobalExceptionHandler:
// type, title, status, detail, code, message, traceId.
public final class ProblemJson {

    private ProblemJson() {
    }

    public static void write(HttpServletResponse response, ErrorCode code) throws IOException {
        String detail = code.getDefaultMessage();
        String body = "{\"type\":\"about:blank\""
                + ",\"title\":\"" + escape(code.getDefaultMessage()) + "\""
                + ",\"status\":" + code.getStatus().value()
                + ",\"detail\":\"" + escape(detail) + "\""
                + ",\"code\":\"" + code.name() + "\""
                + ",\"message\":\"" + escape(detail) + "\""
                + ",\"traceId\":\"" + UUID.randomUUID() + "\"}";

        response.setStatus(code.getStatus().value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }

    // Các chuỗi truyền vào đều là hằng cố định trong ErrorCode, chỉ cần escape " và \.
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
