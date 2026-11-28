package com.smartdrone.domain.entity;

import java.time.Instant;
import java.util.UUID;

import com.smartdrone.domain.enums.AssignmentStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Phân công drone cho một đơn và đặt trước thiết bị sạc ở trạm đích.
 * Các mốc thời gian là ƯỚC TÍNH theo công thức (khoảng cách đường chim bay, tốc độ trung bình, tốc độ sạc giả lập).
 * UUID thường (không khóa ngoại) để xóa trạm/đơn không bị chặn.
 */
@Entity
@Table(name = "drone_assignments", indexes = {
        @Index(name = "ix_drone_assignments_order", columnList = "order_id"),
        @Index(name = "ix_drone_assignments_status", columnList = "status")})
public class DroneAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "drone_id", nullable = false)
    private UUID droneId;

    /** Trạm drone đang ở lúc được phân công (khác trạm gửi nghĩa là drone phải bay tới trạm gửi trước). */
    @Column(name = "origin_station_id", nullable = false)
    private UUID originStationId;

    @Column(name = "pickup_station_id", nullable = false)
    private UUID pickupStationId;

    @Column(name = "dropoff_station_id", nullable = false)
    private UUID dropoffStationId;

    /** Thiết bị sạc ở trạm đích được đặt trước cho drone này (null nếu không đặt được). */
    @Column(name = "reserved_charger_id")
    private UUID reservedChargerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssignmentStatus status = AssignmentStatus.ASSIGNED;

    @Column(name = "reposition_km", nullable = false)
    private double repositionKm;

    @Column(name = "flight_km", nullable = false)
    private double flightKm;

    /** Mức pin tiêu hao ước tính cho cả chặng (tới trạm gửi + giao). */
    @Column(name = "consumption_percent", nullable = false)
    private int consumptionPercent;

    /** Pin của drone lúc phân công (đã tính phần sạc thêm nếu phải chờ sạc). */
    @Column(name = "battery_at_departure", nullable = false)
    private int batteryAtDeparture;

    @Column(name = "planned_departure_at", nullable = false)
    private Instant plannedDepartureAt;

    @Column(name = "planned_arrival_at", nullable = false)
    private Instant plannedArrivalAt;

    @Column(name = "wait_minutes", nullable = false)
    private long waitMinutes;

    /** Mã lý do chờ: NONE, DRONE_CHARGING, DRONE_REPOSITION, DESTINATION_FULL. */
    @Column(name = "wait_reason", nullable = false, length = 30)
    private String waitReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DroneAssignment() { }

    public DroneAssignment(UUID orderId, UUID pickupStationId, UUID dropoffStationId, Instant now) {
        this.orderId = orderId;
        this.pickupStationId = pickupStationId;
        this.dropoffStationId = dropoffStationId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void applyPlan(UUID droneId, UUID originStationId, UUID reservedChargerId, double repositionKm,
            double flightKm, int consumptionPercent, int batteryAtDeparture, Instant departure, Instant arrival,
            long waitMinutes, String waitReason, Instant now) {
        this.droneId = droneId;
        this.originStationId = originStationId;
        this.reservedChargerId = reservedChargerId;
        this.repositionKm = repositionKm;
        this.flightKm = flightKm;
        this.consumptionPercent = consumptionPercent;
        this.batteryAtDeparture = batteryAtDeparture;
        this.plannedDepartureAt = departure;
        this.plannedArrivalAt = arrival;
        this.waitMinutes = waitMinutes;
        this.waitReason = waitReason;
        this.updatedAt = now;
    }

    public void markInFlight(Instant now) { this.status = AssignmentStatus.IN_FLIGHT; this.updatedAt = now; }
    public void complete(Instant now) { this.status = AssignmentStatus.COMPLETED; this.updatedAt = now; }
    public void cancel(Instant now) { this.status = AssignmentStatus.CANCELLED; this.updatedAt = now; }

    public boolean isActive() {
        return status == AssignmentStatus.ASSIGNED || status == AssignmentStatus.IN_FLIGHT;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getDroneId() { return droneId; }
    public UUID getOriginStationId() { return originStationId; }
    public UUID getPickupStationId() { return pickupStationId; }
    public UUID getDropoffStationId() { return dropoffStationId; }
    public UUID getReservedChargerId() { return reservedChargerId; }
    public AssignmentStatus getStatus() { return status; }
    public double getRepositionKm() { return repositionKm; }
    public double getFlightKm() { return flightKm; }
    public int getConsumptionPercent() { return consumptionPercent; }
    public int getBatteryAtDeparture() { return batteryAtDeparture; }
    public Instant getPlannedDepartureAt() { return plannedDepartureAt; }
    public Instant getPlannedArrivalAt() { return plannedArrivalAt; }
    public long getWaitMinutes() { return waitMinutes; }
    public String getWaitReason() { return waitReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
