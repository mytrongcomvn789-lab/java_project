package com.smartdrone.application.apikeys;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.auth.TokenHasher;
import com.smartdrone.domain.entity.ApiKey;
import com.smartdrone.domain.enums.IntegrationScope;
import com.smartdrone.infrastructure.persistence.ApiKeyRepository;

/**
 * Xác thực header "X-Api-Key: {id}.{secret}" cho /v1/integration/**. KHÔNG liên quan JWT, không có khái niệm
 * "người dùng" ở đây — kết quả chỉ là id của key và scope của nó, dùng làm authorities trong Spring Security.
 */
@Component
public class ApiKeyAuthenticator {
    public enum Failure { MALFORMED, NOT_FOUND, INACTIVE, SECRET_MISMATCH, RATE_LIMITED }

    public record Authenticated(UUID keyId, Set<IntegrationScope> scopes) { }

    private final ApiKeyRepository repository;
    private final ApiKeyRateLimiter rateLimiter;
    private final Clock clock;

    public ApiKeyAuthenticator(ApiKeyRepository repository, ApiKeyRateLimiter rateLimiter, Clock clock) {
        this.repository = repository;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    @Transactional
    public Authenticated authenticate(String header) {
        int dot = header == null ? -1 : header.indexOf('.');
        if (dot <= 0 || dot == header.length() - 1) throw failure(Failure.MALFORMED);

        UUID id;
        try {
            id = UUID.fromString(header.substring(0, dot));
        } catch (IllegalArgumentException ex) {
            throw failure(Failure.MALFORMED);
        }
        String secret = header.substring(dot + 1);

        ApiKey key = repository.findForAuthentication(id).orElseThrow(() -> failure(Failure.NOT_FOUND));
        Instant now = clock.instant();
        if (!key.isUsable(now)) throw failure(Failure.INACTIVE);
        // So sánh thời gian không đổi (constant-time) để không lộ qua timing việc secret đúng gần hay xa.
        if (!java.security.MessageDigest.isEqual(
                TokenHasher.sha256Hex(secret).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                key.getSecretHash().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw failure(Failure.SECRET_MISMATCH);
        }
        if (!rateLimiter.allow(key.getId(), key.getRateLimitPerMinute())) throw failure(Failure.RATE_LIMITED);

        // Chỉ ghi lastUsedAt nếu đã quá 60s kể từ lần trước, để không ghi đĩa trên mỗi request.
        if (key.getLastUsedAt() == null || key.getLastUsedAt().isBefore(now.minusSeconds(60))) {
            key.touchLastUsed(now);
        }
        return new Authenticated(key.getId(), key.getScopes());
    }

    public static final class ApiKeyAuthException extends RuntimeException {
        private final Failure failure;
        public ApiKeyAuthException(Failure failure) { this.failure = failure; }
        public Failure failure() { return failure; }
    }

    private static ApiKeyAuthException failure(Failure f) {
        return new ApiKeyAuthException(f);
    }
}