package com.smartdrone.application.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Đọc từ application.yml: app.jwt.*
// Khóa ký lấy từ biến môi trường APP_JWT_KEY khi chạy thật (không commit khóa thật).
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String key,
        String issuer,
        String audience,
        long accessTokenMinutes,
        long refreshTokenDays) {
}
