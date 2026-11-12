package com.smartdrone.domain.entity;

import java.time.Instant;
import java.util.UUID;

import com.smartdrone.domain.enums.ChangeSource;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;

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

// Mỗi lần đơn đổi trạng thái ghi đúng 1 dòng. Chỉ thêm mới, không bao giờ sửa hay xóa.
@Entity
@Table(name = "order_status_history",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_order_status_history_version",
                columnNames = {"order_id", "order_version"}),
        indexes = @Index(name = "idx_order_status_history_order", columnList = "order_id"))
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    // Phiên bản của đơn SAU khi đổi. Cặp (order_id, order_version) là duy nhất.
    @Column(name = "order_version", nullable = false, updatable = false)
    private long orderVersion;

    // null khi đây là dòng đầu tiên (đơn vừa được tạo ở PENDING).
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20, updatable = false)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20, updatable = false)
    private OrderStatus toStatus;

    // Hành động gây ra thay đổi (null cho dòng tạo đơn).
    @Enumerated(EnumType.STRING)
    @Column(length = 30, updatable = false)
    private OrderAction action;

    // null nếu source = SYSTEM.
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", length = 30, updatable = false)
    private RoleName actorRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ChangeSource source;

    @Column(length = 500, updatable = false)
    private String reason;

    // Lần thử giao hàng liên quan (nếu có). Tạm là UUID thường, chưa có bảng attempts.
    @Column(name = "attempt_id", updatable = false)
    private UUID attemptId;

    @Column(name = "trace_id", length = 64, updatable = false)
    private String traceId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected OrderStatusHistory() { }

    public OrderStatusHistory(UUID orderId, long orderVersion, OrderStatus fromStatus,
                              OrderStatus toStatus, OrderAction action, UUID actorId,
                              RoleName actorRole, ChangeSource source, String reason,
                              UUID attemptId, String traceId, Instant occurredAt) {
        this.orderId = orderId;
        this.orderVersion = orderVersion;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.action = action;
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.source = source;
        this.reason = reason;
        this.attemptId = attemptId;
        this.traceId = traceId;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public long getOrderVersion() { return orderVersion; }
    public OrderStatus getFromStatus() { return fromStatus; }
    public OrderStatus getToStatus() { return toStatus; }
    public OrderAction getAction() { return action; }
    public UUID getActorId() { return actorId; }
    public RoleName getActorRole() { return actorRole; }
    public ChangeSource getSource() { return source; }
    public String getReason() { return reason; }
    public UUID getAttemptId() { return attemptId; }
    public String getTraceId() { return traceId; }
    public Instant getOccurredAt() { return occurredAt; }
}