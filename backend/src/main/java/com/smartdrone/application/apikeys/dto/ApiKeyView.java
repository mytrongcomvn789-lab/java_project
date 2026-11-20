package com.smartdrone.application.apikeys.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.smartdrone.domain.entity.ApiKey;
import com.smartdrone.domain.enums.ApiKeyStatus;
import com.smartdrone.domain.enums.IntegrationScope;

/** Không bao giờ chứa secret hay secretHash. effectiveStatus tính thêm EXPIRED để admin thấy đúng thực tế. */
public record ApiKeyView(UUID id, String clientName, Set<IntegrationScope> scopes, ApiKeyStatus status,
                         String effectiveStatus, int rateLimitPerMinute, UUID createdBy, Instant createdAt,
                         Instant expiresAt, Instant revokedAt, Instant lastUsedAt) {

    public static ApiKeyView from(ApiKey key, Instant now) {
        String effective = key.getStatus() == ApiKeyStatus.REVOKED ? "REVOKED"
                : key.getExpiresAt() != null && !key.getExpiresAt().isAfter(now) ? "EXPIRED"
                : "ACTIVE";
        return new ApiKeyView(key.getId(), key.getClientName(), key.getScopes(), key.getStatus(), effective,
                key.getRateLimitPerMinute(), key.getCreatedBy(), key.getCreatedAt(), key.getExpiresAt(),
                key.getRevokedAt(), key.getLastUsedAt());
    }
}