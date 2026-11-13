package com.smartdrone.domain.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Ghi nhớ "key này của người này đã tạo ra đơn nào, với nội dung băm là gì". */
@Entity
@Table(name = "idempotency_keys",
        uniqueConstraints = @UniqueConstraint(name = "uq_idempotency_user_key",
                columnNames = {"user_id", "idem_key"}))
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    // Không đặt tên cột là "key" vì KEY là từ khóa SQL.
    @Column(name = "idem_key", nullable = false, length = 100, updatable = false)
    private String idemKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    // null chỉ trong khoảnh khắc giữa "chiếm key" và "tạo xong đơn" (cùng một transaction).
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRecord() { }

    public IdempotencyRecord(UUID userId, String idemKey, String requestHash, Instant now) {
        this.userId = userId;
        this.idemKey = idemKey;
        this.requestHash = requestHash;
        this.createdAt = now;
    }

    public void complete(UUID orderId) { this.orderId = orderId; }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getIdemKey() { return idemKey; }
    public String getRequestHash() { return requestHash; }
    public UUID getOrderId() { return orderId; }
    public Instant getCreatedAt() { return createdAt; }
}