package com.smartdrone.domain.entity;

import java.time.Instant;
import java.util.UUID;

import com.smartdrone.domain.enums.ChallengeStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Challenge nhận hàng. KHÔNG lưu OTP/QR: chúng được tính lại từ HMAC(secret, id + nonce).
 * Trạng thái/bộ đếm chỉ đổi bằng câu UPDATE nguyên tử trong PickupChallengeRepository.
 */
@Entity
@Table(name = "pickup_challenges",
        uniqueConstraints = @UniqueConstraint(name = "uq_pickup_challenge_order_seq",
                columnNames = {"order_id", "sequence_no"}),
        indexes = @Index(name = "ix_pickup_challenge_order_status", columnList = "order_id, status"))
public class PickupChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "attempt_id", nullable = false, updatable = false)
    private UUID attemptId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "dropoff_station_id", nullable = false, updatable = false)
    private UUID dropoffStationId;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    @Column(nullable = false, length = 64, updatable = false)
    private String nonce;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChallengeStatus status = ChallengeStatus.ACTIVE;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "used_by")
    private UUID usedBy;

    protected PickupChallenge() { }

    public PickupChallenge(UUID orderId, UUID attemptId, UUID customerId, UUID dropoffStationId,
                           int sequenceNo, String nonce, Instant expiresAt, Instant createdAt) {
        this.orderId = orderId;
        this.attemptId = attemptId;
        this.customerId = customerId;
        this.dropoffStationId = dropoffStationId;
        this.sequenceNo = sequenceNo;
        this.nonce = nonce;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getAttemptId() { return attemptId; }
    public UUID getCustomerId() { return customerId; }
    public UUID getDropoffStationId() { return dropoffStationId; }
    public int getSequenceNo() { return sequenceNo; }
    public String getNonce() { return nonce; }
    public ChallengeStatus getStatus() { return status; }
    public int getFailedAttempts() { return failedAttempts; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUsedAt() { return usedAt; }
    public UUID getUsedBy() { return usedBy; }
}