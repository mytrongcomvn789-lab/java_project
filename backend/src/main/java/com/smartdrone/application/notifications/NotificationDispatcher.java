package com.smartdrone.application.notifications;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.smartdrone.application.notifications.dto.NotificationView;
import com.smartdrone.application.realtime.NotificationRealtimePublisher;
import com.smartdrone.application.realtime.OrderStatusChanged;
import com.smartdrone.domain.entity.CustomerNotification;

/** Ghi thông báo rồi đẩy realtime (và email nếu có). KHÔNG BAO GIỜ ném lỗi ra ngoài. */
@Component
public class NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationRecorder recorder;
    private final NotificationRealtimePublisher realtime;
    private final ObjectProvider<NotificationEmailSender> email;

    public NotificationDispatcher(NotificationRecorder recorder, NotificationRealtimePublisher realtime,
                                  ObjectProvider<NotificationEmailSender> email) {
        this.recorder = recorder;
        this.realtime = realtime;
        this.email = email;
    }

    public void dispatch(OrderStatusChanged event) {
        Optional<CustomerNotification> saved;
        try {
            saved = recorder.record(event);
        } catch (DataIntegrityViolationException duplicate) {
            log.debug("NOTIFICATION_DUPLICATE event={}", event.eventId()); // hai luồng cùng xử lý một event: bên kia thắng
            return;
        } catch (RuntimeException ex) {
            // Không rollback gì cả: trạng thái đơn đã commit. NotificationReconciler sẽ tạo bù ở lượt quét sau.
            log.warn("NOTIFICATION_RECORD_FAILED order={} event={}", event.orderId(), event.eventId(), ex);
            return;
        }
        saved.ifPresent(this::deliver);
    }

    private void deliver(CustomerNotification saved) {
        NotificationView view = NotificationView.from(saved);
        try {
            realtime.publish(saved.getUserId(), view);
        } catch (RuntimeException ex) {
            // Thông báo đã nằm trong DB; client sẽ thấy khi tải lại/kết nối lại.
            log.warn("NOTIFICATION_PUSH_FAILED user={} event={}", saved.getUserId(), saved.getEventId(), ex);
        }
        try {
            email.ifAvailable(sender -> sender.send(saved.getUserId(), view));
        } catch (RuntimeException ex) {
            log.warn("NOTIFICATION_EMAIL_FAILED user={} event={}", saved.getUserId(), saved.getEventId(), ex);
        }
    }
}