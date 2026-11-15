package com.smartdrone.application.orders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class OrderReviewEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(OrderReviewEventPublisher.class);
    private final ApplicationEventPublisher publisher;
    public OrderReviewEventPublisher(ApplicationEventPublisher publisher) { this.publisher = publisher; }

    public void afterCommit(OrderReviewedEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Order review requires an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    publisher.publishEvent(event);
                } catch (RuntimeException ex) {
                    // Quyết định đã commit: lỗi realtime không được biến REST thành lỗi giả.
                    log.error("Review committed but event delivery failed, eventId={}", event.eventId(), ex);
                }
            }
        });
    }
}
