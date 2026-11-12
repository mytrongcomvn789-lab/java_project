package com.smartdrone.application.workflow;

import static com.smartdrone.domain.enums.OrderAction.*;
import static com.smartdrone.domain.enums.OrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.domain.entity.OrderStatusHistory;
import com.smartdrone.domain.enums.ChangeSource;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.domain.workflow.OrderEvent;
import com.smartdrone.domain.workflow.StatefulOrder;
import com.smartdrone.infrastructure.persistence.OrderStatusHistoryRepository;

class OrderTransitionServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T03:00:00Z"), ZoneOffset.UTC);
    private final UUID actor = UUID.randomUUID();
    private OrderStatusHistoryRepository repo;
    private OrderTransitionService service;

    @BeforeEach
    void setUp() {
        repo = mock(OrderStatusHistoryRepository.class);
        when(repo.saveAndFlush(any(OrderStatusHistory.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new OrderTransitionService(repo, clock);
    }

    /** Đơn giả: thay cho entity Order của 3.6. */
    private static class FakeOrder implements StatefulOrder {
        private final UUID id = UUID.randomUUID();
        private OrderStatus status;
        private long version;

        FakeOrder(OrderStatus status, long version) {
            this.status = status;
            this.version = version;
        }
        @Override public UUID getId() { return id; }
        @Override public OrderStatus getStatus() { return status; }
        @Override public long getVersion() { return version; }
        @Override public void changeStatus(OrderStatus newStatus) {
            this.status = newStatus;
            this.version++; // giả lập @Version tự tăng khi Hibernate ghi
        }
    }

    private TransitionCommand cmd(FakeOrder o, OrderAction action, RoleName role,
                                  String reason, UUID attemptId, UUID fileId) {
        return new TransitionCommand(action, actor, role, o.getVersion(), reason, attemptId, fileId);
    }

    private ApiException failureOf(FakeOrder order, TransitionCommand c) {
        return assertThrows(ApiException.class, () -> service.transition(order, c));
    }

    @Test
    void validTransitionChangesStatusAndWritesExactlyOneHistoryRow() {
        FakeOrder order = new FakeOrder(PENDING, 3);

        TransitionResult result = service.transition(order,
                cmd(order, APPROVE, RoleName.DISPATCHER, null, null, null));

        assertEquals(PENDING, result.from());
        assertEquals(APPROVED, result.to());
        assertEquals(OrderEvent.ORDER_APPROVED, result.event());
        assertEquals(4, result.newVersion());
        assertEquals(APPROVED, order.getStatus());

        ArgumentCaptor<OrderStatusHistory> captor = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(repo, times(1)).saveAndFlush(captor.capture());
        OrderStatusHistory h = captor.getValue();
        assertEquals(order.getId(), h.getOrderId());
        assertEquals(4, h.getOrderVersion());
        assertEquals(PENDING, h.getFromStatus());
        assertEquals(APPROVED, h.getToStatus());
        assertEquals(APPROVE, h.getAction());
        assertEquals(actor, h.getActorId());
        assertEquals(RoleName.DISPATCHER, h.getActorRole());
        assertEquals(ChangeSource.API, h.getSource());
        assertEquals(clock.instant(), h.getOccurredAt());
        assertNotNull(h.getTraceId());
    }

    @Test
    void staleVersionIsRejectedAndNothingChanges() {
        FakeOrder order = new FakeOrder(PENDING, 3);
        TransitionCommand stale = new TransitionCommand(APPROVE, actor, RoleName.DISPATCHER, 2, null, null, null);

        assertEquals(ErrorCode.VERSION_CONFLICT, failureOf(order, stale).getErrorCode());
        assertEquals(PENDING, order.getStatus());
        verifyNoInteractions(repo);
    }

    @Test
    void invalidTransitionLeavesOrderUntouchedAndWritesNoHistory() {
        FakeOrder order = new FakeOrder(PENDING, 0);
        var ex = failureOf(order, cmd(order, CONFIRM, RoleName.STATION_OPERATOR, null, null, UUID.randomUUID()));

        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());
        assertEquals(PENDING, order.getStatus());
        verifyNoInteractions(repo);
    }

    @Test
    void wrongRoleIsForbiddenAndWritesNoHistory() {
        FakeOrder order = new FakeOrder(PENDING, 0);
        var ex = failureOf(order, cmd(order, APPROVE, RoleName.CUSTOMER, null, null, null));

        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        assertEquals(PENDING, order.getStatus());
        verifyNoInteractions(repo);
    }

    @Test
    void missingReasonIsRejected() {
        FakeOrder order = new FakeOrder(PENDING, 0);
        var ex = failureOf(order, cmd(order, REJECT, RoleName.DISPATCHER, "  ", null, null));

        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        assertEquals(PENDING, order.getStatus());
        verifyNoInteractions(repo);
    }

    // ---- Hai yêu cầu đồng thời: bên thua nhận VERSION_CONFLICT ----

    @Test
    void loserGetsVersionConflictWhenUniqueConstraintFires() {
        doThrow(new DataIntegrityViolationException("uq_order_status_history_version"))
                .when(repo).saveAndFlush(any(OrderStatusHistory.class));
        FakeOrder order = new FakeOrder(PENDING, 3);

        var ex = failureOf(order, cmd(order, APPROVE, RoleName.DISPATCHER, null, null, null));
        assertEquals(ErrorCode.VERSION_CONFLICT, ex.getErrorCode());
    }

    @Test
    void loserGetsVersionConflictWhenOptimisticLockFails() {
        doThrow(new ObjectOptimisticLockingFailureException(OrderStatusHistory.class, UUID.randomUUID()))
                .when(repo).saveAndFlush(any(OrderStatusHistory.class));
        FakeOrder order = new FakeOrder(PENDING, 3);

        var ex = failureOf(order, cmd(order, APPROVE, RoleName.DISPATCHER, null, null, null));
        assertEquals(ErrorCode.VERSION_CONFLICT, ex.getErrorCode());
    }

    // ---- Vòng đời đầy đủ ----

    @Test
    void fullHappyPathWritesOneRowPerStepThenTerminalStateIsFinal() {
        FakeOrder order = new FakeOrder(PENDING, 0);
        UUID attempt = UUID.randomUUID(), photo = UUID.randomUUID();

        service.transition(order, cmd(order, APPROVE, RoleName.DISPATCHER, null, null, null));
        service.transition(order, cmd(order, SCHEDULE, RoleName.DISPATCHER, null, attempt, null));
        service.transition(order, cmd(order, RECEIVE, RoleName.STATION_OPERATOR, null, null, null));
        service.transition(order, cmd(order, DISPATCH, RoleName.STATION_OPERATOR, null, null, null));
        service.transition(order, cmd(order, ARRIVE, RoleName.STATION_OPERATOR, null, null, null));
        assertEquals(AWAITING_PICKUP, order.getStatus()); // đến trạm chưa phải đã giao
        service.transition(order, cmd(order, CONFIRM, RoleName.STATION_OPERATOR, null, null, photo));
        assertEquals(DELIVERED, order.getStatus());

        ArgumentCaptor<OrderStatusHistory> captor = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(repo, times(6)).saveAndFlush(captor.capture());
        List<OrderStatusHistory> rows = captor.getAllValues();
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i + 1, rows.get(i).getOrderVersion());
            if (i > 0) assertEquals(rows.get(i - 1).getToStatus(), rows.get(i).getFromStatus());
        }

        // DELIVERED là trạng thái kết thúc: mọi hành động đều bị từ chối, kể cả quản trị viên
        for (OrderAction a : OrderAction.values()) {
            TransitionCommand c = new TransitionCommand(
                    a, actor, RoleName.SYSTEM_ADMIN, order.getVersion(), "x", attempt, photo);
            assertEquals(ErrorCode.INVALID_STATE_TRANSITION, failureOf(order, c).getErrorCode());
        }
        verify(repo, times(6)).saveAndFlush(any()); // không phát sinh dòng nào thêm
    }

    @Test
    void retryAfterFailureAppendsNewHistoryAndKeepsOldRows() {
        FakeOrder order = new FakeOrder(SCHEDULED, 1);
        UUID firstAttempt = UUID.randomUUID(), retryAttempt = UUID.randomUUID();

        service.transition(order, cmd(order, FAIL, RoleName.DISPATCHER, "Gió mạnh", firstAttempt, null));
        service.transition(order, cmd(order, SCHEDULE, RoleName.DISPATCHER, null, retryAttempt, null));
        assertEquals(SCHEDULED, order.getStatus());

        ArgumentCaptor<OrderStatusHistory> captor = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(repo, times(2)).saveAndFlush(captor.capture());
        verifyNoMoreInteractions(repo); // chỉ thêm mới, không sửa hay xóa dòng cũ

        OrderStatusHistory failed = captor.getAllValues().get(0);
        OrderStatusHistory retry = captor.getAllValues().get(1);
        assertEquals(SCHEDULED, failed.getFromStatus());
        assertEquals(FAILED, failed.getToStatus());
        assertEquals("Gió mạnh", failed.getReason());
        assertEquals(firstAttempt, failed.getAttemptId());
        assertEquals(FAILED, retry.getFromStatus());
        assertEquals(SCHEDULED, retry.getToStatus());
        assertEquals(retryAttempt, retry.getAttemptId());
        assertNotEquals(failed.getAttemptId(), retry.getAttemptId());
        assertEquals(2, failed.getOrderVersion());
        assertEquals(3, retry.getOrderVersion());
    }
}