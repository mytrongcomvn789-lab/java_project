package com.smartdrone.application.pickup;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.orders.scope.OrderScopeResolver;
import com.smartdrone.application.orders.scope.OrderSpecs;
import com.smartdrone.application.pickup.dto.DeliveryConfirmationView;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.DeliveryConfirmationRepository;
import com.smartdrone.infrastructure.persistence.OrderRepository;

/** Đọc bằng chứng giao hàng (cho 5.1, 6.4 và màn chi tiết đơn). Ai xem được đơn thì xem được bằng chứng. */
@Service
@PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR','LOGISTICS_MANAGER')")
public class DeliveryConfirmationQueryService {
    private final OrderRepository orders;
    private final OrderScopeResolver scopes;
    private final DeliveryConfirmationRepository confirmations;

    public DeliveryConfirmationQueryService(OrderRepository orders, OrderScopeResolver scopes,
            DeliveryConfirmationRepository confirmations) {
        this.orders = orders;
        this.scopes = scopes;
        this.confirmations = confirmations;
    }

    @Transactional(readOnly = true)
    public DeliveryConfirmationView find(UUID actor, RoleName role, UUID orderId) {
        orders.findOne(OrderSpecs.inScope(scopes.resolve(actor, role)).and(OrderSpecs.hasId(orderId)))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        return confirmations.findByOrderId(orderId).map(DeliveryConfirmationView::from)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}