package com.smartdrone.domain.entity;

import java.time.Instant;
import java.util.UUID;

import com.smartdrone.domain.enums.OrderStatus;

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

/** Hộp thông báo của khách. event_id (= id dòng order_status_history) là duy nhất nên không bao giờ có thông báo trùng. */
@Entity
@Table(name = "customer_notifications",
        uniqueConstraints = @UniqueConstraint(name = "uq_customer_notifications_event", columnNames = "event_id"),
        indexes = @Index(name = "ix_customer_notifications_user_created", columnList = "user_id, created_at"))
public class CustomerNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "event_name", nullable = false, length = 40, updatable = false)
    private String eventName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private OrderStatus status;

    @Column(name = "order_version", nullable = false, updatable = false)
    private long orderVersion;

    @org.hibernate.annotations.Nationalized
    @Column(nullable = false, length = 150, updatable = false)
    private String title;

    @org.hibernate.annotations.Nationalized
    @Column(nullable = false, length = 500, updatable = false)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // null = chưa đọc. "Đã gửi qua socket" KHÔNG làm đổi trường này.
    @Column(name = "read_at")
    private Instant readAt;

    protected CustomerNotification() { }

    public CustomerNotification(UUID eventId, UUID userId, UUID orderId, String eventName, OrderStatus status,
                                long orderVersion, String title, String body, Instant createdAt) {
        this.eventId = eventId;
        this.userId = userId;
        this.orderId = orderId;
        this.eventName = eventName;
        this.status = status;
        this.orderVersion = orderVersion;
        this.title = title;
        this.body = body;
        this.createdAt = createdAt;
    }

    public void markRead(Instant now) {
        if (readAt == null) readAt = now;
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public UUID getUserId() { return userId; }
    public UUID getOrderId() { return orderId; }
    public String getEventName() { return eventName; }
    public OrderStatus getStatus() { return status; }
    public long getOrderVersion() { return orderVersion; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
}