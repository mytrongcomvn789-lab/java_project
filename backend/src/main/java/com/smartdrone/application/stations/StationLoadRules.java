package com.smartdrone.application.stations;

import java.util.EnumSet;
import java.util.Set;

import com.smartdrone.domain.enums.OrderStatus;

/** 4.6: quy tắc đếm số kiện đang xử lý của trạm. Nhóm đổi quy tắc thì chỉ sửa file này. */
public final class StationLoadRules {
    private StationLoadRules() { }

    /** Kiện chờ xuất tại trạm gửi. */
    public static final Set<OrderStatus> WAITING_DISPATCH =
            EnumSet.of(OrderStatus.SCHEDULED, OrderStatus.PREPARING);

    /** Kiện đang trên đường tới trạm nhận. */
    public static final Set<OrderStatus> INBOUND = EnumSet.of(OrderStatus.IN_TRANSIT);

    /** Kiện đã tới trạm nhận, chờ khách đến lấy. */
    public static final Set<OrderStatus> WAITING_PICKUP = EnumSet.of(OrderStatus.AWAITING_PICKUP);
}