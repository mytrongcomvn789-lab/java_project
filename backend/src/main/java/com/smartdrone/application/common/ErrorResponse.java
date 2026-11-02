package com.smartdrone.application.common;

import java.time.Instant;

// Hình dạng JSON lỗi mà backend trả về cho web/mobile.
// "record" là cách viết class chỉ chứa dữ liệu, ngắn gọn (Java 16+).
public record ErrorResponse(
        int status,        // 400, 404, 500...
        String error,      // "Bad Request", "Not Found"...
        String message,    // câu thông báo cho người dùng (web đọc trường này)
        String path,       // đường dẫn gây lỗi
        Instant timestamp) {
}