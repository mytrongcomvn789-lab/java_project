package com.smartdrone.application.auth.dto;

// Kết quả của login và refresh.
// expiresIn = số giây còn hiệu lực của accessToken.
// "user" là trường bổ sung so với APDDMP-9 để web biết role ngay sau khi đăng nhập.
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserProfile user) {
}
