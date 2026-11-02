package com.smartdrone.application.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// POST /api/v1/auth/refresh và POST /api/v1/auth/logout dùng chung DTO này.
public record RefreshRequest(
        @NotBlank(message = "refreshToken không được để trống")
        @Size(max = 200, message = "refreshToken không hợp lệ")
        String refreshToken) {
}
