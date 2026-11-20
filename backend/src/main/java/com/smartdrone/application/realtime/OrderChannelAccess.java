package com.smartdrone.application.realtime;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.orders.scope.OrderScope;
import com.smartdrone.application.orders.scope.OrderScopeResolver;
import com.smartdrone.application.orders.scope.OrderSpecs;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.OrderRepository;

/**
 * Ai được theo dõi kênh của đơn nào. Dùng đúng OrderScope + OrderSpecs của REST (3.7),
 * nên socket và REST không thể lệch quyền nhau. Đơn ngoài phạm vi và đơn không tồn tại cho cùng kết quả (false).
 */
@Service
public class OrderChannelAccess {
    private final OrderRepository orders;
    private final OrderScopeResolver scopes;

    public OrderChannelAccess(OrderRepository orders, OrderScopeResolver scopes) {
        this.orders = orders;
        this.scopes = scopes;
    }

    @Transactional(readOnly = true)
    public boolean canSubscribe(UUID actorId, RoleName role, UUID orderId) {
        OrderScope scope = scopes.resolve(actorId, role);
        if (scope instanceof OrderScope.Nothing) return false; // SYSTEM_ADMIN, nhân viên chưa có trạm
        return orders.findOne(OrderSpecs.inScope(scope).and(OrderSpecs.hasId(orderId))).isPresent();
    }
}