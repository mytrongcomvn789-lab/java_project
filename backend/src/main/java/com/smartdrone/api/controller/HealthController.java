package com.smartdrone.api.controller;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;             
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.application.common.ApiException;

// GET /api/health  (context-path /api được cấu hình trong application.yml)
// Web (task 2.2) gọi endpoint này ở trang chủ để kiểm tra kết nối.
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "time", Instant.now().toString());
    }
    // ===== TẠM: chỉ để thử, xóa sau khi test xong =====
    @GetMapping("/health/fail")
    public Map<String, Object> fail() {
        // Mình tự ném lỗi nghiệp vụ, mã 400
        throw new ApiException(HttpStatus.BAD_REQUEST, "Thử lỗi 400");
    }

    @GetMapping("/health/crash")
    public Map<String, Object> crash() {
        // Giả làm code bị bug bất ngờ
        throw new RuntimeException("boom");
    }
    // ===== HẾT PHẦN TẠM =====
}

