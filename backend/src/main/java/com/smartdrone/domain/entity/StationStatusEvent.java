package com.smartdrone.domain.entity;

import java.time.Instant;
import java.util.UUID;

import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.domain.enums.StationStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** 4.6: lịch sử đổi trạng thái vận hành của trạm (audit). Chỉ thêm mới, không sửa, không xóa. */
@Entity
@Table(name = "station_status_events",
        indexes = @Index(name = "ix_station_status_events_station", columnList = "station_id, occurred_at"))
public class StationStatusEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // UUID thường (không khóa ngoại) để lịch sử không bị chặn khi trạm thay đổi.
    @Column(name = "station_id", nullable = false, updatable = false)
    private UUID stationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, updatable = false, length = 20)
    private StationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 20)
    private StationStatus toStatus;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false, updatable = false, length = 30)
    private RoleName actorRole;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected StationStatusEvent() { }

    public StationStatusEvent(UUID stationId, StationStatus fromStatus, StationStatus toStatus,
            UUID actorId, RoleName actorRole, Instant occurredAt) {
        this.stationId = stationId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public UUID getStationId() { return stationId; }
    public StationStatus getFromStatus() { return fromStatus; }
    public StationStatus getToStatus() { return toStatus; }
    public UUID getActorId() { return actorId; }
    public RoleName getActorRole() { return actorRole; }
    public Instant getOccurredAt() { return occurredAt; }
}
