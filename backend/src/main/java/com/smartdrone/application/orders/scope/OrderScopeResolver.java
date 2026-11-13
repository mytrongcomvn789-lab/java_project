package com.smartdrone.application.orders.scope;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.smartdrone.application.stations.StaffStationScope;
import com.smartdrone.domain.enums.RoleName;

@Component
public class OrderScopeResolver {
    private final StaffStationScope staffScope;

    public OrderScopeResolver(StaffStationScope staffScope) {
        this.staffScope = staffScope;
    }

    public OrderScope resolve(UUID actorId, RoleName role) {
        return switch (role) {
            case CUSTOMER -> new OrderScope.Own(actorId);
            case STATION_OPERATOR, DISPATCHER, LOGISTICS_MANAGER -> {
                Set<UUID> ids = staffScope.stationIdsFor(actorId, role);
                yield ids.isEmpty() ? new OrderScope.Nothing() : new OrderScope.Stations(ids);
            }
            case SYSTEM_ADMIN -> new OrderScope.Nothing(); // admin không mặc định có quyền xem đơn
        };
    }
}