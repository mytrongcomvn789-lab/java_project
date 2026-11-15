package com.smartdrone.domain.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

/** Phân công tường minh; không có dòng phân công thì không có quyền theo trạm. */
@Entity
@Table(name = "staff_station_assignments", uniqueConstraints = @UniqueConstraint(
        name = "uq_staff_station_assignment", columnNames = {"staff_id", "station_id"}))
public class StaffStationAssignment {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false)
    private User staff;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;
    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    protected StaffStationAssignment() { }
    public StaffStationAssignment(User staff, Station station, Instant assignedAt) {
        this.staff = staff;
        this.station = station;
        this.assignedAt = assignedAt;
    }
    public UUID getId() { return id; }
}
