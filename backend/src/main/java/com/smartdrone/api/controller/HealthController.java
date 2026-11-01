package com.smartdrone.api.controller;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// GET /api/health  (context-path /api được cấu hình trong application.yml)
// Web (task 2.2) gọi endpoint này ở trang chủ để kiểm tra kết nối.
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "time", Instant.now().toString());
    }
}
