package com.smartdrone.application.scheduling;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.orders.dto.OrderDetail;
import com.smartdrone.application.orders.dto.StatusHistoryView;
import com.smartdrone.application.orders.scope.OrderScopeResolver;
import com.smartdrone.application.orders.scope.OrderSpecs;
import com.smartdrone.application.packages.PackageService;
import com.smartdrone.application.workflow.OrderTransitionService;
import com.smartdrone.application.workflow.TransitionCommand;
import com.smartdrone.domain.entity.DeliveryAttempt;
import com.smartdrone.domain.enums.AttemptStatus;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.domain.workflow.TransitionRules;
import com.smartdrone.infrastructure.persistence.DeliveryAttemptRepository;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.OrderStatusHistoryRepository;

/**
 * 4.2: điều phối viên lên lịch giao hàng (APPROVED -> SCHEDULED), cũng dùng để giao lại (FAILED -> SCHEDULED).
 * Kiểm tra slot, khóa trạm, giữ chỗ và chuyển trạng thái nằm chung một transaction.
 */
@Service
@PreAuthorize("hasRole('DISPATCHER')")
public class OrderScheduleService {

    private final OrderRepository orders;
    private final OrderScopeResolver scopes;
    private final OrderTransitionService transitions;
    private final OrderStatusHistoryRepository history;
    private final PackageService packages;
    private final DeliveryAttemptRepository attempts;
    private final ScheduleSlotPolicy slotPolicy;
    private final SlotCapacityService slotCapacity;
    private final Clock clock;

    public OrderScheduleService(OrderRepository orders, OrderScopeResolver scopes,
            OrderTransitionService transitions, OrderStatusHistoryRepository history,
            PackageService packages, DeliveryAttemptRepository attempts,
            ScheduleSlotPolicy slotPolicy, SlotCapacityService slotCapacity, Clock clock) {
        this.orders = orders;
        this.scopes = scopes;
        this.transitions = transitions;
        this.history = history;
        this.packages = packages;
        this.attempts = attempts;
        this.slotPolicy = slotPolicy;
        this.slotCapacity = slotCapacity;
        this.clock = clock;
    }

    @Transactional
    public OrderDetail schedule(UUID actorId, UUID orderId, long version, Instant slotStart) {
        Instant now = Instant.now(clock);

        var scope = scopes.resolve(actorId, RoleName.DISPATCHER);
        var order = orders.findOne(OrderSpecs.inScope(scope).and(OrderSpecs.hasId(orderId)))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));

        // Báo lỗi rẻ trước khi khóa trạm: slot sai, phiên bản cũ, trạng thái không cho lên lịch.
        slotPolicy.validate(slotStart, now);
        if (order.getVersion() != version) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
        if (TransitionRules.find(order.getStatus(), OrderAction.SCHEDULE).isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Không thể lên lịch khi đơn đang ở trạng thái " + order.getStatus());
        }
        Instant slotEnd = slotPolicy.slotEnd(slotStart);

        // Giao lại: trả chỗ của lần trước (nếu 4.8 chưa trả) để nó không tự chiếm chỗ của lần mới.
        attempts.findByOrderIdAndStatus(orderId, AttemptStatus.ACTIVE).ifPresent(a -> a.release(now));

        // Khóa trạm gửi và trạm nhận theo thứ tự id, rồi đếm chỗ. Đầy thì ném SLOT_FULL (409).
        slotCapacity.lockAndCheck(order.getPickupStationId(), order.getDropoffStationId(), slotStart, slotEnd);

        DeliveryAttempt attempt = new DeliveryAttempt(orderId, attempts.findMaxAttemptNo(orderId) + 1,
                order.getPickupStationId(), order.getDropoffStationId(), slotStart, slotEnd, actorId, now);
        try {
            attempts.saveAndFlush(attempt);
        } catch (DataIntegrityViolationException e) {
            // Trùng (order_id, attempt_no): có người vừa lên lịch cùng đơn.
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }

        // Cổng duy nhất đổi trạng thái: kiểm tra If-Match, luật, ghi lịch sử kèm attemptId.
        transitions.transition(order, new TransitionCommand(
                OrderAction.SCHEDULE, actorId, RoleName.DISPATCHER, version, null, attempt.getId(), null));

        var entries = history.findByOrderIdOrderByOrderVersionAsc(orderId);
        return OrderDetail.from(order, packages.findByOrderId(orderId).orElse(null),
                entries.stream().map(StatusHistoryView::from).toList());
    }
}