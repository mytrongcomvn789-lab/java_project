package com.smartdrone.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartdrone.domain.entity.OrderStatusHistory;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, UUID> {

    // Tên hàm tự mô tả câu truy vấn: tìm theo orderId, sắp xếp orderVersion tăng dần.
    List<OrderStatusHistory> findByOrderIdOrderByOrderVersionAsc(UUID orderId);
}