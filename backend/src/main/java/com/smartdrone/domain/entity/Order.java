package com.smartdrone.domain.entity;

import java.time.Instant;
import java.util.UUID;

import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.workflow.StatefulOrder;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

// Tên bảng là "orders" vì ORDER là từ khóa của SQL.
@Entity
@Table(name = "orders", indexes = {
        @Index(name = "ix_orders_customer_created", columnList = "customer_id, created_at"),
        @Index(name = "ix_orders_status", columnList = "status"),
        @Index(name = "ix_orders_pickup_created", columnList = "pickup_station_id, created_at"),
        @Index(name = "ix_orders_dropoff_created", columnList = "dropoff_station_id, created_at")})
public class Order implements StatefulOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Hibernate tự tăng mỗi lần đơn được ghi lại: nền của ETag/If-Match.
    @Version
    private long version;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    // Giữ stationId dưới dạng UUID để đơn cũ tiếp tục tham chiếu trạm sau khi trạm ngừng hoạt động.
    @Column(name = "pickup_station_id", nullable = false)
    private UUID pickupStationId;

    @Column(name = "dropoff_station_id", nullable = false)
    private UUID dropoffStationId;

    @Embedded
    private RecipientSnapshot recipient;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Order() { }

    public Order(UUID customerId, UUID pickupStationId, UUID dropoffStationId,
                 RecipientSnapshot recipient, Instant now) {
        this.customerId = customerId;
        this.pickupStationId = pickupStationId;
        this.dropoffStationId = dropoffStationId;
        this.recipient = recipient;
        this.status = OrderStatus.PENDING; // đơn mới luôn bắt đầu ở PENDING
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Sửa nội dung đơn (chỉ làm khi PENDING, service kiểm tra). Luôn cập nhật updatedAt để version tăng. */
    public void updateDetails(UUID pickupStationId, UUID dropoffStationId,
                              RecipientSnapshot recipient, Instant now) {
        this.pickupStationId = pickupStationId;
        this.dropoffStationId = dropoffStationId;
        this.recipient = recipient;
        this.updatedAt = now;
    }

    /** Chỉ OrderTransitionService được gọi hàm này; không nơi nào khác được đổi trạng thái. */
    @Override
    public void changeStatus(OrderStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    @Override public UUID getId() { return id; }
    @Override public long getVersion() { return version; }
    @Override public OrderStatus getStatus() { return status; }
    public UUID getCustomerId() { return customerId; }
    public UUID getPickupStationId() { return pickupStationId; }
    public UUID getDropoffStationId() { return dropoffStationId; }
    public RecipientSnapshot getRecipient() { return recipient; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}