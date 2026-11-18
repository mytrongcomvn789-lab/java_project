package com.smartdrone.application.notifications;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.realtime.OrderStatusChanged;
import com.smartdrone.domain.entity.CustomerNotification;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.infrastructure.persistence.CustomerNotificationRepository;
import com.smartdrone.infrastructure.persistence.OrderRepository;

/**
 * Ghi thông báo trong transaction RIÊNG (REQUIRES_NEW): nó chạy sau khi transaction nghiệp vụ đã commit,
 * nên nếu lỗi ở đây thì chỉ thông báo bị ảnh hưởng, trạng thái đơn đã lưu vẫn nguyên.
 * Không có @PreAuthorize vì được gọi từ listener/job nền (không có người đăng nhập).
 */
@Service
public class NotificationRecorder {
    private final OrderRepository orders;
    private final CustomerNotificationRepository notifications;

    public NotificationRecorder(OrderRepository orders, CustomerNotificationRepository notifications) {
        this.orders = orders;
        this.notifications = notifications;
    }

    /** Trả rỗng nếu không cần thông báo hoặc eventId này đã có thông báo rồi. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<CustomerNotification> record(OrderStatusChanged event) {
        if (event.from() == null) return Optional.empty();                 // vừa tạo đơn: khách tự thấy kết quả
        if (notifications.existsByEventId(event.eventId())) return Optional.empty();
        Optional<Order> order = orders.findById(event.orderId());
        if (order.isEmpty()) return Optional.empty();
        var content = NotificationTemplates.render(event.event(), event.to(), event.orderId());
        // Người nhận LUÔN là chủ đơn, lấy từ DB; không nhận từ payload sự kiện.
        var notification = new CustomerNotification(event.eventId(), order.get().getCustomerId(), event.orderId(),
                event.event(), event.to(), event.version(), content.title(), content.body(), event.occurredAt());
        // flush ngay: trùng event_id thì lỗi lộ ra ở đây (DataIntegrityViolationException), không đợi lúc commit.
        return Optional.of(notifications.saveAndFlush(notification));
    }
}