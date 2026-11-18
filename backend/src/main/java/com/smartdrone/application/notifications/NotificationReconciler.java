package com.smartdrone.application.notifications;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.smartdrone.application.realtime.OrderStatusChanged;
import com.smartdrone.domain.entity.OrderStatusHistory;
import com.smartdrone.domain.workflow.TransitionRules;
import com.smartdrone.infrastructure.persistence.OrderStatusHistoryRepository;

/**
 * Lưới an toàn cho "lỗi gửi thông báo": order_status_history là nguồn sự thật bền vững, nên dòng lịch sử nào
 * chưa có thông báo (lần ghi sau commit bị lỗi, app tắt giữa chừng...) sẽ được tạo bù, idempotent theo eventId.
 * Chỉ xét dòng cũ hơn "grace" để không tranh với listener đang xử lý, và không quá "lookback".
 */
@Component
public class NotificationReconciler {
    private static final Logger log = LoggerFactory.getLogger(NotificationReconciler.class);
    private static final int BATCH = 200;

    private final OrderStatusHistoryRepository history;
    private final NotificationDispatcher dispatcher;
    private final Clock clock;
    private final long graceSeconds;
    private final long lookbackHours;

    public NotificationReconciler(OrderStatusHistoryRepository history, NotificationDispatcher dispatcher, Clock clock,
            @Value("${app.notifications.reconcile-grace-seconds:15}") long graceSeconds,
            @Value("${app.notifications.reconcile-lookback-hours:24}") long lookbackHours) {
        this.history = history;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.graceSeconds = graceSeconds;
        this.lookbackHours = lookbackHours;
    }

    @Scheduled(fixedDelayString = "${app.notifications.reconcile-interval:PT30S}",
               initialDelayString = "${app.notifications.reconcile-interval:PT30S}")
    public void reconcile() {
        Instant now = clock.instant();
        List<OrderStatusHistory> missing;
        try {
            missing = history.findWithoutNotification(now.minusSeconds(lookbackHours * 3600),
                    now.minusSeconds(graceSeconds), PageRequest.of(0, BATCH));
        } catch (RuntimeException ex) {
            log.warn("NOTIFICATION_RECONCILE_QUERY_FAILED", ex);
            return;
        }
        for (OrderStatusHistory h : missing) {
            String event = TransitionRules.find(h.getFromStatus(), h.getAction())
                    .map(rule -> rule.event().name()).orElse("ORDER_STATUS_CHANGED");
            dispatcher.dispatch(new OrderStatusChanged(h.getId(), h.getOrderId(), h.getOrderVersion(),
                    h.getFromStatus(), h.getToStatus(), event, h.getAttemptId(), h.getOccurredAt()));
        }
        if (!missing.isEmpty()) log.info("NOTIFICATION_RECONCILED candidates={}", missing.size());
    }
}