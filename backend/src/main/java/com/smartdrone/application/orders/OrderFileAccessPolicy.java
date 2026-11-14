package com.smartdrone.application.orders;

import java.util.UUID;
import org.springframework.stereotype.Component;
import com.smartdrone.application.files.FileResourceAccessPolicy;
import com.smartdrone.application.orders.scope.*;
import com.smartdrone.application.packages.PackageService;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.OrderRepository;

/** Ai xem được đơn thì xem được ảnh gắn vào đơn đó. Cố ý không phụ thuộc PackageService/OrderQueryService (tránh vòng phụ thuộc). */
@Component
public class OrderFileAccessPolicy implements FileResourceAccessPolicy {
    private final OrderRepository orders;
    private final OrderScopeResolver scopes;

    public OrderFileAccessPolicy(OrderRepository orders, OrderScopeResolver scopes) {
        this.orders = orders;
        this.scopes = scopes;
    }

    @Override public String resourceType() { return PackageService.RESOURCE_TYPE; } // "ORDER"

    @Override
    public boolean canView(UUID actorId, RoleName role, UUID orderId) {
        return orders.findOne(OrderSpecs.inScope(scopes.resolve(actorId, role))
                .and(OrderSpecs.hasId(orderId))).isPresent();
    }
}