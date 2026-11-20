package com.smartdrone.domain.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

// Nhật ký thao tác quản trị (NFR-07). Chỉ thêm mới. Trang Audit Log sẽ đọc bảng này ở task sau.
// Không bao giờ ghi mật khẩu/token vào detail.
@Entity
@Table(name = "audit_logs", indexes = @Index(name = "ix_audit_logs_time", columnList = "occurred_at"))
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "actor_email", length = 150, updatable = false)
    private String actorEmail;

    @Column(name = "actor_role", length = 30, updatable = false)
    private String actorRole;

    @Column(nullable = false, length = 60, updatable = false)
    private String action;

    @Column(name = "target_type", nullable = false, length = 40, updatable = false)
    private String targetType;

    @Column(name = "target_id", length = 100, updatable = false)
    private String targetId;

    @Column(length = 1000, updatable = false)
    private String detail;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AuditLog() { }

    public AuditLog(UUID actorId, String actorEmail, String actorRole, String action,
            String targetType, String targetId, String detail, Instant occurredAt) {
        this.actorId = actorId;
        this.actorEmail = actorEmail;
        this.actorRole = actorRole;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail == null || detail.length() <= 1000 ? detail : detail.substring(0, 1000);
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public UUID getActorId() { return actorId; }
    public String getActorEmail() { return actorEmail; }
    public String getActorRole() { return actorRole; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}
