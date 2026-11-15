package com.smartdrone.application.orders;

import java.util.UUID;
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
import com.smartdrone.domain.entity.OrderStatusHistory;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.OrderStatusHistoryRepository;

/** 4.1: phạm vi giống danh sách 3.7, quyết định và lịch sử cùng transaction. */
@Service
@PreAuthorize("hasRole('DISPATCHER')")
public class OrderReviewService {
    private final OrderRepository orders;
    private final OrderScopeResolver scopes;
    private final OrderTransitionService transitions;
    private final OrderStatusHistoryRepository history;
    private final PackageService packages;
    private final OrderReviewEventPublisher events;

    public OrderReviewService(OrderRepository orders, OrderScopeResolver scopes,
            OrderTransitionService transitions, OrderStatusHistoryRepository history,
            PackageService packages, OrderReviewEventPublisher events) {
        this.orders = orders; this.scopes = scopes; this.transitions = transitions;
        this.history = history; this.packages = packages; this.events = events;
    }

    @Transactional
    public OrderDetail approve(UUID actorId, UUID orderId, long version) {
        return review(actorId, orderId, version, OrderAction.APPROVE, null);
    }

    @Transactional
    public OrderDetail reject(UUID actorId, UUID orderId, long version, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Lý do từ chối phải có 1–500 ký tự");
        }
        return review(actorId, orderId, version, OrderAction.REJECT, reason.strip());
    }

    private OrderDetail review(UUID actorId, UUID orderId, long version, OrderAction action, String reason) {
        var scope = scopes.resolve(actorId, RoleName.DISPATCHER);
        var order = orders.findOne(OrderSpecs.inScope(scope).and(OrderSpecs.hasId(orderId)))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        var result = transitions.transition(order, new TransitionCommand(
                action, actorId, RoleName.DISPATCHER, version, reason, null, null));
        // transition() đã flush cả order và history; không tự đổi status tại đây.
        var entries = history.findByOrderIdOrderByOrderVersionAsc(orderId);
        OrderStatusHistory entry = entries.stream()
                .filter(h -> h.getOrderVersion() == order.getVersion()).findFirst().orElseThrow();
        events.afterCommit(new OrderReviewedEvent(entry.getId(), orderId, order.getCustomerId(),
                order.getPickupStationId(), order.getDropoffStationId(), order.getVersion(),
                entry.getFromStatus(), entry.getToStatus(), action, result.event(),
                actorId, RoleName.DISPATCHER, entry.getReason(), entry.getOccurredAt()));
        return OrderDetail.from(order, packages.findByOrderId(orderId).orElse(null),
                entries.stream().map(StatusHistoryView::from).toList());
    }
}
