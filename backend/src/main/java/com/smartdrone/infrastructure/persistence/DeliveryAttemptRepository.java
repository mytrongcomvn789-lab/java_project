package com.smartdrone.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.smartdrone.domain.entity.DeliveryAttempt;
import com.smartdrone.domain.enums.AttemptStatus;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    List<DeliveryAttempt> findByOrderIdOrderByAttemptNoAsc(UUID orderId);

    // Lần đang giữ chỗ của đơn (bước giao lại/thất bại dùng để trả chỗ).
    Optional<DeliveryAttempt> findByOrderIdAndStatus(UUID orderId, AttemptStatus status);

    // Số thứ tự lần thử lớn nhất của đơn; chưa có lần nào thì trả 0.
    @Query("select coalesce(max(a.attemptNo), 0) from DeliveryAttempt a where a.orderId = :orderId")
    int findMaxAttemptNo(@Param("orderId") UUID orderId);

    
    // Số lần lên lịch đang giữ chỗ ở một trạm (gửi hoặc nhận) chồng lên khung [slotStart, slotEnd).
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
}