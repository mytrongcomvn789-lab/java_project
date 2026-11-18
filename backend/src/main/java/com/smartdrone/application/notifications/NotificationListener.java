package com.smartdrone.application.notifications;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.smartdrone.application.realtime.OrderStatusChanged;

/** Chỉ chạy SAU KHI transaction đổi trạng thái commit. Rollback thì không có thông báo ma. */
@Component
public class NotificationListener {
    private final NotificationDispatcher dispatcher;

    public NotificationListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderStatusChanged(OrderStatusChanged changed) {
        dispatcher.dispatch(changed);
    }
}