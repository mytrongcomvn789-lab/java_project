package com.smartdrone.application.notifications;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.workflow.OrderEvent;

class NotificationTemplatesTest {

    @Test
    void everyWorkflowEventHasItsOwnTemplateWithOrderCode() {
        UUID orderId = UUID.randomUUID();
        for (OrderEvent event : OrderEvent.values()) {
            var content = NotificationTemplates.render(event.name(), OrderStatus.APPROVED, orderId);
            assertNotEquals(NotificationTemplates.FALLBACK_TITLE, content.title(), "Thiếu template cho " + event);
            assertTrue(content.body().contains(NotificationTemplates.shortCode(orderId)), event.name());
        }
    }

    @Test
    void contentFitsColumnLimitsAndUnknownEventFallsBackToStatusLabel() {
        UUID orderId = UUID.randomUUID();
        for (OrderEvent event : OrderEvent.values()) {
            var content = NotificationTemplates.render(event.name(), OrderStatus.APPROVED, orderId);
            assertTrue(content.title().length() <= 150 && content.body().length() <= 500, event.name());
        }
        var fallback = NotificationTemplates.render("SOMETHING_NEW", OrderStatus.IN_TRANSIT, orderId);
        assertEquals(NotificationTemplates.FALLBACK_TITLE, fallback.title());
        assertTrue(fallback.body().contains(OrderStatus.IN_TRANSIT.label()));
    }
}