package com.smartdrone.application.orders;

import java.util.Comparator;
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
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.DeliveryAttemptRepository;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.OrderStatusHistoryRepository;

/**
 * 4.8: nhân viên đúng TRẠM GỬI của đơn xác nhận đã nhận hàng hoàn về (RETURNING → RETURNED).
 * Hàng hoàn về nơi gửi nên chỉ trạm gửi được hoàn tất; trạm khác hoặc đơn không tồn tại đều là 404.
 * Chỗ của lần thử đã được trả từ lúc FAILED/RETURNING, ở đây không giữ thêm chỗ nào.
 */
@Service
@PreAuthorize("hasRole('STATION_OPERATOR')")
public class OrderReturnService {
    private final OrderRepository orders;
    private final DeliveryAttemptRepository attempts;
    private final OrderStatusHistoryRepository history;
    private final PackageService packages;
    private final StaffStationScope stations;
    private final OrderTransitionService transitions;

    public OrderReturnService(OrderRepository orders, DeliveryAttemptRepository attempts,
            OrderStatusHistoryRepository history, PackageService packages, StaffStationScope stations,
            OrderTransitionService transitions) {
        this.orders = orders; this.attempts = attempts; this.history = history;
        this.packages = packages; this.stations = stations; this.transitions = transitions;
    }

    @Transactional
    public OrderDetail completeReturn(UUID actor, UUID orderId, long version, DeliveryProgressRequest request) {
        String note = null;
        if (request != null && request.note() != null && !request.note().isBlank()) {
            if (request.note().length() > 500) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Ghi chú tối đa 500 ký tự");
            }
            note = request.note().strip();
        }
        var ids = stations.stationIdsFor(actor, RoleName.STATION_OPERATOR);
        Specification<Order> atPickup = (root, query, cb) ->
                ids.isEmpty() ? cb.disjunction() : root.get("pickupStationId").in(ids);
        Order order = orders.findOne(atPickup.and(OrderSpecs.hasId(orderId)))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        UUID attemptId = attempts.findByOrderIdOrderByAttemptNoAsc(orderId).stream()
                .max(Comparator.comparingInt(DeliveryAttempt::getAttemptNo))
                .map(DeliveryAttempt::getId).orElse(null);
        transitions.transition(order, new TransitionCommand(OrderAction.COMPLETE_RETURN, actor,
                RoleName.STATION_OPERATOR, version, note, attemptId, null));
        var entries = history.findByOrderIdOrderByOrderVersionAsc(orderId);
        return OrderDetail.from(order, packages.findByOrderId(orderId).orElse(null),
                entries.stream().map(StatusHistoryView::from).toList());
    }
}
