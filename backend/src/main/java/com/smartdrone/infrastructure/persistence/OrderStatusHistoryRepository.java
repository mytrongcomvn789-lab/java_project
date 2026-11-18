package com.smartdrone.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.smartdrone.domain.entity.OrderStatusHistory;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, UUID> {

    // Tên hàm tự mô tả câu truy vấn: tìm theo orderId, sắp xếp orderVersion tăng dần.
    List<OrderStatusHistory> findByOrderIdOrderByOrderVersionAsc(UUID orderId);

    // 4.9: dòng lịch sử (không phải dòng tạo đơn) chưa có thông báo, để NotificationReconciler tạo bù.
    @Query("""
            select h from OrderStatusHistory h
            where h.fromStatus is not null
              and h.occurredAt >= :since and h.occurredAt <= :before
              and not exists (select 1 from CustomerNotification n where n.eventId = h.id)
            order by h.occurredAt asc""")
    List<OrderStatusHistory> findWithoutNotification(@Param("since") Instant since,
                                                     @Param("before") Instant before, Pageable pageable);
}