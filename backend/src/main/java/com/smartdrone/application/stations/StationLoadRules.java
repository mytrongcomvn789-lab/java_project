package com.smartdrone.application.stations;

import java.util.EnumSet;
import java.util.Set;

import com.smartdrone.domain.enums.OrderStatus;

/**
 * 4.6: quy tắc đếm số kiện theo vị trí. Nhóm đổi quy tắc thì chỉ sửa file này.
 * FAILED và RETURNING chưa được tính vì 4.8 chưa quy định kiện nằm ở đâu.
 */
public final class StationLoadRules {
    private StationLoadRules() { }

    /** Tại trạm GỬI: đã lên lịch nhưng kiện chưa tới trạm. */
    public static final Set<OrderStatus> SCHEDULED = EnumSet.of(OrderStatus.SCHEDULED);

    /** Tại trạm GỬI: kiện đã được nhân viên nhận (4.5), đang ở trạm, chờ xuất phát. */
    public static final Set<OrderStatus> PREPARING = EnumSet.of(OrderStatus.PREPARING);

    /** Tại trạm NHẬN: kiện đang trên đường tới. */
    public static final Set<OrderStatus> INBOUND = EnumSet.of(OrderStatus.IN_TRANSIT);

    /** Tại trạm NHẬN: kiện đã tới (4.4), đang ở trạm chờ khách đến lấy. */
    public static final Set<OrderStatus> WAITING_PICKUP = EnumSet.of(OrderStatus.AWAITING_PICKUP);
}