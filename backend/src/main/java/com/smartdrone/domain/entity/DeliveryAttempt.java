package com.smartdrone.domain.entity;

import java.time.Instant;
import java.util.UUID;

import com.smartdrone.domain.enums.AttemptStatus;

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

// Một lần lên lịch giao hàng: giữ 1 chỗ ở trạm gửi và 1 chỗ ở trạm nhận trong khoảng [slotStart, slotEnd).
// Giao lại sau thất bại tạo dòng mới (attemptNo tăng), không sửa dòng cũ.
@Entity
@Table(name = "delivery_attempts",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_delivery_attempt_order_no",
                columnNames = {"order_id", "attempt_no"}),
        indexes = {
                @Index(name = "idx_delivery_attempt_pickup_slot", columnList = "pickup_station_id, slot_start"),
                @Index(name = "idx_delivery_attempt_dropoff_slot", columnList = "dropoff_station_id, slot_start")
        })
public class DeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    // Lần thử thứ mấy của đơn này, bắt đầu từ 1.
    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    @Column(name = "pickup_station_id", nullable = false, updatable = false)
    private UUID pickupStationId;

    @Column(name = "dropoff_station_id", nullable = false, updatable = false)
    private UUID dropoffStationId;

    @Column(name = "slot_start", nullable = false, updatable = false)
    private Instant slotStart;

    @Column(name = "slot_end", nullable = false, updatable = false)
    private Instant slotEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttemptStatus status = AttemptStatus.ACTIVE;

    // Điều phối viên đã lên lịch.
    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected DeliveryAttempt() { }

    public DeliveryAttempt(UUID orderId, int attemptNo, UUID pickupStationId, UUID dropoffStationId,
                           Instant slotStart, Instant slotEnd, UUID createdBy, Instant createdAt) {
        this.orderId = orderId;
        this.attemptNo = attemptNo;
        this.pickupStationId = pickupStationId;
        this.dropoffStationId = dropoffStationId;
        this.slotStart = slotStart;
        this.slotEnd = slotEnd;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    /** Trả chỗ. Gọi nhiều lần không sao (lần sau không làm gì). */
    public void release(Instant now) {
        if (status == AttemptStatus.RELEASED) {
            return;
        }
        status = AttemptStatus.RELEASED;
        releasedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public int getAttemptNo() { return attemptNo; }
    public UUID getPickupStationId() { return pickupStationId; }
    public UUID getDropoffStationId() { return dropoffStationId; }
    public Instant getSlotStart() { return slotStart; }
    public Instant getSlotEnd() { return slotEnd; }
    public AttemptStatus getStatus() { return status; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReleasedAt() { return releasedAt; }
}