package com.smartdrone.application.notifications;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

import com.smartdrone.application.notifications.dto.NotificationView;
import com.smartdrone.application.realtime.NotificationRealtimePublisher;
import com.smartdrone.application.realtime.OrderStatusChanged;
import com.smartdrone.domain.entity.CustomerNotification;
import com.smartdrone.domain.enums.OrderStatus;

/** Lỗi ở kênh gửi (DB thông báo, socket, email) không bao giờ được văng ra làm hỏng nghiệp vụ giao hàng. */
class NotificationDispatcherTest {
    private final NotificationRecorder recorder = mock(NotificationRecorder.class);
    private final NotificationRealtimePublisher publisher = mock(NotificationRealtimePublisher.class);
    private final NotificationEmailSender sender = mock(NotificationEmailSender.class);
    private NotificationDispatcher dispatcher;

    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final OrderStatusChanged event = new OrderStatusChanged(UUID.randomUUID(), orderId, 1,
            OrderStatus.PENDING, OrderStatus.APPROVED, "ORDER_APPROVED", null, Instant.now());

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ObjectProvider<NotificationEmailSender> email = mock(ObjectProvider.class);
        doAnswer(inv -> {
            ((Consumer<NotificationEmailSender>) inv.getArgument(0)).accept(sender);
            return null;
        }).when(email).ifAvailable(any());
        dispatcher = new NotificationDispatcher(recorder, publisher, email);
    }

    private CustomerNotification saved() {
        return new CustomerNotification(event.eventId(), userId, orderId, "ORDER_APPROVED",
                OrderStatus.APPROVED, 1, "Tiêu đề", "Nội dung", Instant.now());
    }

    @Test
    void savedNotificationIsPushedToItsOwnerOnly() {
        when(recorder.record(event)).thenReturn(Optional.of(saved()));
        dispatcher.dispatch(event);
        verify(publisher).publish(eq(userId), any(NotificationView.class));
        verify(sender).send(eq(userId), any(NotificationView.class));
    }

    @Test
    void duplicateEventProducesNoSecondPush() {
        when(recorder.record(event)).thenReturn(Optional.empty());
        dispatcher.dispatch(event);
        verifyNoInteractions(publisher, sender);
    }

    @Test
    void uniqueKeyRaceIsSwallowed() {
        when(recorder.record(event)).thenThrow(new DataIntegrityViolationException("dup"));
        assertDoesNotThrow(() -> dispatcher.dispatch(event));
        verifyNoInteractions(publisher, sender);
    }

    @Test
    void recordFailureDoesNotPropagate() {
        when(recorder.record(event)).thenThrow(new IllegalStateException("db down"));
        assertDoesNotThrow(() -> dispatcher.dispatch(event));
        verifyNoInteractions(publisher, sender);
    }

    @Test
    void pushFailureDoesNotPropagateAndDoesNotBlockEmail() {
        when(recorder.record(event)).thenReturn(Optional.of(saved()));
        doThrow(new IllegalStateException("socket")).when(publisher).publish(any(), any());
        assertDoesNotThrow(() -> dispatcher.dispatch(event));
        verify(sender).send(eq(userId), any(NotificationView.class));
    }

    @Test
    void emailFailureDoesNotPropagate() {
        when(recorder.record(event)).thenReturn(Optional.of(saved()));
        doThrow(new IllegalStateException("smtp")).when(sender).send(any(), any());
        assertDoesNotThrow(() -> dispatcher.dispatch(event));
        verify(publisher).publish(eq(userId), any(NotificationView.class));
    }
}