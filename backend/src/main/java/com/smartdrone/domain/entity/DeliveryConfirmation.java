package com.smartdrone.domain.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Nationalized;

import com.smartdrone.domain.enums.ConfirmationMethod;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Bằng chứng giao hàng. Ràng buộc unique là lớp chặn cuối: 1 đơn, 1 challenge, 1 ảnh chỉ dùng một lần. */
@Entity
@Table(name = "delivery_confirmations", uniqueConstraints = {
        @UniqueConstraint(name = "uq_delivery_confirmation_order", columnNames = "order_id"),
        @UniqueConstraint(name = "uq_delivery_confirmation_challenge", columnNames = "challenge_id"),
        @UniqueConstraint(name = "uq_delivery_confirmation_photo", columnNames = "photo_file_id")})
public class DeliveryConfirmation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "attempt_id", nullable = false, updatable = false)
    private UUID attemptId;

    @Column(name = "challenge_id", nullable = false, updatable = false)
    private UUID challengeId;

    @Column(name = "confirmed_by", nullable = false, updatable = false)
    private UUID confirmedBy;

    @Column(name = "station_id", nullable = false, updatable = false)
    private UUID stationId;

    @Column(name = "photo_file_id", nullable = false, updatable = false)
    private UUID photoFileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private ConfirmationMethod method;

    @Nationalized
    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private Instant confirmedAt;

    @Column(name = "order_version", nullable = false, updatable = false)
    private long orderVersion;

    protected DeliveryConfirmation() { }

    public DeliveryConfirmation(UUID orderId, UUID attemptId, UUID challengeId, UUID confirmedBy, UUID stationId,
                                UUID photoFileId, ConfirmationMethod method, String note,
                                Instant confirmedAt, long orderVersion) {
        this.orderId = orderId;
        this.attemptId = attemptId;
        this.challengeId = challengeId;
        this.confirmedBy = confirmedBy;
        this.stationId = stationId;
        this.photoFileId = photoFileId;
        this.method = method;
        this.note = note;
        this.confirmedAt = confirmedAt;
        this.orderVersion = orderVersion;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getAttemptId() { return attemptId; }
    public UUID getChallengeId() { return challengeId; }
    public UUID getConfirmedBy() { return confirmedBy; }
    public UUID getStationId() { return stationId; }
    public UUID getPhotoFileId() { return photoFileId; }
    public ConfirmationMethod getMethod() { return method; }
    public String getNote() { return note; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public long getOrderVersion() { return orderVersion; }
}