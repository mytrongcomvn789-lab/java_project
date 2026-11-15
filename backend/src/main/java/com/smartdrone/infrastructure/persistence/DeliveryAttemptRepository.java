package com.smartdrone.infrastructure.persistence;

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

    // Lần đang giữ chỗ của đơn (bước giao lại/thất bại dùng để trả chỗ).
    Optional<DeliveryAttempt> findByOrderIdAndStatus(UUID orderId, AttemptStatus status);

    // Số thứ tự lần thử lớn nhất của đơn; chưa có lần nào thì trả 0.
    @Query("select coalesce(max(a.attemptNo), 0) from DeliveryAttempt a where a.orderId = :orderId")
    int findMaxAttemptNo(@Param("orderId") UUID orderId);
}