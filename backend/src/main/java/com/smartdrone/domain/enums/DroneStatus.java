package com.smartdrone.domain.enums;

/** Trạng thái của drone. Drone dùng chung giữa các trạm nên vị trí hiện tại nằm ở Drone.stationId. */
public enum DroneStatus {
    /** Đang đỗ tại trạm, không cắm sạc. */
    IDLE,
    /** Đang cắm vào một thiết bị sạc. */
    CHARGING,
    /** Đang bay làm nhiệm vụ (không thuộc trạm nào). */
    IN_FLIGHT,
    /** Ngừng hoạt động để bảo trì. */
    MAINTENANCE
}
