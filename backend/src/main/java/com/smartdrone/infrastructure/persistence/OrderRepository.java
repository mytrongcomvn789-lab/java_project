package com.smartdrone.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartdrone.domain.entity.Order;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    // Tìm đơn của đúng khách. Không thấy (đơn không tồn tại HOẶC của người khác) -> service trả 404,
    // không để lộ việc đơn đó có tồn tại.
    Optional<Order> findByIdAndCustomerId(UUID id, UUID customerId);
}