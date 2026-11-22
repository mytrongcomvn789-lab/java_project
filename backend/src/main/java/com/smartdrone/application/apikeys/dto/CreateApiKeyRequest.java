package com.smartdrone.application.apikeys.dto;

import java.time.Instant;
import java.util.Set;

import com.smartdrone.domain.enums.IntegrationScope;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record CreateApiKeyRequest(
        @NotBlank(message = "clientName không được để trống")
        @Size(max = 150, message = "clientName tối đa 150 ký tự")
        String clientName,

        @NotEmpty(message = "Cần ít nhất 1 scope")
        Set<IntegrationScope> scopes,

        // null = không hết hạn. Phải ở tương lai nếu có.
        @Future(message = "expiresAt phải ở tương lai")
        Instant expiresAt,

        // null = dùng mặc định hệ thống (app.api-keys.default-rate-limit-per-minute).
        @Min(value = 1, message = "rateLimitPerMinute tối thiểu 1")
        @Max(value = 1000, message = "rateLimitPerMinute tối đa 1000")
        Integer rateLimitPerMinute) {
}