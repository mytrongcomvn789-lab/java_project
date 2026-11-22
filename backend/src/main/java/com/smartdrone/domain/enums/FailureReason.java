package com.smartdrone.domain.enums;

/** 4.8: lý do giao thất bại (mã cố định để thống kê ở 5.1). OTHER bắt buộc kèm ghi chú. */
public enum FailureReason {
    RECIPIENT_ABSENT("Người nhận không có mặt / không nhận hàng"),
    STATION_UNAVAILABLE("Trạm không sẵn sàng"),
    WEATHER("Thời tiết xấu"),
    DRONE_FAULT("Sự cố drone"),
    OTHER("Lý do khác");

    private final String label;

    FailureReason(String label) { this.label = label; }

    public String label() { return label; }
}
