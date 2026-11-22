package com.smartdrone.application.apikeys;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.admin.AuditService;
import com.smartdrone.application.auth.TokenHasher;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.apikeys.dto.ApiKeyIssued;
import com.smartdrone.application.apikeys.dto.ApiKeyPage;
import com.smartdrone.application.apikeys.dto.ApiKeyView;
import com.smartdrone.application.apikeys.dto.CreateApiKeyRequest;
import com.smartdrone.domain.entity.ApiKey;
import com.smartdrone.infrastructure.persistence.ApiKeyRepository;

/**
 * CRUD quản trị cho API key. Chỉ SYSTEM_ADMIN. Khác với ApiKeyAuthenticator (dùng lúc xác thực request ngoài,
 * không yêu cầu đăng nhập JWT) — hai lớp này cố ý tách riêng để không bao giờ nhầm quyền quản trị với quyền gọi API.
 */
@Service
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class ApiKeyService {
    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private final ApiKeyRepository repository;
    private final Clock clock;
    private final int defaultRateLimitPerMinute;
    private final AuditService audit;

    public ApiKeyService(ApiKeyRepository repository, Clock clock,
            @Value("${app.api-keys.default-rate-limit-per-minute:60}") int defaultRateLimitPerMinute,
            AuditService audit) {
        this.repository = repository;
        this.clock = clock;
        this.defaultRateLimitPerMinute = defaultRateLimitPerMinute;
        this.audit = audit;
    }

    @Transactional
    public ApiKeyIssued issue(UUID actorId, CreateApiKeyRequest request) {
        Instant now = clock.instant();
        String secret = TokenHasher.newOpaqueToken();
        int rateLimit = request.rateLimitPerMinute() == null ? defaultRateLimitPerMinute : request.rateLimitPerMinute();
        ApiKey key = new ApiKey(request.clientName(), TokenHasher.sha256Hex(secret), request.scopes(),
                rateLimit, actorId, request.expiresAt(), now);
        key = repository.save(key);
        log.info("API_KEY_ISSUED id={} client={} scopes={} by={}", key.getId(), key.getClientName(),
                key.getScopes(), actorId);
        // Task 2.4: ghi vào audit log chung. Không bao giờ ghi secret/rawKey.
        audit.record(actorId, "API_KEY_ISSUED", "API_KEY", key.getId().toString(),
                "client=" + key.getClientName() + ", scopes=" + key.getScopes() + ", rateLimitPerMinute=" + rateLimit
                        + ", expiresAt=" + key.getExpiresAt());
        // rawKey: "{id}.{secret}". Client phải lưu lại ngay; server không giữ bản gốc.
        return new ApiKeyIssued(ApiKeyView.from(key, now), key.getId() + "." + secret);
    }

    @Transactional(readOnly = true)
    public ApiKeyPage list(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "page >= 1; pageSize từ 1 đến 100");
        }
        var result = repository.findAllBy(PageRequest.of(page - 1, pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
        Instant now = clock.instant();
        return new ApiKeyPage(result.map(key -> ApiKeyView.from(key, now)).getContent(),
                page, pageSize, result.getTotalElements());
    }

    @Transactional
    public void revoke(UUID actorId, UUID id) {
        ApiKey key = repository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        key.revoke(clock.instant());
        log.info("API_KEY_REVOKED id={} by={}", id, actorId);
        audit.record(actorId, "API_KEY_REVOKED", "API_KEY", id.toString(), "client=" + key.getClientName());
    }

    @Transactional
    public ApiKeyIssued rotate(UUID actorId, UUID id) {
        ApiKey key = repository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (key.getStatus() != com.smartdrone.domain.enums.ApiKeyStatus.ACTIVE) {
            throw new ApiException(ErrorCode.REQUEST_ERROR, "Chỉ xoay vòng được key đang ACTIVE");
        }
        String secret = TokenHasher.newOpaqueToken();
        key.rotateSecret(TokenHasher.sha256Hex(secret));
        log.info("API_KEY_ROTATED id={} by={}", id, actorId);
        audit.record(actorId, "API_KEY_ROTATED", "API_KEY", id.toString(), "client=" + key.getClientName());
        return new ApiKeyIssued(ApiKeyView.from(key, clock.instant()), key.getId() + "." + secret);
    }
}