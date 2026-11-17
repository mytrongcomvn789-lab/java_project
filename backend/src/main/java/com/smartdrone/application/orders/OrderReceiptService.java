package com.smartdrone.application.orders;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.smartdrone.application.common.*;
import com.smartdrone.application.orders.dto.*;
import com.smartdrone.application.orders.scope.*;
import com.smartdrone.application.packages.PackageService;
import com.smartdrone.application.stations.StaffStationScope;
import com.smartdrone.application.workflow.*;
import com.smartdrone.domain.entity.*;
import com.smartdrone.domain.enums.*;
import com.smartdrone.infrastructure.persistence.*;

/** 4.5: receipt at the pickup station, not customer delivery confirmation (4.7). */
@Service
@PreAuthorize("hasRole('STATION_OPERATOR')")
public class OrderReceiptService {
    private final OrderRepository orders;
    private final UserRepository users;
    private final DeliveryAttemptRepository attempts;
    private final OrderPackageRepository packageRows;
    private final PackageService packages;
    private final OrderStatusHistoryRepository history;
    private final StaffStationScope stations;
    private final OrderTransitionService transitions;

    public OrderReceiptService(OrderRepository orders, UserRepository users, DeliveryAttemptRepository attempts,
            OrderPackageRepository packageRows, PackageService packages, OrderStatusHistoryRepository history,
            StaffStationScope stations, OrderTransitionService transitions) {
        this.orders = orders; this.users = users; this.attempts = attempts; this.packageRows = packageRows;
        this.packages = packages; this.history = history; this.stations = stations; this.transitions = transitions;
    }

    private Specification<Order> pickupScope(UUID actor) {
        var ids = stations.stationIdsFor(actor, RoleName.STATION_OPERATOR);
        return (root, query, cb) -> ids.isEmpty() ? cb.disjunction() : root.get("pickupStationId").in(ids);
    }

    @Transactional(readOnly = true)
    public OrderPage list(UUID actor, OrderListQuery query) {
        // The normal order read scope includes both ends. Receipt must only allow the pickup end.
        var ids = stations.stationIdsFor(actor, RoleName.STATION_OPERATOR);
        if (ids.isEmpty() || (query.stationId() != null && !ids.contains(query.stationId())))
            return new OrderPage(List.of(), query.page(), query.pageSize(), 0);
        var spec = OrderSpecs.forList(new OrderScope.Stations(ids), query).and(pickupScope(actor));
        if (query.stationId() != null)
            spec = spec.and((root, q, cb) -> cb.equal(root.get("pickupStationId"), query.stationId()));
        var page = orders.findAll(spec, PageRequest.of(query.page() - 1, query.pageSize(), query.sort()));
        var pkgs = packages.findByOrderIds(page.getContent().stream().map(Order::getId).toList());
        return new OrderPage(page.getContent().stream()
                .map(o -> OrderSummary.from(o, pkgs.get(o.getId()))).toList(),
                query.page(), query.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ReceiptContext context(UUID actor, UUID id) {
        var order = scopedOrder(actor, id);
        var pkg = packageRows.findByOrderId(id).orElse(null);
        var active = activeAttempts(id);
        return new ReceiptContext(detail(order), users.findById(order.getCustomerId()).map(User::getFullName).orElse(null), pkg == null ? null : pkg.getId(),
                active.size() == 1 ? ReceiptContext.Attempt.from(active.getFirst()) : null);
    }

    @Transactional
    public OrderDetail receive(UUID actor, UUID id, long version, ReceivePackageRequest request) {
        var order = scopedOrder(actor, id);
        if (version != order.getVersion()) throw new ApiException(ErrorCode.VERSION_CONFLICT);
        if (order.getStatus() != OrderStatus.SCHEDULED)
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Chỉ nhận kiện của đơn đã lên lịch");
        if (request == null || request.attemptId() == null || request.packageId() == null
                || (request.note() != null && request.note().length() > 500))
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Thiếu lần giao/kiện hoặc ghi chú quá 500 ký tự");
        var active = activeAttempts(id);
        if (active.size() != 1) throw invalidAttempt();
        var attempt = active.getFirst();
        var lastScheduled = history.findByOrderIdOrderByOrderVersionAsc(id).stream()
                .filter(h -> h.getAction() == OrderAction.SCHEDULE).reduce((a, b) -> b);
        if (!attempt.getId().equals(request.attemptId())
                || !attempt.getPickupStationId().equals(order.getPickupStationId())
                || !attempt.getDropoffStationId().equals(order.getDropoffStationId())
                || lastScheduled.isEmpty() || !attempt.getId().equals(lastScheduled.get().getAttemptId()))
            throw invalidAttempt();
        var pkg = packageRows.findByOrderId(id)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "Đơn chưa có kiện hàng"));
        if (!pkg.getId().equals(request.packageId()))
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Kiện hàng không khớp với đơn");
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        // Shared workflow atomically updates @Version and records actor/time/note/attempt.
        // Repeated/concurrent requests cannot add a second RECEIVE for this order version.
        transitions.transition(order, new TransitionCommand(OrderAction.RECEIVE, actor,
                RoleName.STATION_OPERATOR, version, note, attempt.getId(), null));
        return detail(order);
    }

    private Order scopedOrder(UUID actor, UUID id) {
        return orders.findOne(pickupScope(actor).and(OrderSpecs.hasId(id)))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }
    private List<DeliveryAttempt> activeAttempts(UUID id) {
        return attempts.findByOrderIdOrderByAttemptNoAsc(id).stream()
                .filter(a -> a.getStatus() == AttemptStatus.ACTIVE).toList();
    }
    private OrderDetail detail(Order order) {
        return OrderDetail.from(order, packages.findByOrderId(order.getId()).orElse(null),
                history.findByOrderIdOrderByOrderVersionAsc(order.getId()).stream()
                        .map(StatusHistoryView::from).toList());
    }
    private ApiException invalidAttempt() {
        return new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                "Lần giao không còn hiệu lực hoặc chưa khớp lịch của đơn. Hãy tải lại và kiểm tra với điều phối viên.");
    }
}
