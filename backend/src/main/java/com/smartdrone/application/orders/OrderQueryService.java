package com.smartdrone.application.orders;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.orders.dto.*;
import com.smartdrone.application.orders.scope.*;
import com.smartdrone.application.packages.PackageService;
import com.smartdrone.application.packages.dto.PackageView;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.OrderStatusHistoryRepository;

/** Task 3.7: đọc và tra cứu đơn. Mọi truy vấn đều bị giới hạn bởi OrderScope ngay trong SQL. */
@Service
@PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR','LOGISTICS_MANAGER')")
public class OrderQueryService {
    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final PackageService packages;
    private final OrderScopeResolver scopes;

    public OrderQueryService(OrderRepository orders, OrderStatusHistoryRepository history,
                             PackageService packages, OrderScopeResolver scopes) {
        this.orders = orders;
        this.history = history;
        this.packages = packages;
        this.scopes = scopes;
    }

    @Transactional(readOnly = true)
    public OrderPage list(UUID actorId, RoleName role, OrderListQuery q) {
        OrderScope scope = scopes.resolve(actorId, role);
        if (scope instanceof OrderScope.Nothing || stationOutsideScope(scope, q)) {
            return new OrderPage(List.of(), q.page(), q.pageSize(), 0); // rỗng, không lộ gì
        }
        Page<Order> result = orders.findAll(OrderSpecs.forList(scope, q),
                PageRequest.of(q.page() - 1, q.pageSize(), q.sort()));   // total cũng đã bị scope giới hạn
        // Một truy vấn lấy gói hàng cho cả trang (tránh N+1).
        Map<UUID, PackageView> pkgs = packages.findByOrderIds(
                result.getContent().stream().map(Order::getId).toList());
        List<OrderSummary> items = result.getContent().stream()
                .map(o -> OrderSummary.from(o, pkgs.get(o.getId()))).toList();
        return new OrderPage(items, q.page(), q.pageSize(), result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public OrderDetail get(UUID actorId, RoleName role, UUID orderId) {
        OrderScope scope = scopes.resolve(actorId, role);
        // Ngoài phạm vi hoặc không tồn tại đều là 404, không phân biệt được.
        Order order = orders.findOne(OrderSpecs.inScope(scope).and(OrderSpecs.hasId(orderId)))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        List<StatusHistoryView> events = history.findByOrderIdOrderByOrderVersionAsc(order.getId())
                .stream().map(StatusHistoryView::from).toList();
        return OrderDetail.from(order, packages.findByOrderId(order.getId()).orElse(null), events);
    }

    /** Nhân viên lọc theo trạm không thuộc phạm vi của mình thì trả trang rỗng. */
    private static boolean stationOutsideScope(OrderScope scope, OrderListQuery q) {
        return scope instanceof OrderScope.Stations s && q.stationId() != null
                && !s.stationIds().contains(q.stationId());
    }
}