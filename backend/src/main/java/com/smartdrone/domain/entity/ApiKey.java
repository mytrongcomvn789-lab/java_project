package com.smartdrone.domain.entity;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.Nationalized;

import com.smartdrone.domain.enums.ApiKeyStatus;
import com.smartdrone.domain.enums.IntegrationScope;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/**
 * Tín dụng (credential) cho hệ thống ngoài gọi vào /v1/integration/**. KHÔNG dùng để đăng nhập thay người
 * dùng: không có customerId/userId nào đứng sau một API key, chỉ có clientName (tên hệ thống đối tác) và scope.
 * Chỉ lưu sha256(secret); secret gốc chỉ hiện ra đúng một lần lúc cấp hoặc xoay vòng (xem ApiKeyService).
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Nationalized
    @Column(name = "client_name", nullable = false, length = 150)
    private String clientName;

    @Column(name = "secret_hash", nullable = false, length = 64)
    private String secretHash;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "api_key_scopes", joinColumns = @JoinColumn(name = "api_key_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 30)
    private Set<IntegrationScope> scopes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApiKeyStatus status = ApiKeyStatus.ACTIVE;

    @Column(name = "rate_limit_per_minute", nullable = false)
    private int rateLimitPerMinute;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt; // null = không hết hạn

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected ApiKey() { }

    public ApiKey(String clientName, String secretHash, Set<IntegrationScope> scopes, int rateLimitPerMinute,
            UUID createdBy, Instant expiresAt, Instant createdAt) {
        this.clientName = clientName;
        this.secretHash = secretHash;
        this.scopes = EnumSet.copyOf(scopes);
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public boolean isUsable(Instant now) {
        return status == ApiKeyStatus.ACTIVE && (expiresAt == null || expiresAt.isAfter(now));
    }

    public void revoke(Instant now) {
        this.status = ApiKeyStatus.REVOKED;
        this.revokedAt = now;
    }

    /** Xoay vòng: đổi secret, KHÔNG đổi scope hay id. Secret cũ ngưng hoạt động ngay. */
    public void rotateSecret(String newSecretHash) {
        this.secretHash = newSecretHash;
    }

    public void touchLastUsed(Instant now) {
        this.lastUsedAt = now;
    }

    public UUID getId() { return id; }
    public String getClientName() { return clientName; }
    public String getSecretHash() { return secretHash; }
    public Set<IntegrationScope> getScopes() { return scopes; }
    public ApiKeyStatus getStatus() { return status; }
    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
}