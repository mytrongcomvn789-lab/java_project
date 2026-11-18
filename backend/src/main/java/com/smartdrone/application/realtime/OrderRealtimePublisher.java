package com.smartdrone.application.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Chỉ publish SAU KHI transaction nghiệp vụ commit. Rollback thì listener không chạy, nên không có sự kiện ma.
 * Socket chỉ là kênh báo tin; dữ liệu bền vẫn là REST/lịch sử. Lỗi phát tin không làm hỏng request nghiệp vụ.
 */
@Component
public class OrderRealtimePublisher {
    private static final Logger log = LoggerFactory.getLogger(OrderRealtimePublisher.class);

    private final SimpMessagingTemplate messaging;

    public OrderRealtimePublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderStatusChanged(OrderStatusChanged changed) {
        try {
            messaging.convertAndSend(OrderChannel.destination(changed.orderId()),
                    OrderRealtimeEvent.from(changed));
        } catch (RuntimeException ex) {
            log.warn("REALTIME_PUBLISH_FAILED order={} event={}", changed.orderId(), changed.eventId(), ex);
        }
    }
}