package com.smartdrone.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.smartdrone.domain.entity.DeliveryAttempt;
import com.smartdrone.domain.enums.AttemptStatus;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    List<DeliveryAttempt> findByOrderIdOrderByAttemptNoAsc(UUID orderId);

    // Lan dang giu cho cua don (buoc giao lai/that bai dung de tra cho).
    Optional<DeliveryAttempt> findByOrderIdAndStatus(UUID orderId, AttemptStatus status);

    // So thu tu lan thu lon nhat cua don; chua co lan nao thi tra 0.
    @Query("select coalesce(max(a.attemptNo), 0) from DeliveryAttempt a where a.orderId = :orderId")
    int findMaxAttemptNo(@Param("orderId") UUID orderId);

    // So lan len lich dang giu cho o mot tram (gui hoac nhan) chong len khung [slotStart, slotEnd).
    @Query("""
            select count(a) from DeliveryAttempt a
            where a.status = com.smartdrone.domain.enums.AttemptStatus.ACTIVE
              and (a.pickupStationId = :stationId or a.dropoffStationId = :stationId)
              and a.slotStart < :slotEnd
              and a.slotEnd > :slotStart
            """)
    long countActiveOverlapping(@Param("stationId") UUID stationId,
                                @Param("slotStart") Instant slotStart,
                                @Param("slotEnd") Instant slotEnd);

    // Cac lan giu cho dang ACTIVE cua mot tram chong len khoang [rangeStart, rangeEnd).
    @Query("""
            select a from DeliveryAttempt a
            where a.status = com.smartdrone.domain.enums.AttemptStatus.ACTIVE
              and (a.pickupStationId = :stationId or a.dropoffStationId = :stationId)
              and a.slotStart < :rangeEnd
              and a.slotEnd > :rangeStart
            """)
    List<DeliveryAttempt> findActiveOverlapping(@Param("stationId") UUID stationId,
                                                @Param("rangeStart") Instant rangeStart,
                                                @Param("rangeEnd") Instant rangeEnd);
}