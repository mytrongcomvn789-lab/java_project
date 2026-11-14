package com.smartdrone.application.orders.scope;

import java.util.Set;
import java.util.UUID;

/** Phạm vi đơn mà một người được xem. Không có kiểu "xem tất cả". */
public sealed interface OrderScope {
    record Own(UUID customerId) implements OrderScope { }
    record Stations(Set<UUID> stationIds) implements OrderScope {
        public Stations { stationIds = Set.copyOf(stationIds); }
    }
    record Nothing() implements OrderScope { }
}