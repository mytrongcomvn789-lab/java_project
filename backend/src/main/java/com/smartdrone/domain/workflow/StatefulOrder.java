package com.smartdrone.domain.workflow;

import java.util.UUID;

import com.smartdrone.domain.enums.OrderStatus;

/**
 * Entity Order (task 3.6) sẽ `implements StatefulOrder` và có cột @Version.
 * Chỉ OrderTransitionService được gọi changeStatus; không nơi nào khác được đổi trạng thái.
 */
public interface StatefulOrder {
    UUID getId();
    OrderStatus getStatus();
    long getVersion();
    void changeStatus(OrderStatus newStatus);
}