package com.smartdrone.application.orders;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.orders.dto.DeliveryProgressRequest;
import com.smartdrone.application.orders.dto.OrderDetail;
import com.smartdrone.application.orders.dto.StatusHistoryView;
import com.smartdrone.application.orders.scope.OrderSpecs;
import com.smartdrone.application.packages.PackageService;
import com.smartdrone.application.stations.StaffStationScope;
import com.smartdrone.application.workflow.OrderTransitionService;
import com.smartdrone.application.workflow.TransitionCommand;
import com.smartdrone.domain.entity.DeliveryAttempt;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.domain.enums.AttemptStatus;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.DeliveryAttemptRepository;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.OrderStatusHistoryRepository;

/**
 * 4.4: hai thao tác vận hành còn thiếu của state machine.
 * DISPATCH (PREPARING → IN_TRANSIT) do nhân viên ở TRẠM GỬI làm;
 * ARRIVE (IN_TRANSIT → AWAITING_PICKUP) do nhân viên ở TRẠM NHẬN làm.
 * Cả hai đi qua OrderTransitionService (không tự set status) và phát sự kiện sau commit
 * bằng publisher sẵn có (cùng nguồn với 4.1/4.3), không tạo publisher thứ hai.
 */
@Service
@PreAuthorize("hasRole('STATION_OPERATOR')")
public class OrderDeliveryProgressService {
    private final OrderRepository orders;
    private final DeliveryAttemptRepository attempts;
    private final OrderStatusHistoryRepository history;
    private final PackageService packages;
    private final StaffStationScope stations;
    private final OrderTransitionService transitions;
    private final OrderReviewEventPublisher events;

    public OrderDeliveryProgressService(OrderRepository orders, DeliveryAttemptRepository attempts,
            OrderStatusHistoryRepository history, PackageService packages, StaffStationScope stations,
            OrderTransitionService transitions, OrderReviewEventPublisher events) {
        this.orders = orders; this.attempts = attempts; this.history = history; this.packages = packages;
        this.stations = stations; this.transitions = transitions; this.events = events;
    }

    @Transactional
    public OrderDetail dispatch(UUID actor, UUID orderId, long version, DeliveryProgressRequest request) {
        return advance(actor, orderId, version, request, OrderAction.DISPATCH, OrderStatus.PREPARING, true);
    }

    @Transactional
    public OrderDetail arrive(UUID actor, UUID orderId, long version, DeliveryProgressRequest request) {
        return advance(actor, orderId, version, request, OrderAction.ARRIVE, OrderStatus.IN_TRANSIT, false);
    }

    private OrderDetail advance(UUID actor, UUID orderId, long version, DeliveryProgressRequest request,
            OrderAction action, OrderStatus expected, boolean pickupEnd) {
        String note = note(request);
        // Phạm vi theo đúng đầu: ngoài trạm được gán (hoặc không tồn tại) đều là 404, không lộ gì.
        Order order = orders.findOne(atEnd(actor, pickupEnd).and(OrderSpecs.hasId(orderId)))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (version != order.getVersion()) throw new ApiException(ErrorCode.VERSION_CONFLICT);
        if (order.getStatus() != expected) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Không thể " + action + " khi đơn đang ở trạng thái " + order.getStatus());
        }
        DeliveryAttempt attempt = currentAttempt(order);
        var result = transitions.transition(order, new TransitionCommand(action, actor,
                RoleName.STATION_OPERATOR, version, note, attempt.getId(), null));
        var entries = history.findByOrderIdOrderByOrderVersionAsc(orderId);
        var entry = entries.stream().filter(h -> h.getOrderVersion() == result.newVersion())
                .findFirst().orElseThrow();
        events.afterCommit(new OrderReviewedEvent(entry.getId(), orderId, order.getCustomerId(),
                order.getPickupStationId(), order.getDropoffStationId(), result.newVersion(),
                entry.getFromStatus(), entry.getToStatus(), action, result.event(),
                actor, RoleName.STATION_OPERATOR, entry.getReason(), entry.getOccurredAt()));
        return OrderDetail.from(order, packages.findByOrderId(orderId).orElse(null),
                entries.stream().map(StatusHistoryView::from).toList());
    }

    private Specification<Order> atEnd(UUID actor, boolean pickupEnd) {
        var ids = stations.stationIdsFor(actor, RoleName.STATION_OPERATOR);
        String column = pickupEnd ? "pickupStationId" : "dropoffStationId";
        return (root, query, cb) -> ids.isEmpty() ? cb.disjunction() : root.get(column).in(ids);
    }

    /** Phải có đúng một lần giao ACTIVE, khớp hai trạm của đơn và khớp lần SCHEDULE gần nhất. */
    private DeliveryAttempt currentAttempt(Order order) {
        List<DeliveryAttempt> active = attempts.findByOrderIdOrderByAttemptNoAsc(order.getId()).stream()
                .filter(a -> a.getStatus() == AttemptStatus.ACTIVE).toList();
        if (active.size() != 1) throw invalidAttempt();
        DeliveryAttempt attempt = active.getFirst();
        var lastScheduled = history.findByOrderIdOrderByOrderVersionAsc(order.getId()).stream()
                .filter(h -> h.getAction() == OrderAction.SCHEDULE).reduce((a, b) -> b);
        if (!attempt.getPickupStationId().equals(order.getPickupStationId())
                || !attempt.getDropoffStationId().equals(order.getDropoffStationId())
                || lastScheduled.isEmpty() || !attempt.getId().equals(lastScheduled.get().getAttemptId())) {
            throw invalidAttempt();
        }
        return attempt;
    }

    private static String note(DeliveryProgressRequest request) {
        if (request == null || request.note() == null || request.note().isBlank()) return null;
        if (request.note().length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Ghi chú tối đa 500 ký tự");
        }
        return request.note().strip();
    }

    private static ApiException invalidAttempt() {
        return new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                "Lần giao không còn hiệu lực hoặc chưa khớp lịch của đơn. Hãy tải lại và kiểm tra với điều phối viên.");
    }
}
