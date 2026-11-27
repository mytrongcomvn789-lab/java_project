package com.smartdrone.domain.enums;

/** Vòng đời một lần phân công drone cho đơn. */
public enum AssignmentStatus {
    /** Đã chọn drone và đặt trước thiết bị sạc ở trạm đích, chưa cất cánh. */
    ASSIGNED,
    /** Đơn đã khởi hành, drone đang bay. */
    IN_FLIGHT,
    /** Drone đã đến trạm đích. */
    COMPLETED,
    /** Hủy phân công (đơn không còn chờ vận chuyển); drone và thiết bị sạc được giải phóng. */
    CANCELLED
}
