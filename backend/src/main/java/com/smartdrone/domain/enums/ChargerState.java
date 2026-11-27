package com.smartdrone.domain.enums;

/** Trạng thái hiển thị của thiết bị sạc, suy ra từ dữ liệu (không lưu trực tiếp). */
public enum ChargerState {
    /** Không có drone, sẵn sàng nhận. */
    EMPTY,
    /** Có drone, pin chưa đạt mức mục tiêu. */
    CHARGING,
    /** Pin đã đạt mục tiêu nhưng drone vẫn còn cắm; thiết bị chưa trống cho tới khi drone rời đi. */
    FULL,
    /** Lỗi hoặc đang bảo trì. */
    MAINTENANCE
}
