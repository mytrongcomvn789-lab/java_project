package com.smartdrone.application.orders.scope;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import com.smartdrone.application.orders.dto.OrderListQuery;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.domain.enums.OrderStatus;

public final class OrderSpecs {
    private OrderSpecs() { }

    public static Specification<Order> inScope(OrderScope scope) {
        return switch (scope) {
            case OrderScope.Own own ->
                    (root, q, cb) -> cb.equal(root.get("customerId"), own.customerId());
            case OrderScope.Stations s ->
                    (root, q, cb) -> cb.or(root.get("pickupStationId").in(s.stationIds()),
                                           root.get("dropoffStationId").in(s.stationIds()));
            case OrderScope.Nothing n ->
                    (root, q, cb) -> cb.disjunction(); // không khớp dòng nào
        };
    }

    public static Specification<Order> hasId(UUID id) {
        return (root, q, cb) -> cb.equal(root.get("id"), id);
    }

    /** Scope LUÔN được ghép đầu tiên; các bộ lọc chỉ thu hẹp thêm, không bao giờ mở rộng. */
    public static Specification<Order> forList(OrderScope scope, OrderListQuery f) {
        List<Specification<Order>> parts = new ArrayList<>();
        parts.add(inScope(scope));
        if (f.orderId() != null) parts.add(hasId(f.orderId()));
        if (f.status() != null) parts.add(hasStatus(f.status()));
        if (f.from() != null) parts.add(createdFrom(f.from()));
        if (f.to() != null) parts.add(createdBefore(f.to()));
        if (f.stationId() != null) parts.add(atStation(f.stationId()));
        return Specification.allOf(parts);
    }

    private static Specification<Order> hasStatus(OrderStatus s) {
        return (root, q, cb) -> cb.equal(root.get("status"), s);
    }
    private static Specification<Order> createdFrom(Instant from) {
        return (root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }
    private static Specification<Order> createdBefore(Instant to) { // "to" là mốc loại trừ
        return (root, q, cb) -> cb.lessThan(root.get("createdAt"), to);
    }
    private static Specification<Order> atStation(UUID stationId) {
        return (root, q, cb) -> cb.or(cb.equal(root.get("pickupStationId"), stationId),
                                      cb.equal(root.get("dropoffStationId"), stationId));
    }
}